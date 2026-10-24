import {test,expect,type Page} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {mappingScope as scope,mappingPage,publishedMapping,pageIntakeAck} from '../../tests/monitoringObjectMappings.fixture'

async function prepare(page:Page,{retry=false,enabled=true}={}){
 const keys:string[]=[]
 await page.route('**/api/v1/**',async route=>{
  const request=route.request(),url=new URL(request.url())
  if(request.method()==='POST'&&url.pathname.endsWith('/page-intakes')){
   keys.push(request.headers()['idempotency-key']!)
   expect(request.postDataJSON()).toEqual({expected_version:2,expected_source_definition_sha256:'a'.repeat(64),first_event_id:'40',last_event_id:'99',from_clock:0,through_clock:100,page_size:1})
   if(retry&&keys.length===1){await route.abort('failed');return}
   await route.fulfill({json:{data:pageIntakeAck(),trace_id:'SYNTHETIC_UI'}});return
  }
  if(request.method()==='GET'&&url.pathname==='/api/v1/monitoring-object-mappings'){
   await route.fulfill({json:{data:mappingPage(),trace_id:'SYNTHETIC_UI'}});return
  }
  if(request.method()==='GET'&&url.pathname===`/api/v1/monitoring-object-mappings/${scope.id}`){
   if(keys.length){await route.fulfill({status:503,json:{error:{code:'SOURCE_UNAVAILABLE',message:'合成后续读取失败'},trace_id:'SYNTHETIC_UI'}});return}
   await route.fulfill({json:{data:{...publishedMapping(),page_intake_enabled:enabled},trace_id:'SYNTHETIC_UI'}});return
  }
  await route.fulfill({status:503,json:{error:{code:'SYNTHETIC_UNEXPECTED_API',message:'未授权的合成路径'},trace_id:'SYNTHETIC_UI'}})
 })
 await page.goto('/qa/monitoring-problem-intake.fixture.html')
 await page.getByRole('button',{name:'读取资产映射',exact:true}).click()
 await page.getByRole('button',{name:'查看与处理',exact:true}).click()
 if(enabled){
  await page.getByText('按有界窗口消费来源分页',{exact:true}).click()
  await page.getByLabel('起始事件 ID',{exact:true}).fill('40')
  await page.getByLabel('截止事件 ID',{exact:true}).fill('99')
  await page.getByLabel('开始时间（UTC）',{exact:true}).fill('1970-01-01T00:00')
  await page.getByLabel('截止时间（UTC）',{exact:true}).fill('1970-01-01T00:01:40')
  await page.getByRole('combobox',{name:'每页条数',exact:true}).selectOption('1')
 }
 return keys
}

test('1024px keyboard page intake retains its receipt and next location after refresh failure; revocation clears it',async({page},info)=>{
 await page.setViewportSize({width:1024,height:900});await prepare(page)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:info.outputPath('page-form-desktop-1024.png'),fullPage:true})
 const submit=page.getByRole('button',{name:'读取并提交一页',exact:true});await submit.focus();await page.keyboard.press('Enter')
 await expect(page.getByRole('region',{name:'来源分页回执'})).toContainText('本页已耐久提交 1 条，业务已关联 1 条')
 await expect(page.getByRole('region',{name:'来源分页回执'})).toContainText('下一页起始 ID：45')
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:info.outputPath('page-desktop-1024.png'),fullPage:true})
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.getByRole('region',{name:'来源分页回执'})).toHaveCount(0)
})

test('1440px page retry uses original request key and original window',async({page},info)=>{
 await page.setViewportSize({width:1440,height:900});const keys=await prepare(page,{retry:true})
 await page.getByRole('button',{name:'读取并提交一页',exact:true}).click()
 await expect(page.getByRole('button',{name:'核对原请求',exact:true})).toBeVisible()
 await page.getByRole('button',{name:'核对原请求',exact:true}).click()
 await expect(page.getByRole('region',{name:'来源分页回执'})).toBeVisible()
 expect(keys).toHaveLength(2);expect(keys[0]).toBeTruthy();expect(keys[1]).toBe(keys[0])
 await page.screenshot({path:info.outputPath('page-desktop-retry-1440.png'),fullPage:true})
})

test('page form is hidden behind explicit enablement',async({page})=>{
 await page.setViewportSize({width:1440,height:900});await prepare(page,{enabled:false})
 await expect(page.getByText('按有界窗口消费来源分页',{exact:true})).toHaveCount(0)
})
