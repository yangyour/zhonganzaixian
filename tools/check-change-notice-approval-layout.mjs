// Requires own Vite dev server; synthetic UI layout only, no backend or external effects.
import {chromium} from 'playwright'
import {mkdir,writeFile} from 'node:fs/promises'
const base=process.env.APPROVAL_LAYOUT_ORIGIN||'http://127.0.0.1:5197'
const output=process.env.APPROVAL_LAYOUT_OUTPUT||'/tmp/d10-approval-layout'
await mkdir(output,{recursive:true})
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE||'/usr/bin/chromium',chromiumSandbox:true})
const evidence={scope:'SYNTHETIC_COMPONENT_LAYOUT_ONLY',real_api:'NOT_RUN',viewports:[],browser:browser.version()}
try{
 for(const width of [390,1440]){
  const page=await browser.newPage({viewport:{width,height:960}}),errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.goto(base+'/qa/change-notice-approval.fixture.html')
  await page.getByRole('button',{name:'读取所选草稿修订的审批',exact:true}).click()
  await page.getByRole('button',{name:'按审批版本 v1 准备动作',exact:true}).click()
  await page.getByLabel('审批说明',{exact:true}).fill('合成审核说明：保留原修订与版本，不代表生效。')
  await page.getByText('核对固定修订内容、目标与计划窗口',{exact:true}).click()
  await page.screenshot({path:`${output}/approval-${width}.png`,fullPage:true})
  const dimensions=await page.evaluate(()=>({client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth,targets:document.querySelectorAll('.approval-content li').length,buttons:[...document.querySelectorAll('.approval-panel button')].map(e=>({height:e.getBoundingClientRect().height,width:e.getBoundingClientRect().width}))}))
  if(errors.length||dimensions.scroll>dimensions.client||dimensions.buttons.some(b=>b.height<44)||dimensions.targets!==2)throw Error(JSON.stringify({width,errors,dimensions}))
  evidence.viewports.push({width,horizontal_overflow:false,minimum_button_height:44,screenshot:`approval-${width}.png`})
  await page.close()
 }
 await writeFile(`${output}/result.json`,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2))
}finally{await browser.close()}
