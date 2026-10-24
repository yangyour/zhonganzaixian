import {test,expect,type Page} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {mappingScope as scope,mappingPage,publishedMapping,problemAck} from '../../tests/monitoringObjectMappings.fixture'

async function prepare(page:Page,{retry=false,enabled=true}={}){
 let attempts=0
 const keys:string[]=[]
 await page.route('**/api/v1/**',async route=>{
  const request=route.request(),url=new URL(request.url())
  if(request.method()==='POST'&&url.pathname.endsWith('/problem-intakes')){
   attempts++;keys.push(request.headers()['idempotency-key']!)
   expect(request.postDataJSON()).toEqual({expected_version:2,source_event_id:'44',expected_source_definition_sha256:'a'.repeat(64)})
   if(retry&&attempts===1){await route.abort('failed');return}
   await route.fulfill({json:{data:problemAck(),trace_id:'SYNTHETIC_UI'}});return
  }
  if(request.method()==='GET'&&url.pathname==='/api/v1/monitoring-object-mappings'){
   await route.fulfill({json:{data:mappingPage(),trace_id:'SYNTHETIC_UI'}});return
  }
  if(request.method()==='GET'&&url.pathname===`/api/v1/monitoring-object-mappings/${scope.id}`){
   if(attempts>0){await route.fulfill({status:503,json:{error:{code:'SOURCE_UNAVAILABLE',message:'合成后续读取失败'},trace_id:'SYNTHETIC_UI'}});return}
   await route.fulfill({json:{data:{...publishedMapping(),problem_intake_enabled:enabled},trace_id:'SYNTHETIC_UI'}});return
  }
  await route.fulfill({status:503,json:{error:{code:'SYNTHETIC_UNEXPECTED_API',message:'未授权的合成路径'},trace_id:'SYNTHETIC_UI'}})
 })
 await page.goto('/qa/monitoring-problem-intake.fixture.html')
 await page.getByRole('button',{name:'读取资产映射',exact:true}).click()
 await page.getByRole('button',{name:'查看与处理',exact:true}).click()
 await page.getByText('保存一条真实来源事件',{exact:true}).click()
 await page.getByLabel('来源事件 ID',{exact:true}).fill('44')
 return keys
}

test('1024px desktop problem intake keeps a durable receipt after follow-up failure and removes it on revocation',async({page},info)=>{
 await page.setViewportSize({width:1024,height:900});await prepare(page)
 await page.getByRole('button',{name:'读取问题并关联业务事件',exact:true}).focus();await page.keyboard.press('Enter')
 await expect(page.getByText('来源问题已关联业务事件',{exact:false})).toBeVisible()
 await expect(page.getByRole('link',{name:'查看关联事件'})).toHaveAttribute('href',`/events?event_id=${scope.asset}`)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:info.outputPath('desktop-1024.png'),fullPage:true})
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.getByRole('link',{name:'查看关联事件'})).toHaveCount(0)
})

test('1440px desktop retry reuses the original idempotency key',async({page},info)=>{
 await page.setViewportSize({width:1440,height:900});const keys=await prepare(page,{retry:true})
 await page.getByRole('button',{name:'读取问题并关联业务事件',exact:true}).click()
 await expect(page.getByRole('button',{name:'核对原请求',exact:true})).toBeVisible()
 await page.getByRole('button',{name:'核对原请求',exact:true}).click()
 await expect(page.getByRole('link',{name:'查看关联事件'})).toBeVisible()
 expect(keys).toHaveLength(2);expect(keys[0]).toBeTruthy();expect(keys[1]).toBe(keys[0])
 await page.screenshot({path:info.outputPath('desktop-retry-1440.png'),fullPage:true})
})

test('unconfigured intake remains absent while raw capture remains available',async({page})=>{
 await page.setViewportSize({width:1440,height:900});await prepare(page,{enabled:false})
 await expect(page.getByRole('button',{name:'读取问题并关联业务事件',exact:true})).toHaveCount(0)
 await expect(page.getByRole('button',{name:'读取来源并保存原始记录',exact:true})).toBeVisible()
})
