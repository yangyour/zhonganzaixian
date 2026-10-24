import {test,expect,type Page} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
const id='79000000-0000-4000-8000-000000000003',actor='79000000-0000-4000-8000-000000000002'
function entry(cancelled=false,key=actor){return{kind:'REQUEST',id,version:cancelled?1:0,command_id:key,recorded_at:'2026-10-24T12:00:00Z',
 state:{status:cancelled?'CANCELLED':'WAITING_CONFIGURATION',message_id:id,intent_id:id,receiver_id:actor,phase:'OPENED',
 cancellation_reason:'SYNTHETIC 取消重复规划',external_dispatch_enabled:false,reason:cancelled?'MANUALLY_CANCELLED_BEFORE_DISPATCH':'PUBLISHED_ROUTING_CONFIGURATION_REQUIRED'}}}
async function transport(page:Page,lose=false,blocked=false){
 const writes:{key:string|undefined;body:unknown;path:string}[]=[],commands=new Map<string,ReturnType<typeof entry>>()
 let cancelled=false
 await page.route('**/api/v1/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname.replace('/api/v1','')
  if(request.method()==='POST'){
   expect(path).toBe(`/notification-routing/requests/${id}/cancel`)
   const key=request.headers()['idempotency-key'];writes.push({key,body:request.postDataJSON(),path})
   if(blocked)return route.fulfill({status:409,json:{error:{code:'NOTIFICATION_CANCELLATION_BLOCKED',message:'已有投递登记，请核对原请求'},trace_id:'SYNTHETIC'}})
   cancelled=true
   commands.set(key,entry(true,key));if(lose&&writes.length===1)return route.abort('failed')
   return route.fulfill({json:{data:commands.get(key),trace_id:'SYNTHETIC'}})
  }
  let data:unknown
  if(path==='/notification-routing/policy')data={record:null,external_dispatch_enabled:false}
  else if(path==='/notification-routing/entries')data={items:[entry(cancelled)],next_id:null,next_time:null,source:'postgresql'}
  else if(path.startsWith('/notification-routing/commands/'))data=commands.get(path.split('/').at(-1)!)
  else throw new Error('Unexpected synthetic read: '+path)
  return route.fulfill({json:{data,trace_id:'SYNTHETIC'}})
 });return writes
}
for(const width of [1440,1024])test(`explicit cancellation and guarded dialog at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message))
 const writes=await transport(page);await page.goto('/qa/notification-cancellation.fixture.html')
 await page.getByRole('button',{name:'取消未派发请求',exact:true}).click()
 const dialog=page.getByRole('dialog',{name:'取消未派发通知请求'})
 await expect(dialog.getByRole('button',{name:'确认取消请求'})).toBeDisabled()
 await dialog.getByLabel('取消原因').fill('SYNTHETIC 取消重复规划');expect(writes).toHaveLength(0)
 await expect(dialog.getByRole('button',{name:'确认取消请求'})).toBeEnabled()
 await page.locator('.el-overlay').evaluate(async element=>{
  await Promise.all(element.getAnimations({subtree:true}).map(animation=>animation.finished.catch(()=>{})))
 })
 expect((await new AxeBuilder({page}).include('.el-dialog').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/notification-cancellation-${width}.png`,fullPage:true})
 await dialog.getByRole('button',{name:'确认取消请求'}).focus();await page.keyboard.press('Enter')
 await expect(dialog).toHaveCount(0);expect(writes).toHaveLength(1)
 expect(writes[0].body).toEqual({expected_version:0,reason:'SYNTHETIC 取消重复规划'})
 await expect(page.getByRole('cell',{name:'已取消',exact:false})).toBeVisible()
 await expect(page.getByRole('button',{name:'取消未派发请求',exact:true})).toHaveCount(0)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect(errors).toEqual([])
})
test('lost cancellation is read back with the original command and never resubmitted on reload',async({page})=>{
 const writes=await transport(page,true);await page.goto('/qa/notification-cancellation.fixture.html')
 await page.getByRole('button',{name:'取消未派发请求',exact:true}).click()
 const dialog=page.getByRole('dialog',{name:'取消未派发通知请求'})
 await dialog.getByLabel('取消原因').fill('SYNTHETIC 取消重复规划');await dialog.getByRole('button',{name:'确认取消请求'}).click()
 await expect(dialog).toContainText('取消结果尚未确认');expect(writes).toHaveLength(1)
 await page.reload();await expect(page.getByRole('button',{name:'核对原命令',exact:true})).toBeVisible();expect(writes).toHaveLength(1)
 await page.getByRole('button',{name:'核对原命令',exact:true}).click()
 await expect(page.getByRole('cell',{name:'已取消',exact:false})).toBeVisible();expect(writes).toHaveLength(1)
})
test('registered-delivery rejection preserves the reason and never presents a successful cancellation',async({page})=>{
 const writes=await transport(page,false,true);await page.goto('/qa/notification-cancellation.fixture.html')
 await page.getByRole('button',{name:'取消未派发请求',exact:true}).click()
 const dialog=page.getByRole('dialog',{name:'取消未派发通知请求'})
 await dialog.getByLabel('取消原因').fill('SYNTHETIC 取消重复规划');await dialog.getByRole('button',{name:'确认取消请求'}).click()
 await expect(dialog).toContainText('已有投递登记，请核对原请求')
 await expect(dialog.getByLabel('取消原因')).toHaveValue('SYNTHETIC 取消重复规划');expect(writes).toHaveLength(1)
 await dialog.getByRole('button',{name:'放弃取消'}).click();await expect(dialog).toHaveCount(0)
 await expect(page.getByRole('cell',{name:'已取消',exact:false})).toHaveCount(0)
 await page.getByRole('button',{name:'模拟撤销权限'}).click()
 await expect(page.getByRole('button',{name:'取消未派发请求',exact:true})).toHaveCount(0)
})
