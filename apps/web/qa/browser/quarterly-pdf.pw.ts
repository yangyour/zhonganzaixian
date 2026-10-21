import {test,expect,type Page,type Route} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
import {createHash} from 'node:crypto'
const capture=JSON.parse(readFileSync(new URL('../../../../fixtures/quarterly-pdf-http-synthetic-20261021.json',import.meta.url),'utf8'))
const d=capture.detail,bytes=Buffer.from(capture.pdf_base64,'base64'),url='/qa/quarterly-pdf.fixture.html'
const pdfEndpoint='**/api/v1/inspection-quarterly-reports/'+d.id+'/preview.pdf'
async function setup(page:Page){
 const summary={...d,projection:'METADATA_ONLY'};delete summary.frozen_document;delete summary.document_integrity
 await page.route('**/api/v1/inspection-quarterly-reports?*',route=>route.fulfill({json:{data:{tenant_id:d.tenant_id,items:[summary],has_more:false,next_before_recorded_at:null,next_before_id:null,page_size_limit:20,scope:'CURRENT_TENANT_PADMIN',source:'postgresql'},trace_id:'synthetic'}}))
 await page.route('**/api/v1/inspection-quarterly-reports/'+d.id,route=>route.fulfill({json:{data:d,trace_id:'synthetic'}}))
}
async function open(page:Page){await page.goto(url);await page.getByRole('button',{name:'读取冻结报告',exact:true}).click();await expect(page.getByRole('button',{name:'下载 PDF 草稿预览',exact:true})).toBeEnabled()}
async function deliver(route:Route){await route.fulfill({status:200,headers:capture.headers,body:bytes})}
for(const width of [320,390,768,1440])test(`PDF keyboard download, reflow and axe at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900});await setup(page);let posts=0,pdfReads=0;page.on('request',r=>{if(r.method()==='POST')posts++})
 await page.route(pdfEndpoint,async route=>{pdfReads++;expect(route.request().headers()['if-match']).toBe('"'+d.document_sha256+'"');await deliver(route)})
 await open(page);const button=page.getByRole('button',{name:'下载 PDF 草稿预览',exact:true});await button.focus();const pending=page.waitForEvent('download');await page.keyboard.press('Enter');const download=await pending
 expect(download.suggestedFilename()).toBe('quarterly-draft-'+d.id+'.pdf');expect(await download.failure()).toBeNull();const file=await download.path();expect(file).not.toBeNull();expect(createHash('sha256').update(readFileSync(file!)).digest('hex')).toBe(new Headers(capture.headers).get('X-MOP-PDF-SHA256'))
 await expect(page.getByRole('status').filter({hasText:'已发起'})).toContainText('不代表已保存、批准或发布');expect(pdfReads).toBe(1);expect(posts).toBe(0)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.quarterly-report-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/quarterly-pdf-${width}.png`,fullPage:true})
})
test('PDF missing-font error keeps detail, never downloads and permits explicit retry',async({page})=>{
 await setup(page);let calls=0,downloads=0;page.on('download',()=>downloads++);await page.route(pdfEndpoint,async route=>{if(++calls===1)return route.fulfill({status:503,json:{error:{code:'REPORT_PDF_UNCONFIGURED',message:'合成字体未配置，可使用 HTML 预览'},trace_id:'synthetic'}});await deliver(route)})
 await open(page);const button=page.getByRole('button',{name:'下载 PDF 草稿预览',exact:true});await button.click();await expect(page.getByRole('alert').filter({hasText:'合成字体未配置'})).toBeVisible();await expect(button).toBeEnabled();expect(calls).toBe(1);expect(downloads).toBe(0)
 const pending=page.waitForEvent('download');await button.click();await pending;expect(calls).toBe(2)
})
test('PDF cancellation restores focus and permission revocation discards late bytes',async({page})=>{
 await setup(page);let downloads=0;page.on('download',()=>downloads++);await page.route(pdfEndpoint,async route=>{await new Promise(r=>setTimeout(r,500));await deliver(route).catch(()=>{})})
 await open(page);const button=page.getByRole('button',{name:'下载 PDF 草稿预览',exact:true});await button.click();await expect(button).toBeDisabled();await expect(page.getByRole('button',{name:'下载 HTML 草稿预览',exact:true})).toBeDisabled();await page.getByRole('button',{name:'取消预览下载'}).click();await expect(button).toBeFocused();await expect(page.getByRole('status').filter({hasText:'已取消'})).toBeVisible()
 await page.waitForTimeout(650);expect(downloads).toBe(0);await button.click();await page.getByRole('button',{name:'模拟撤销权限'}).click();await page.waitForTimeout(650);expect(downloads).toBe(0);await expect(button).toHaveCount(0)
})
test('PDF tampered bytes fail digest validation without a download',async({page})=>{
 await setup(page);let downloads=0;page.on('download',()=>downloads++);const corrupt=Buffer.from(bytes);corrupt[50]^=1;await page.route(pdfEndpoint,route=>route.fulfill({status:200,headers:capture.headers,body:corrupt}))
 await open(page);await page.getByRole('button',{name:'下载 PDF 草稿预览',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'摘要'})).toBeVisible();expect(downloads).toBe(0)
})
