import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {followups,query,id,tenant} from '../../tests/reportFollowups.fixture'
for(const width of [1440,1024])test(`cross-report worklist scope recovery and opening at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1100});const errors:string[]=[],requests:string[]=[];let failed=false
 page.on('pageerror',e=>errors.push(e.message))
 await page.route('**/api/v1/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname.replace('/api/v1','');expect(route.request().method()).toBe('GET')
  let data:unknown
  if(path==='/report-publications/followups'){
   requests.push(url.search)
   const before=url.searchParams.get('before_id');if(before&&!failed){failed=true;return route.abort('failed')}
   data=followups({...query,filter:url.searchParams.get('filter') as any,before:before?{document_id:before,published_at:url.searchParams.get('before')!}:null})
  }else if(path==='/report-publications')data={items:[],next:null,external_dispatch_enabled:false}
  else if(path.endsWith('/readership'))data={tenant_id:tenant,document_id:path.split('/')[2],document_sha256:'a'.repeat(64),state_version:5,published_at:'2026-10-02T00:00:00Z',observed_at:'2026-10-24T00:00:00Z',filter:url.searchParams.get('filter')??'ALL',total_recipients:2,read_count:1,unread_count:1,followup_due_count:1,matching_count:2,items:[{customer_id:id(1),display_name:null,first_read_at:'2026-10-03T00:00:00Z',followup_due:false},{customer_id:id(2),display_name:null,first_read_at:null,followup_due:true}],next_after:null}
  else data={label:'SYNTHETIC selected report',source_id:id(900),source_document_sha256:'a'.repeat(64),customer_acceptance_confirmed:false,external_dispatch_enabled:false,state:{version:5,phase:'PUBLISHED',reason:null,recipients:[id(1),id(2)],html:{content_sha256:'c'.repeat(64)},pdf:{content_sha256:'d'.repeat(64)},document:{id:path.split('/')[2],tenant_id:tenant,family_id:id(1000),parent_id:null,revision:1,kind:'QUARTERLY',document_sha256:'a'.repeat(64),captured_at:'2026-10-01T00:00:00Z',incomplete_reasons:[]}}}
  await route.fulfill({json:{data,trace_id:'SYNTHETIC_FOLLOWUP_ONLY'}})
 })
 await page.goto('/qa/report-revision.fixture.html');const panel=page.locator('details[aria-label="跨报告未读跟进"]')
 await panel.locator('summary').click();await panel.getByLabel('冻结时间起（UTC）',{exact:true}).fill('2026-10-01');await panel.getByLabel('冻结时间止（UTC，不含）',{exact:true}).fill('2026-11-01')
 expect(requests).toHaveLength(0);await panel.getByRole('button',{name:'查询跟进列表',exact:true}).click();await expect(panel).toContainText('匹配 22 份修订')
 await panel.getByRole('button',{name:'跟进列表下一页'}).click();await panel.getByRole('button',{name:'重试原跟进页'}).click();await expect(panel.locator('tbody tr')).toHaveCount(2);expect(requests.at(-1)).toBe(requests.at(-2))
 await panel.getByRole('button',{name:'跟进列表第一页'}).click();await expect(panel.locator('tbody tr')).toHaveCount(20)
 await panel.getByRole('region',{name:'跨报告跟进表格'}).focus();await page.keyboard.press('ArrowDown');await expect.poll(()=>panel.locator('.followup-table').evaluate(e=>e.scrollTop)).toBeGreaterThan(0)
 expect((await new AxeBuilder({page}).include('details[aria-label="跨报告未读跟进"]').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 await page.screenshot({path:`../../tests/artifacts/report-followups-${width}.png`,fullPage:true})
 await panel.getByRole('button',{name:'查看阅读情况：Synthetic report 22',exact:true}).click();await expect(page.getByRole('heading',{name:'SYNTHETIC selected report'})).toBeFocused()
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(panel).toHaveCount(0);expect(errors).toEqual([])
})
