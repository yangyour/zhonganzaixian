import {test,expect,type Page,type Route} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {id as exportId,scope as exportScope,summary,detail,running,page as exportPage} from '../../tests/assetExport.fixture'
import {row,current,history,revision} from '../../tests/ownPreference.fixture'
const entry='/qa/export-preference-recovery.fixture.html'
const ok=(route:Route,data:unknown)=>route.fulfill({json:{data,trace_id:'SYNTHETIC_BROWSER_ONLY'}})
const failed=(route:Route)=>route.fulfill({status:503,json:{error:{code:'UPSTREAM_UNAVAILABLE',message:'合成读取失败'},trace_id:'SYNTHETIC_BROWSER_ONLY'}})
function publication(published=false){return{job_id:exportId,tenant_id:exportScope.tenantId,created_by:exportScope.actorId,permission_version:1,version:published?2:0,status:published?'PUBLISHED':'NOT_PUBLISHED',prepared:published,file_sha256:published?'c'.repeat(64):null,size_bytes:published?4:null,row_count:published?0:null,stored_at:published?'2026-10-04T00:00:00Z':null,observed_at:'2026-10-04T00:00:00Z',publication_enabled:published,download_grant_available:published,notification_sent:false,source:'postgresql'}}
async function exportReads(page:Page){const methods:string[]=[];await page.route('**/api/v1/**',route=>{const req=route.request(),url=new URL(req.url());methods.push(req.method());expect(req.method()).toBe('GET');if(url.pathname==='/api/v1/asset-export-jobs')return ok(route,exportPage([summary()]));if(url.pathname.endsWith('/publication'))return ok(route,publication());if(url.pathname==='/api/v1/asset-export-jobs/'+exportId)return ok(route,detail());throw Error('Unexpected synthetic API '+url.pathname)});return methods}
async function preferenceReads(page:Page){const methods:string[]=[];await page.route('**/api/v1/**',route=>{methods.push(route.request().method());expect(route.request().method()).toBe('GET');const url=new URL(route.request().url());if(url.pathname==='/api/v1/my/notification-preferences')return ok(route,current());if(url.pathname.endsWith('/history'))return ok(route,history());throw Error('Unexpected synthetic API '+url.pathname)});return methods}
for(const width of [320,390,768,1440]){
 test(`export keyboard controls reflow and axe at ${width}px`,async({page},info)=>{
  const methods=await exportReads(page);await page.setViewportSize({width,height:900});await page.goto(entry)
  await expect(page.getByRole('heading',{name:/资产台账导出/})).toBeVisible();await expect(page.getByRole('table')).toBeVisible()
  await page.getByRole('button',{name:'新建导出请求',exact:true}).click();const ack=page.getByRole('checkbox',{name:'确认仅准备不发布'});await ack.focus();await page.keyboard.press('Space');await expect(page.getByRole('button',{name:'提交导出申请',exact:true})).toBeEnabled()
  const choice=page.getByRole('button',{name:'读取当前进度',exact:true});await choice.focus();await page.keyboard.press('Enter');await expect(page.getByRole('heading',{name:'导出进度与文件发布',exact:true})).toBeVisible()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
  expect((await new AxeBuilder({page}).include('.export-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({path:info.outputPath(`export-${width}.png`),fullPage:true});expect(methods.every(m=>m==='GET')).toBe(true)
 })
 test(`preference keyboard draft reflow and axe at ${width}px`,async({page},info)=>{
  const methods=await preferenceReads(page);await page.setViewportSize({width,height:900});await page.goto(entry+'?page=preference');await page.getByRole('button',{name:'以当前版本开始编辑'}).click()
  const level=page.getByRole('combobox',{name:'请求通知等级'});await level.selectOption('ALL');await level.focus();await page.keyboard.press('Tab');await expect(page.getByRole('combobox',{name:'渠道请求状态'})).toBeFocused()
  await expect(page.locator('.current-preference')).toContainText('未生效');expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
  expect((await new AxeBuilder({page}).include('.own-preference-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({path:info.outputPath(`preference-${width}.png`),fullPage:true});expect(methods.every(m=>m==='GET')).toBe(true)
 })
}
test('export cancellation keeps typed filters and restores status focus without restarting polling',async({page})=>{
 const methods=await exportReads(page);let count=0,release!:()=>void;const gate=new Promise<void>(r=>release=r)
 await page.route('**/api/v1/asset-export-jobs?*',async route=>{methods.push(route.request().method());if(++count===2)await gate;await ok(route,exportPage([summary()])).catch(()=>{})})
 await page.goto(entry);await expect(page.getByRole('table')).toBeVisible();await page.getByRole('button',{name:'新建导出请求',exact:true}).click();await page.getByRole('checkbox',{name:'导出文本不限'}).uncheck();await page.getByRole('textbox',{name:'导出检索文本',exact:true}).fill('保留输入')
 await page.getByRole('button',{name:'刷新任务列表'}).click();await page.getByRole('button',{name:'取消导出作业读取'}).click();release()
 await expect(page.locator('.read-recovery [role=status]')).toBeFocused();await expect(page.getByRole('textbox',{name:'导出检索文本',exact:true})).toHaveValue('保留输入');await page.getByRole('button',{name:'重试原导出作业读取'}).click();await expect(page.getByRole('table')).toBeVisible();await expect(page.getByText('准备进度自动刷新已暂停；',{exact:false})).toBeVisible();expect(methods.every(m=>m==='GET')).toBe(true)
})
test('preference cancellation retains edited level and rejects late current snapshot',async({page})=>{
 await preferenceReads(page);let count=0,release!:()=>void;const gate=new Promise<void>(r=>release=r)
 await page.route('**/api/v1/my/notification-preferences',async route=>{if(++count===2){await gate;await ok(route,current(row(99))).catch(()=>{});return}return ok(route,current())})
 await page.goto(entry+'?page=preference');await page.getByRole('button',{name:'以当前版本开始编辑'}).click();await page.getByRole('combobox',{name:'请求通知等级'}).selectOption('ALL');await page.getByRole('button',{name:'刷新当前记录'}).click();await page.getByRole('button',{name:'取消本人偏好读取'}).click();release()
 await expect(page.locator('.read-recovery [role=status]')).toBeFocused();await expect(page.getByRole('combobox',{name:'请求通知等级'})).toHaveValue('ALL');await page.getByRole('button',{name:'重试原本人偏好读取'}).click();await expect(page.getByRole('heading',{name:'当前版本 v1',exact:true})).toBeVisible();await expect(page.getByRole('combobox',{name:'请求通知等级'})).toHaveValue('ALL')
})
test('unknown grant plus failed refresh recovers original key and never downloads automatically',async({page})=>{
 let reads=0;const commands:{key:string|undefined;body:string|null}[]=[],expires=new Date(Date.now()+900_000).toISOString(),url='/api/v1/asset-export-jobs/'+exportId+'/content?grant='+Date.parse(expires)+'.'+'a'.repeat(43);let downloads=0
 page.on('download',()=>downloads++)
 await page.route('**/api/v1/**',async route=>{const req=route.request(),path=new URL(req.url()).pathname
  if(path.endsWith('/content-grants')){commands.push({key:req.headers()['idempotency-key'],body:req.postData()});expect(req.method()).toBe('POST');if(commands.length===1)return route.abort('failed');return ok(route,{job_id:exportId,content_url:url,expires_at:expires,file_sha256:'c'.repeat(64),size_bytes:4})}
  expect(req.method()).toBe('GET');if(path==='/api/v1/asset-export-jobs')return ok(route,exportPage([summary()]));if(path.endsWith('/publication'))return ++reads===2?failed(route):ok(route,publication(true));if(path==='/api/v1/asset-export-jobs/'+exportId)return ok(route,{...running(),status:'SUCCEEDED'});throw Error('Unexpected access or automatic download '+path)
 })
 await page.goto(entry);await page.getByRole('button',{name:'读取当前进度',exact:true}).click();await page.getByRole('button',{name:'获取下载链接'}).click();await expect(page.getByRole('button',{name:'重试提交',exact:true})).toBeVisible();await page.getByRole('button',{name:'刷新发布状态'}).click();await expect(page.locator('.publication-panel .api-error')).toBeVisible();await page.getByRole('button',{name:'重试提交',exact:true}).click()
 await expect(page.getByRole('link',{name:'下载台账 XLSX'})).toHaveAttribute('href',url);expect(commands).toHaveLength(2);expect(commands[0]!.key).toBeTruthy();expect(commands[1]).toEqual(commands[0]);expect(downloads).toBe(0)
})
test('preference failed continuation retries version 6 boundary with original rows',async({page})=>{
 let nextReads=0;const boundaries:(string|null)[]=[]
 await page.route('**/api/v1/**',route=>{const url=new URL(route.request().url());expect(route.request().method()).toBe('GET');if(url.pathname==='/api/v1/my/notification-preferences')return ok(route,current(row(20)));if(url.pathname.endsWith('/history')){const before=url.searchParams.get('before_version');boundaries.push(before);if(before!==null){expect(before).toBe('6');if(++nextReads===1)return failed(route);return ok(route,history([5,4,3,2,1].map(v=>revision(v))))}return ok(route,{...history(Array.from({length:15},(_,n)=>revision(20-n))),has_more:true,next_before_version:6})}throw Error(url.pathname)})
 await page.goto(entry+'?page=preference');await page.getByRole('button',{name:'加载历史记录'}).click();await page.getByRole('button',{name:'下一页本人历史'}).click();await page.getByRole('button',{name:'重试原本人偏好读取'}).click();await expect(page.locator('.history-toggle')).toHaveCount(5);expect(boundaries).toEqual([null,'6','6'])
})
for(const mode of ['export','preference'])test(`${mode} permission revocation removes private fields and read controls`,async({page})=>{
 if(mode==='export')await exportReads(page);else await preferenceReads(page)
 await page.goto(entry+(mode==='preference'?'?page=preference':''))
 if(mode==='export'){await expect(page.getByRole('table')).toBeVisible();await page.getByRole('button',{name:'新建导出请求',exact:true}).click();await page.getByRole('checkbox',{name:'导出文本不限'}).uncheck();await page.getByRole('textbox',{name:'导出检索文本',exact:true}).fill('private synthetic')}
 else{await page.getByRole('button',{name:'以当前版本开始编辑'}).click();await page.getByRole('combobox',{name:'请求通知等级'}).selectOption('ALL')}
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.locator(mode==='export'?'.export-form':'.preference-form')).toHaveCount(0);await expect(page.locator('.read-recovery')).toHaveCount(0);await expect(page.getByText('未展示旧数据',{exact:false})).toBeVisible()
})
test('preference account version change removes old editor without writing',async({page})=>{
 const methods=await preferenceReads(page);await page.goto(entry+'?page=preference');await page.getByRole('button',{name:'以当前版本开始编辑'}).click();await page.getByRole('combobox',{name:'请求通知等级'}).selectOption('ALL');await page.getByRole('button',{name:'模拟账号版本变化'}).click();await expect(page.locator('.preference-form')).toHaveCount(0);await expect(page.getByRole('heading',{name:'当前版本 v1',exact:true})).toBeVisible();expect(methods.every(m=>m==='GET')).toBe(true)
})
