import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { page as inventory, scope, id } from '../backup-inventory.data'
const before = inventory().items[0]!.version_id, after = id(99)
const summary = {tenant_id:scope.tenantId,asset_id:scope.assetId,before_version_id:before,after_version_id:after,status:'CHANGED',changed:true,before_lines:1,after_lines:1,hunks:[{before_start:0,before_count:1,after_start:0,after_count:1}],redaction:'ALL_TEXT_WITHHELD',noise_policy:'NONE_APPLIED_UNCONFIRMED',change_approved:false,automatic_ticket_allowed:false,actual_recovery_verified:false}
for (const width of [1024,1440]) test(`scoped binary download and redacted comparison at ${width}px`, async ({page}, info) => {
  await page.route('**/api/v1/**', route => {
    const request=route.request(),path=new URL(request.url()).pathname
    if(path.endsWith('/content')) {
      expect(request.method()).toBe('GET');expect(request.headers().accept).toBe('application/octet-stream')
      return route.fulfill({contentType:'application/octet-stream',body:Buffer.alloc(247),headers:{'Content-Disposition':`attachment; filename="backup-${before}.envelope"`}})
    }
    if(path.endsWith('/diff')) {
      expect(request.method()).toBe('GET');const query=new URL(request.url()).searchParams
      expect(Object.fromEntries(query)).toEqual({before_version_id:before,after_version_id:after})
      return route.fulfill({json:{data:summary,trace_id:'SYNTHETIC_BROWSER_ONLY'}})
    }
    expect(path).toBe(`/api/v1/assets/${scope.assetId}/backup-versions`)
    return route.fulfill({json:{data:inventory(),trace_id:'SYNTHETIC_BROWSER_ONLY'}})
  })
  await page.setViewportSize({width,height:1000});await page.goto('/qa/backup-version-content.fixture.html')
  await page.getByRole('button',{name:'读取已登记版本',exact:true}).click()
  await page.getByLabel('下载版本标识',{exact:true}).fill(before)
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'下载所选格式',exact:true}).click()
  expect((await downloaded).suggestedFilename()).toBe(`backup-${before}.envelope`)
  await page.getByLabel('比较前版本标识',{exact:true}).fill(before);await page.getByLabel('比较后版本标识',{exact:true}).fill(after)
  await page.getByRole('button',{name:'比较两个版本',exact:true}).focus();await page.keyboard.press('Enter')
  await expect(page.getByText('存在差异，正文已全部隐藏',{exact:true})).toBeVisible()
  await expect(page.getByRole('table',{name:'脱敏差异位置，行位置从0开始',exact:true})).toBeVisible()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
  expect((await new AxeBuilder({page}).include('.backup-inventory').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({path:info.outputPath(`backup-content-${width}.png`),fullPage:true})
})
test('unconfigured content shows error and explicit retry keeps original version and format',async({page})=>{
  const requests:string[]=[]
  await page.route('**/api/v1/**',route=>{
    if(new URL(route.request().url()).pathname.endsWith('/content')) {requests.push(route.request().url());return route.fulfill({status:503,json:{error:{code:'BACKUP_CONTENT_UNAVAILABLE',message:'合成未配置'},trace_id:'SYNTHETIC_BROWSER_ONLY'}})}
    return route.fulfill({json:{data:inventory(),trace_id:'SYNTHETIC_BROWSER_ONLY'}})
  })
  await page.goto('/qa/backup-version-content.fixture.html');await page.getByRole('button',{name:'读取已登记版本',exact:true}).click()
  await page.getByLabel('下载版本标识',{exact:true}).fill(before);await page.getByLabel('文件格式',{exact:true}).selectOption('plaintext')
  await page.getByRole('button',{name:'下载所选格式',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible()
  await page.getByText('查看排查信息',{exact:true}).click();await expect(page.getByText('合成未配置',{exact:true})).toBeVisible()
  await page.getByLabel('下载版本标识',{exact:true}).fill(after);await page.getByRole('button',{name:'重试原内容请求',exact:true}).click()
  await expect.poll(()=>requests.length).toBe(2);expect(requests[1]).toBe(requests[0])
})
test('cancel then revoke context prevents an ignored-abort response from delivering a file',async({page})=>{
  let release!:()=>void;const held=new Promise<void>(resolve=>{release=resolve});const downloads:string[]=[]
  page.on('download',value=>downloads.push(value.suggestedFilename()))
  await page.route('**/api/v1/**',async route=>{
    if(new URL(route.request().url()).pathname.endsWith('/content')) {await held;await route.fulfill({contentType:'application/octet-stream',body:Buffer.alloc(247)}).catch(()=>{});return}
    await route.fulfill({json:{data:inventory(),trace_id:'SYNTHETIC_BROWSER_ONLY'}})
  })
  try {
    await page.goto('/qa/backup-version-content.fixture.html');await page.getByRole('button',{name:'读取已登记版本',exact:true}).click()
    await page.getByLabel('下载版本标识',{exact:true}).fill(before);await page.getByRole('button',{name:'下载所选格式',exact:true}).click()
    await page.getByRole('button',{name:'取消内容请求',exact:true}).click();await expect(page.getByText('请求已取消，未保留差异结果。',{exact:true})).toBeVisible()
    await page.getByRole('button',{name:'模拟撤销当前上下文',exact:true}).click();release()
    await expect(page.getByRole('heading',{name:'版本访问与脱敏差异',exact:true})).toHaveCount(0);expect(downloads).toEqual([])
  } finally {release()}
})
