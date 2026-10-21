import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {readFileSync} from 'node:fs'
const captured=JSON.parse(readFileSync(new URL('../../../../fixtures/observation-window-http-synthetic-20261021.json',import.meta.url),'utf8')).captures[0]
const response={data:captured.response,trace_id:'SYNTHETIC_ACCEPTANCE'}
async function open(page:import('@playwright/test').Page){await page.goto('/qa/observation-window.fixture.html');await expect(page.getByLabel('显式观察窗 JSON 输入')).toHaveValue('');await page.getByLabel('显式观察窗 JSON 输入').fill(JSON.stringify(captured.input))}
for(const width of [320,390,768,1440])test(`observation diagnostic keyboard, reflow and axe at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900});await page.route('**/api/v1/monitoring/observation-window-diagnostics',r=>r.fulfill({json:response}));await open(page)
 const submit=page.getByRole('button',{name:'诊断明确输入',exact:true});await submit.focus();await page.keyboard.press('Enter')
 await expect(page.getByRole('heading',{name:'候选观察跨度达到，但不是实际恢复确认'})).toBeVisible();await expect(page.getByText('均未执行，也未授权',{exact:true})).toBeVisible()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.observation-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/observation-window-${width}.png`,fullPage:true})
})
test('cancel diagnostic restores editor focus and rejects a late success',async({page})=>{let release!:()=>void;const pending=new Promise<void>(r=>release=r);await page.route('**/api/v1/monitoring/observation-window-diagnostics',async r=>{await pending;await r.fulfill({json:response}).catch(()=>{})});await open(page);await page.getByRole('button',{name:'诊断明确输入',exact:true}).click();await page.getByRole('button',{name:'取消诊断',exact:true}).click();release();await expect(page.getByLabel('显式观察窗 JSON 输入')).toBeFocused();await expect(page.getByLabel('显式观察窗 JSON 输入')).toHaveValue(JSON.stringify(captured.input));await expect(page.locator('.result')).toHaveCount(0)})
test('revocation clears input and ignores late diagnostic data',async({page})=>{let release!:()=>void;const pending=new Promise<void>(r=>release=r);await page.route('**/api/v1/monitoring/observation-window-diagnostics',async r=>{await pending;await r.fulfill({json:response}).catch(()=>{})});await open(page);await page.getByRole('button',{name:'诊断明确输入',exact:true}).click();await page.getByRole('button',{name:'模拟撤销权限'}).click();release();await expect(page.getByLabel('显式观察窗 JSON 输入')).toHaveCount(0);await expect(page.locator('.result')).toHaveCount(0)})
test('navigation requires explicit discard and cancellation retains input',async({page})=>{await open(page);await page.getByRole('link',{name:'返回阈值条件诊断'}).click();await page.getByRole('button',{name:'保留输入',exact:true}).click();await expect(page.getByLabel('显式观察窗 JSON 输入')).toHaveValue(JSON.stringify(captured.input));await page.getByRole('link',{name:'返回阈值条件诊断'}).click();await page.getByRole('button',{name:'清除并继续',exact:true}).click();await expect(page.getByText('阈值条件诊断合成目标',{exact:true})).toBeVisible()})
