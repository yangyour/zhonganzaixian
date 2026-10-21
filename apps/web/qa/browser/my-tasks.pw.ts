import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {workflow,workflowPage,id,workflowContext} from '../../tests/dashboardWorkflow.fixture'
import type {WorkflowState} from '../../src/utils/ticketWorkflow'
for(const width of [320,390,768,1440])test(`own tasks fixed assignee, keyboard, reflow and axe at ${width}px`,async({page})=>{
 const methods:string[]=[]
 await page.setViewportSize({width,height:900})
 await page.route('**/api/v1/ticket-workflows?*',route=>{const url=new URL(route.request().url());expect(url.searchParams.get('assignee')).toBe(workflowContext.actorId);methods.push(route.request().method());const state=url.searchParams.get('state') as WorkflowState;return route.fulfill({json:{data:workflowPage([workflow(20,state)]),trace_id:'SYNTHETIC'}})})
 await page.goto('/qa/my-tasks.fixture.html')
 await expect(page.getByRole('heading',{name:'我的待办',exact:true})).toBeVisible()
 await page.getByRole('combobox',{name:'我的工单状态'}).selectOption('IN_PROGRESS')
 await expect(page.getByRole('status')).toContainText('处理中 · 第 1 页')
 const scroll=page.getByRole('region',{name:'我负责的工单列表，可横向滚动'});await scroll.focus();await page.keyboard.press('End');await expect(scroll).toBeFocused()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false)
 expect((await new AxeBuilder({page}).include('.my-tasks-page').withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa']).analyze()).violations).toEqual([])
 await page.screenshot({path:`../../tests/artifacts/my-tasks-${width}.png`,fullPage:true})
 const link=page.getByRole('link',{name:'查看我的工单SYN-20'});await link.focus();await page.keyboard.press('Enter');await expect(page.getByRole('status')).toHaveText('合成工单详情目标：'+id(20));expect(methods.every(m=>m==='GET')).toBe(true)
})
test('cancelled own task read restores refresh focus and rejects late rows',async({page})=>{
 let release!:()=>void;const gate=new Promise<void>(r=>release=r)
 await page.route('**/api/v1/ticket-workflows?*',async route=>{await gate;await route.fulfill({json:{data:workflowPage([workflow(20,'ACCEPTED')]),trace_id:'SYNTHETIC'}}).catch(()=>{})})
 await page.goto('/qa/my-tasks.fixture.html');await page.getByRole('button',{name:'取消读取我的工单'}).click();release()
 await expect(page.getByRole('button',{name:'刷新我的工单',exact:true})).toBeFocused();await expect(page.getByRole('status')).toContainText('读取已取消');await expect(page.getByRole('table')).toHaveCount(0)
})
test('own task facts disappear when current permissions are revoked',async({page})=>{
 await page.route('**/api/v1/ticket-workflows?*',route=>route.fulfill({json:{data:workflowPage([workflow(20,'ACCEPTED')]),trace_id:'SYNTHETIC'}}))
 await page.goto('/qa/my-tasks.fixture.html');await expect(page.getByRole('table')).toBeVisible();await page.getByRole('button',{name:'模拟撤销权限'}).click();await expect(page.getByRole('table')).toHaveCount(0);await expect(page.getByRole('link',{name:'查看我的工单SYN-20'})).toHaveCount(0);await expect(page.getByRole('status')).toContainText('未读取任务数据')
})
