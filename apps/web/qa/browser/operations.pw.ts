import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const rawEvents=JSON.parse(readFileSync(new URL('../../../../fixtures/monitoring-event-center-controller.json',import.meta.url),'utf8')).workspace
// Synthetic controller DTO fixture uses ascending IDs; the real repository contract is descending.
rawEvents.data.events.sort((a:{id:string},b:{id:string})=>a.id>b.id?-1:a.id<b.id?1:0)
const rawReport=JSON.parse(readFileSync(new URL('../../tests/fixtures/quarterly-report/selected.json',import.meta.url),'utf8'))
const {frozen_document:_document,document_integrity:_integrity,...meta}=rawReport.data
const reportPage={data:{tenant_id:meta.tenant_id,items:[{...meta,projection:'METADATA_ONLY'}],has_more:false,next_before_recorded_at:null,next_before_id:null,page_size_limit:20,scope:'CURRENT_TENANT_PADMIN',source:'postgresql'},trace_id:'SYNTHETIC'}
async function reports(page:import('@playwright/test').Page){
 await page.route('**/api/v1/inspection-quarterly-reports?*',r=>r.fulfill({json:reportPage}))
 await page.route('**/api/v1/inspection-quarterly-reports/*',r=>r.fulfill({json:rawReport}))
 await page.goto('/qa/operations.fixture.html?view=reports')
 await page.getByRole('button',{name:'读取冻结报告',exact:true}).click()
 await expect(page.getByRole('button',{name:'下载 HTML 草稿预览'})).toBeVisible()
}
for(const width of [320,390,768,1440]){
 test(`recent events semantic states, reflow and axe at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});await page.route('**/api/v1/events?*',r=>r.fulfill({json:rawEvents}))
  await page.goto('/qa/operations.fixture.html')
  await expect(page.getByText('列表数量不是当前问题总数',{exact:false})).toBeVisible()
  await expect(page.locator('.events>li')).toHaveCount(rawEvents.data.events.length)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
  expect((await new AxeBuilder({page}).include('.dashboard-events').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({path:`../../tests/artifacts/recent-events-${width}.png`,fullPage:true})
  const link=page.locator('.events h3 a').first();await link.focus();await page.keyboard.press('Enter')
  await expect(page.getByRole('status')).toHaveText('合成详情目标：'+rawEvents.data.events[0].id)
 })
 test(`report draft keyboard, reflow and axe at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});await reports(page)
  const scroll=page.getByRole('region',{name:'季度报告列表，可横向滚动'});await scroll.focus();await page.keyboard.press('End');await expect(scroll).toBeFocused()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
  expect((await new AxeBuilder({page}).include('.quarterly-report-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({path:`../../tests/artifacts/quarterly-report-${width}.png`,fullPage:true})
 })
}
test('draft download rereads server and delivers inert HTML without publication',async({page})=>{
 await reports(page);let reads=0
 await page.route('**/api/v1/inspection-quarterly-reports/*',r=>{reads++;return r.fulfill({json:rawReport})})
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载 HTML 草稿预览'}).click();const file=await download
 expect(reads).toBe(1);expect(file.suggestedFilename()).toBe(`quarterly-draft-${meta.id}.html`)
 const path=await file.path();expect(path).not.toBeNull();const html=readFileSync(path!,'utf8')
 expect(html).toContain(meta.document_sha256);expect(html).toContain('DRAFT 草稿预览');expect(html).not.toContain('<script');expect(html).toContain('合成😀原文')
 await expect(page.getByRole('status').filter({hasText:'已发起 HTML 草稿下载'})).toBeVisible()
})
test('cancelled preview returns focus and cannot download late response',async({page})=>{
 await reports(page);let release!:()=>void;const gate=new Promise<void>(r=>release=r);const downloads:string[]=[];page.on('download',d=>downloads.push(d.suggestedFilename()))
 await page.route('**/api/v1/inspection-quarterly-reports/*',async r=>{await gate;await r.fulfill({json:rawReport}).catch(()=>{})})
 await page.getByRole('button',{name:'下载 HTML 草稿预览'}).click();await page.getByRole('button',{name:'取消预览下载'}).click();release()
 await expect(page.getByRole('button',{name:'下载 HTML 草稿预览'})).toBeFocused();await expect(page.getByRole('status').filter({hasText:'已取消'})).toBeVisible();expect(downloads).toEqual([])
})
