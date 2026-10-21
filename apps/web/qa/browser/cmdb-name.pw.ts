import {test,expect} from '@playwright/test'
import Papa from 'papaparse'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const capture=JSON.parse(readFileSync(new URL('../../../../fixtures/cmdb-name-http-synthetic-20261021.json',import.meta.url),'utf8'))
const url='/qa/cmdb-name.fixture.html',endpoint='**/api/v1/assets/import-sources/*/cmdb-name-check'
for(const width of [320,390,768,1440])test(`CMDB exact name keyboard, reflow and axe at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900});let reads=0
 await page.route(endpoint,async route=>{reads++;expect(route.request().method()).toBe('GET');await route.fulfill({json:{data:capture.name_check,trace_id:'synthetic'}})})
 await page.goto(url);expect(reads).toBe(0)
 const button=page.getByRole('button',{name:'检查字典与库内重名'});await button.focus();await page.keyboard.press('Enter')
 const summary=page.getByRole('status').filter({hasText:'条记录'});await expect(summary).toBeFocused();await expect(summary).toContainText('1 条含错误')
 await expect(page.getByText('不预留名称。',{exact:false})).toBeVisible();await expect(page.getByText('尚不能入库：',{exact:false})).toBeVisible()
 const table=page.getByRole('region',{name:'服务端逐行校验问题，可滚动查看'});await table.focus();await expect(table).toBeFocused()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.source-preflight').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/cmdb-name-${width}.png`,fullPage:true});expect(reads).toBe(1)
})
test('CMDB name failure has retry without false success',async({page})=>{
 let reads=0;await page.route(endpoint,async route=>{reads++;await route.fulfill(reads===1?{status:503,json:{error:{code:'UPSTREAM_UNAVAILABLE',message:'合成读取失败'},trace_id:'synthetic'}}:{json:{data:capture.name_check,trace_id:'synthetic'}})})
 await page.goto(url);const button=page.getByRole('button',{name:'检查字典与库内重名'});await button.click();await expect(page.getByRole('alert')).toBeVisible();await page.getByText('查看排查信息',{exact:true}).click();await expect(page.getByText('合成读取失败',{exact:true})).toBeVisible();await expect(page.getByText('不预留名称。',{exact:false})).toHaveCount(0)
 await button.click();await expect(page.getByRole('status').filter({hasText:'条记录'})).toBeVisible();expect(reads).toBe(2)
})
test('CMDB name cancellation restores focus; revoked scope hides protected result',async({page})=>{
 await page.route(endpoint,async route=>{await new Promise(r=>setTimeout(r,400));await route.fulfill({json:{data:capture.name_check,trace_id:'synthetic'}}).catch(()=>{})})
 await page.goto(url);const button=page.getByRole('button',{name:'检查字典与库内重名'});await button.click();await page.getByRole('button',{name:'取消读取校验'}).click();await expect(button).toBeFocused()
 await button.click();await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.getByRole('region',{name:'服务端 CMDB 来源校验'})).toHaveCount(0)
 await page.waitForTimeout(500);await expect(page.getByText('条记录，',{exact:false})).toHaveCount(0)
})
test('source issue CSV binds current snapshot without another network check',async({page})=>{
 let reads=0;const writes:string[]=[];page.on('request',r=>{if(!['GET','HEAD'].includes(r.method()))writes.push(r.url())});await page.route(endpoint,route=>{reads++;return route.fulfill({json:{data:capture.name_check,trace_id:'synthetic'}})})
 await page.goto(url);await page.getByRole('button',{name:'检查字典与库内重名'}).click();const button=page.getByRole('button',{name:'导出全部服务端问题 CSV'});await expect(button).toBeVisible();const pending=page.waitForEvent('download');await button.click();const download=await pending;expect(await download.failure()).toBeNull();expect(download.suggestedFilename()).toBe('cmdb-source-findings.csv');const path=await download.path();expect(path).not.toBeNull()
 const parsed=Papa.parse<Record<string,string>>(readFileSync(path!,'utf8'),{header:true,skipEmptyLines:true});expect(parsed.errors).toEqual([]);expect(parsed.data).toHaveLength(capture.name_check.findings.length+1);expect(parsed.data[0]!.source_id).toBe(capture.name_check.source_id);expect(parsed.data[0]!.parse_hash).toBe(capture.name_check.parse_hash);expect(parsed.data[0]!.database_checks).toBe('EXACT_ASSET_NAMES_ONLY');expect(parsed.data[0]!.can_import).toBe('false');expect(reads).toBe(1);expect(writes).toEqual([])
 await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(button).toHaveCount(0)
})
