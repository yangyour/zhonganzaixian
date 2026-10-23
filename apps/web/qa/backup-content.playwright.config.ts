import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir:'./browser',testMatch:'backup-version-content.pw.ts',workers:1,retries:0,
  outputDir:'../../../tests/artifacts/backup-content-browser',
  reporter:[['list'],['json',{outputFile:'../../../tests/artifacts/backup-content-browser-results.json'}]],
  use:{baseURL:'http://127.0.0.1:15214',browserName:'chromium',headless:true,acceptDownloads:true,
    launchOptions:process.env.MOP_CHROMIUM_EXECUTABLE?{executablePath:process.env.MOP_CHROMIUM_EXECUTABLE}:undefined,
    screenshot:'only-on-failure',trace:'retain-on-failure'},
  webServer:{command:'npm run dev -- --host 127.0.0.1 --port 15214 --strictPort',url:'http://127.0.0.1:15214/qa/backup-version-content.fixture.html',reuseExistingServer:false,timeout:60000},
})
