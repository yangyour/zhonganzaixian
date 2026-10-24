import {test,expect} from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import {sample} from '../../tests/serviceObservations.fixture'
for(const width of [1024,1440])test(`service observations unknown, drilldown and revocation at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});const failures:string[]=[];page.on('pageerror',e=>failures.push(e.message));
 await page.route('**/api/v1/dashboard/service-observations',route=>{expect(route.request().method()).toBe('GET');return route.fulfill({json:{data:sample(),trace_id:'SYNTHETIC'}})})
 await page.goto('/qa/service-observations.fixture.html');await page.getByRole('button',{name:'刷新',exact:true}).click();
 const health=page.getByRole('region',{name:'服务健康'});await expect(health).toContainText('本月可用率 未知');await expect(health).not.toContainText('100%');
 await health.locator('summary').click();await expect(health.getByRole('link',{name:'设备',exact:true})).toHaveAttribute('href',/asset_id=/);
 await page.getByRole('region',{name:'故障时间线'}).locator('summary').click();await expect(page.locator('.unknown-track .unknown')).toBeVisible();
 expect((await new AxeBuilder({page}).include('main').withTags(['wcag2a','wcag2aa']).analyze()).violations).toEqual([]);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)).toBe(false);
 await page.getByRole('button',{name:'模拟撤权'}).click();await expect(page.locator('.service-row')).toHaveCount(0);await expect(page.locator('.service-track')).toHaveCount(0);expect(failures).toEqual([])
})
test('stopping a pending read keeps late data out and allows retry',async({page})=>{
 let finish!:()=>void;let first=true;
 await page.route('**/api/v1/dashboard/service-observations',async route=>{if(first){first=false;await new Promise<void>(resolve=>finish=resolve)}await route.fulfill({json:{data:sample(),trace_id:'SYNTHETIC'}}).catch(()=>{})});
 await page.goto('/qa/service-observations.fixture.html');await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('button',{name:'停止等待'}).click();finish();await expect(page.locator('.service-row')).toHaveCount(0);await page.getByRole('button',{name:'刷新',exact:true}).click();await expect(page.locator('.service-row')).toHaveCount(1)
})
