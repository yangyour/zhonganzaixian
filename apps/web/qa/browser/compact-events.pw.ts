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
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 await list.focus();await page.keyboard.press('End');await expect(list).toBeFocused()
 await page.getByRole('button',{name:'刷新事件',exact:true}).click();await expect(list).toBeVisible();expect(writes).toEqual([])
 expect((await new AxeBuilder({page}).include('.monitoring-events-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/compact-events-${width}.png`,fullPage:true})
})
