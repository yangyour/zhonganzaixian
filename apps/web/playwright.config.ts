import {defineConfig} from '@playwright/test'
export default defineConfig({
 testDir:'./qa/browser',testMatch:'*.pw.ts',fullyParallel:false,workers:1,retries:0,
 outputDir:'../../tests/artifacts/frontend-browser',
 reporter:[['list'],['json',{outputFile:'../../tests/artifacts/frontend-browser-results.json'}]],
 use:{baseURL:'http://127.0.0.1:28130',browserName:'chromium',headless:true,
  launchOptions:process.env.MOP_CHROMIUM_EXECUTABLE?{executablePath:process.env.MOP_CHROMIUM_EXECUTABLE}:undefined,
  screenshot:'only-on-failure',trace:'retain-on-failure'},
 webServer:{command:'npm run dev -- --port 28130',url:'http://127.0.0.1:28130/qa/cmdb-catalog.fixture.html',reuseExistingServer:false,timeout:30000},
})
