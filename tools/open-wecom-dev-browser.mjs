import { createRequire } from 'node:module'
import { existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Separate browser profile and per-process DNS mapping; no system hosts or public DNS changes.
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
const require=createRequire(resolve(root,'package.json'))
const {chromium}=require('playwright')
const executablePath=[
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
].find(existsSync)
if(!executablePath)throw new Error('No installed Edge or Chrome found')
const profile=resolve(root,'.local/wecom-dev-browser')
mkdirSync(profile,{recursive:true})
const context=await chromium.launchPersistentContext(profile,{
  executablePath,headless:false,viewport:null,
  args:['--host-resolver-rules=MAP mop.all-online.com.cn 127.0.0.1','--proxy-server=direct://','--proxy-bypass-list=*','--disable-features=HttpsUpgrades'],
})
await context.pages()[0].goto('http://mop.all-online.com.cn/login')
console.log('Local MOP browser opened; the domain resolves to this machine in this browser only.')
await new Promise(resolve=>context.on('close',resolve))
