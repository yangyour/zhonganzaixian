import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {workflow,workflowPage,id} from '../../tests/dashboardWorkflow.fixture'
import type {WorkflowState} from '../../src/utils/ticketWorkflow'
for(const width of [320,390,768,1440])test(`workflow state, keyboard navigation, reflow and axe at ${width}px`,async({page})=>{
 const methods:string[]=[]
 await page.setViewportSize({width,height:900})
 await page.route('**/api/v1/ticket-workflows?*',route=>{methods.push(route.request().method());const state=new URL(route.request().url()).searchParams.get('state') as WorkflowState;return route.fulfill({json:{data:workflowPage([workflow(20,state)]),trace_id:'SYNTHETIC'}})})
 await page.goto('/qa/dashboard-workflows.fixture.html')
 await expect(page.getByRole('table')).toBeVisible()
 await page.getByRole('combobox',{name:'筛选工作流状态'}).selectOption('ACCEPTED')
 await expect(page.locator('caption')).toHaveText('已接单的已激活工单工作流')
 await expect(page.getByRole('status')).toContainText('已接单 · 第 1 页')
 const scroll=page.getByRole('region',{name:'已激活工单工作流，可横向滚动'});await scroll.focus();await page.keyboard.press('End');await expect(scroll).toBeFocused()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.dashboard-workflows').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/dashboard-workflows-${width}.png`,fullPage:true})
 const link=page.getByRole('link',{name:'查看工单处理SYN-20'});await link.focus();await page.keyboard.press('Enter');await expect(page.getByRole('status')).toHaveText('合成工单详情目标：'+id(20));expect(methods.every(m=>m==='GET')).toBe(true)
})
test('cancelled workflow read returns focus and never restores a late table',async({page})=>{
 let release!:()=>void;const gate=new Promise<void>(r=>release=r)
 await page.route('**/api/v1/ticket-workflows?*',async route=>{await gate;await route.fulfill({json:{data:workflowPage(),trace_id:'SYNTHETIC'}}).catch(()=>{})})
 await page.goto('/qa/dashboard-workflows.fixture.html');await page.getByRole('button',{name:'取消读取工单'}).click();release()
 await expect(page.getByRole('button',{name:'刷新工单',exact:true})).toBeFocused();await expect(page.getByRole('status')).toContainText('读取已取消');await expect(page.getByRole('table')).toHaveCount(0)
})
test('revoking permissions removes workflow facts and navigation targets',async({page})=>{
 await page.route('**/api/v1/ticket-workflows?*',route=>route.fulfill({json:{data:workflowPage(),trace_id:'SYNTHETIC'}}))
 await page.goto('/qa/dashboard-workflows.fixture.html');await expect(page.getByRole('table')).toBeVisible();await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.locator('.dashboard-workflows')).toHaveCount(0);await expect(page.getByRole('link',{name:'查看工单处理SYN-20'})).toHaveCount(0)
})
