import {test,expect,type Page} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const fixture=JSON.parse(readFileSync(new URL('../../../../fixtures/import-source-list-http-synthetic-20261021.json',import.meta.url),'utf8'))
const url='/qa/import-sources.fixture.html',endpoint='**/api/v1/assets/import-sources?*'
async function policy(page:Page){await page.route('**/api/v1/assets/import-policy',route=>route.fulfill({json:{data:fixture.policy,trace_id:'synthetic'}}))}
for(const width of [320,390,768,1440])test(`source history keyboard, reflow, reopen and axe at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900});await policy(page);let posts=0;page.on('request',r=>{if(r.method()==='POST')posts++})
 await page.route(endpoint,route=>route.fulfill({json:{data:new URL(route.request().url()).searchParams.has('cursor')?fixture.second:fixture.first,trace_id:'synthetic'}}))
 await page.route('**/api/v1/assets/import-sources/'+fixture.source.id,route=>route.fulfill({json:{data:fixture.source,trace_id:'synthetic'}}))
 await page.goto(url);const load=page.getByRole('button',{name:'查看我的暂存来源'});await load.focus();await page.keyboard.press('Enter');await expect(page.getByRole('status').filter({hasText:'第 1 页'})).toBeFocused()
 await page.getByRole('button',{name:'下一页来源'}).click();await expect(page.getByRole('status').filter({hasText:'第 2 页'})).toBeFocused();await expect(page.locator('tbody tr')).toHaveCount(3)
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.owned-import-sources').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/import-sources-${width}.png`,fullPage:true})
 await page.getByRole('button',{name:new RegExp('来源 '+fixture.source.id+'$')}).click();await expect(page.getByRole('status').filter({hasText:'已重新打开来源'})).toBeVisible();expect(posts).toBe(0)
})
test('failed next history page retries original cursor without stale rows',async({page})=>{
 await policy(page);let failed=false;const cursors:string[]=[];await page.route(endpoint,route=>{const cursor=new URL(route.request().url()).searchParams.get('cursor');if(cursor)cursors.push(cursor);if(cursor&&!failed){failed=true;return route.fulfill({status:503,json:{error:{code:'UPSTREAM_UNAVAILABLE',message:'synthetic'},trace_id:'synthetic'}})}return route.fulfill({json:{data:cursor?fixture.second:fixture.first,trace_id:'synthetic'}})})
 await page.goto(url);await page.getByRole('button',{name:'查看我的暂存来源'}).click();await page.getByRole('button',{name:'下一页来源'}).click();await expect(page.getByRole('alert')).toBeVisible();await expect(page.locator('tbody tr')).toHaveCount(0)
 await page.getByRole('button',{name:'重新读取本页来源'}).click();await expect(page.locator('tbody tr')).toHaveCount(3);expect(cursors).toEqual([fixture.first.next_cursor,fixture.first.next_cursor])
})
test('cancel and revoke prevent late source history from reappearing',async({page})=>{
 await policy(page);await page.route(endpoint,async route=>{await new Promise(r=>setTimeout(r,400));await route.fulfill({json:{data:fixture.first,trace_id:'synthetic'}}).catch(()=>{})})
 await page.goto(url);const load=page.getByRole('button',{name:'查看我的暂存来源'});await load.click();await page.getByRole('button',{name:'取消读取来源列表'}).click();await expect(load).toBeFocused()
 await load.click();await page.getByRole('button',{name:'模拟撤销权限'}).click();await page.waitForTimeout(500);await expect(page.locator('.owned-import-sources')).toHaveCount(0);await expect(page.locator('tbody tr')).toHaveCount(0)
})
