import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {reachabilitySample} from '../../tests/assetReachability.fixture'
for(const width of [1024,1440])test(`asset reachability full pagination, filtering and revocation at ${width}`,async({page})=>{
 const data=reachabilitySample();data.items=Array.from({length:45},(_,i)=>({...data.items[0]!,id:`30000000-0000-4000-8000-${String(i).padStart(12,'0')}`,name:'资产'+i}));Object.assign(data,{total:45,unknown:45});
 await page.route('**/api/v1/dashboard/asset-reachability',route=>{expect(route.request().method()).toBe('GET');return route.fulfill({json:{data,trace_id:'SYNTHETIC'}})});
 await page.setViewportSize({width,height:1000});await page.goto('/qa/asset-reachability.fixture.html');await expect(page.locator('tbody tr')).toHaveCount(20);await page.getByRole('button',{name:'下一页'}).click();await page.getByRole('button',{name:'下一页'}).click();await expect(page.locator('tbody tr')).toHaveCount(5);await page.getByLabel('状态筛选').selectOption('NORMAL');await expect(page.getByRole('navigation')).toContainText('第 1 / 1 页');await expect(page.locator('tbody')).toContainText('当前筛选没有资产记录');
 expect((await new AxeBuilder({page}).include('main').withTags(['wcag2a','wcag2aa']).analyze()).violations).toEqual([]);await page.getByRole('button',{name:'模拟撤权'}).click();await expect(page.locator('table')).toHaveCount(0)
})
