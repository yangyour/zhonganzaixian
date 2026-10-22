import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const fixture=JSON.parse(readFileSync(new URL('../../../../fixtures/monitoring-event-center-controller.json',import.meta.url),'utf8')).workspace
for(const width of [320,390,768,1440])test(`compact event list reflows with keyboard scrolling at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900})
 const writes:string[]=[]
 await page.route('**/api/v1/events?*',r=>{if(r.request().method()!=='GET')writes.push(r.request().method());return r.fulfill({json:fixture})})
 await page.goto('/qa/compact-events.fixture.html')
 const list=page.getByRole('region',{name:'监控事件列表，可横向滚动'})
 await expect(list).toBeVisible();await expect(list.locator('tbody tr')).toHaveCount(fixture.data.events.length)
 await expect(page.locator('.workspace-grid,.kpi-list,.feature-summary')).toHaveCount(0)
 const heading=page.getByRole('heading',{level:1})
 await heading.evaluate(e=>{e.tabIndex=-1;e.focus()})
 expect(await heading.evaluate(e=>e.getBoundingClientRect().height)).toBe(1)
 await expect(page.locator('.event-list-toolbar').getByRole('button',{name:'刷新事件',exact:true})).toBeVisible()
 const review=page.locator('.event-review').first()
 const field=review.locator('textarea')
 await expect(field).toBeHidden()
 await review.locator('summary').focus();await page.keyboard.press('Enter')
 await expect(field).toBeVisible()
 await field.fill('人工复核合成原因，保留填写内容但不提交任何业务操作。')
 await expect(review.getByRole('button',{name:'确认解决',exact:true})).toBeEnabled()
 await review.locator('summary').click();await expect(field).toBeHidden()
 await review.locator('summary').click();await expect(field).toHaveValue('人工复核合成原因，保留填写内容但不提交任何业务操作。')
 await review.locator('summary').click()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 await list.focus();await page.keyboard.press('End');await expect(list).toBeFocused()
 await page.getByRole('button',{name:'刷新事件',exact:true}).click();await expect(list).toBeVisible();expect(writes).toEqual([])
 expect((await new AxeBuilder({page}).include('.monitoring-events-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/compact-events-${width}.png`,fullPage:true})
})

test('event refresh remains available after the initial read fails',async({page})=>{
 let reads=0
 await page.route('**/api/v1/events?*',r=>++reads===1?r.fulfill({status:503,json:{error:{code:'SYNTHETIC_UNAVAILABLE',message:'合成读取失败'},trace_id:'SYNTHETIC'}}):r.fulfill({json:fixture}))
 await page.goto('/qa/compact-events.fixture.html')
 await expect(page.locator('.alert-error')).toBeVisible()
 await page.getByRole('button',{name:'刷新事件',exact:true}).click()
 await expect(page.locator('.event-table-scroll tbody tr')).toHaveCount(fixture.data.events.length)
})

test('empty event results keep the compact toolbar and explicit empty state',async({page})=>{
 const empty=structuredClone(fixture);empty.data.events=[]
 await page.route('**/api/v1/events?*',r=>r.fulfill({json:empty}))
 await page.goto('/qa/compact-events.fixture.html')
 await expect(page.getByText('暂无监控事件',{exact:true})).toBeVisible()
 await expect(page.locator('.event-list-toolbar')).toContainText('本次 0 条')
 await expect(page.getByRole('button',{name:'刷新事件',exact:true})).toBeEnabled()
})
