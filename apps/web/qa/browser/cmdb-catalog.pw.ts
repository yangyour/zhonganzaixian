import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const fixture=JSON.parse(readFileSync(new URL('../../../../fixtures/cmdb-type-catalog-controller.json',import.meta.url),'utf8'))
const url='/qa/cmdb-catalog.fixture.html'
const endpoint='**/api/v1/cmdb/type-catalog'
for(const width of [320,390,768,1440]){
 test(`catalog keyboard, filtering, reflow and axe at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900})
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message))
  let reads=0;await page.route(endpoint,async route=>{reads++;expect(route.request().method()).toBe('GET');await route.fulfill({json:fixture})})
  await page.goto(url)
  const load=page.getByRole('button',{name:'查看 CMDB 类型字典'})
  await expect(load).toBeVisible();expect(reads).toBe(0)
  await load.focus();await page.keyboard.press('Enter')
  await expect(page.getByText('95 属性。未发布到运行配置。',{exact:false})).toBeVisible()
  const parent=page.getByLabel('筛选主类型');await parent.selectOption('FW')
  const types=page.getByRole('region',{name:'CMDB 分类列表，可滚动查看'})
  await expect(types.locator('tbody tr')).toHaveCount(4)
  await types.focus();await page.keyboard.press('End')
  await expect(types).toBeFocused()
  const summary=page.locator('summary');await summary.focus();await page.keyboard.press('Enter')
  const search=page.getByRole('searchbox',{name:'搜索属性'})
  await search.fill(' PRIMARY_IP ');await expect(page.getByRole('status').filter({hasText:'匹配 1 项'})).toBeVisible()
  await search.fill('不存在的属性');await expect(page.getByText('没有匹配的属性，请尝试其他关键词。')).toBeVisible()
  await page.getByRole('button',{name:'清除属性搜索'}).click()
  await expect(search).toHaveValue('');await expect(page.getByRole('status').filter({hasText:'匹配 95 项'})).toBeVisible()
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);expect(overflow).toBe(false)
  const scan=await new AxeBuilder({page}).include('.cmdb-catalog').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()
  expect(scan.violations.map(v=>({id:v.id,nodes:v.nodes.map(n=>n.target)}))).toEqual([])
  await page.screenshot({path:`../../tests/artifacts/cmdb-catalog-${width}.png`,fullPage:true})
  await page.getByRole('button',{name:'收起字典'}).click()
  await expect(page.getByRole('button',{name:'查看 CMDB 类型字典'})).toBeFocused()
  await expect(types).toHaveCount(0);expect(reads).toBe(1);expect(errors).toEqual([])
 })
}
test('failure is recoverable without stale content or duplicate submit',async({page})=>{
 let reads=0
 await page.route(endpoint,async route=>{reads++;await route.fulfill(reads===1?{status:503,json:{error:{code:'DEPENDENCY_UNAVAILABLE',message:'合成依赖故障'},trace_id:'SYNTHETIC'}}:{json:fixture})})
 await page.goto(url);await page.getByRole('button',{name:'查看 CMDB 类型字典'}).click()
 await expect(page.getByRole('alert')).toBeVisible();await expect(page.getByRole('table')).toHaveCount(0)
 await page.getByRole('button',{name:'重新读取字典'}).click();await expect(page.getByRole('table').first()).toBeVisible();expect(reads).toBe(2)
})
test('cancel aborts a slow read and keeps the panel collapsed',async({page})=>{
 let release!:()=>void;const gate=new Promise<void>(r=>{release=r})
 await page.route(endpoint,async route=>{await gate;await route.fulfill({json:fixture}).catch(()=>{})})
 await page.goto(url);await page.getByRole('button',{name:'查看 CMDB 类型字典'}).click()
 await expect(page.getByRole('button',{name:'重新读取字典'})).toBeDisabled()
 await page.getByRole('button',{name:'取消读取'}).click();release()
 await expect(page.getByRole('button',{name:'查看 CMDB 类型字典'})).toBeFocused()
 await expect(page.getByRole('table')).toHaveCount(0)
})
test('revocation clears visible catalog immediately',async({page})=>{
 await page.route(endpoint,route=>route.fulfill({json:fixture}));await page.goto(url)
 await page.getByRole('button',{name:'查看 CMDB 类型字典'}).click();await expect(page.getByRole('table').first()).toBeVisible()
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.locator('.cmdb-catalog')).toHaveCount(0)
})

test('33-column local preflight locates invalid rows without uploading the file',async({page})=>{
 const columns=JSON.parse(readFileSync(new URL('../../src/generated/cmdbImportSchema.json',import.meta.url),'utf8')).columns
 const row=Array<string>(33).fill('');row[0]='合成 CSV 设备';row[1]='SW';row[2]='192.0.2.1';row[16]='fw_adc'
 let catalogReads=0;const writes:string[]=[]
 page.on('request',r=>{if(!['GET','HEAD'].includes(r.method()))writes.push(r.url())})
 await page.route(endpoint,route=>{catalogReads++;return route.fulfill({json:fixture})})
 await page.goto(url);await page.getByRole('button',{name:'查看 CMDB 类型字典'}).click()
 const input=page.getByLabel('选择待检查的 CSV')
 await input.setInputFiles({name:'synthetic.csv',mimeType:'text/csv',buffer:Buffer.from(columns.map((c:{label:string})=>c.label).join(',')+'\r\n'+row.join(','))})
 await expect(page.getByText('子类型与主类型不匹配。')).toBeVisible()
 await page.getByRole('button',{name:'查看第 2 条记录，子类型'}).click()
 await expect(page.getByRole('region',{name:'第 2 条记录原值'})).toBeFocused()
 await expect(page.getByText('合成 CSV 设备',{exact:true})).toBeVisible()
 expect(writes).toEqual([]);expect(catalogReads).toBe(1)
 await page.getByRole('button',{name:'清除本地预检'}).click()
 await expect(page.getByText('合成 CSV 设备',{exact:true})).toHaveCount(0)
})
