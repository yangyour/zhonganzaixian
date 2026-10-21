// @vitest-environment jsdom
import {afterEach,expect,it,vi} from 'vitest'
import {createApp,nextTick,type App} from 'vue'
import Papa from 'papaparse'
import fixture from '../../../fixtures/cmdb-type-catalog-controller.json'
import Panel from '../src/components/CmdbImportPreflightPanel.vue'
import {readCmdbTypeCatalog} from '../src/utils/cmdbTypeCatalog'
import {CMDB_IMPORT_COLUMNS} from '../src/utils/cmdbImportPreflight'
let app:App|undefined,host:HTMLElement,state:any
const catalog=readCmdbTypeCatalog(fixture.data,fixture.data.tenant_id)
const csv=()=>{const row=Array<string>(33).fill('');row[0]='合成设备';row[1]='SW';row[2]='192.0.2.1';row[16]='fw_adc';return Papa.unparse([CMDB_IMPORT_COLUMNS.map(c=>c.label),row])}
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.append(host);app=createApp(Panel,{catalog});app.component('el-button',{template:'<button type="button"><slot /></button>'});state=(app.mount(host).$ as any).setupState}
function file(text:string,name='synthetic.csv',read=()=>Promise.resolve(new TextEncoder().encode(text).buffer)){return {name,size:new TextEncoder().encode(text).length,arrayBuffer:read}}
async function choose(value:unknown){const input=host.querySelector('input')!;Object.defineProperty(input,'files',{value:[value],configurable:true});input.dispatchEvent(new Event('change',{bubbles:true}));await settle()}
afterEach(()=>{app?.unmount();app=undefined;host?.remove();vi.restoreAllMocks()})
it('parses a selected file locally and moves focus to the summary without sending a request',async()=>{
 const network=vi.spyOn(globalThis,'fetch');mount();await choose(file(csv()))
 expect(state.result.records).toBe(1);expect(host.textContent).toContain('尚不能执行导入');expect(network).not.toHaveBeenCalled()
 expect(document.activeElement?.classList.contains('preflight-summary')).toBe(true)
 expect(host.textContent).toContain('子类型与主类型不匹配')
 const locate=Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='查看原值')!;locate.click();await settle()
 expect(document.activeElement?.getAttribute('aria-label')).toBe('第 2 条记录原值');expect(host.textContent).toContain('合成设备')
})
it('cancellation ignores a late file read and clears selected file data',async()=>{
 mount();let resolve!:(v:ArrayBuffer)=>void
 await choose(file(csv(),'slow.csv',()=>new Promise(r=>{resolve=r})));expect(state.busy).toBe(true)
 state.clear();resolve(new TextEncoder().encode(csv()).buffer);await settle();expect(state.result).toBeNull();expect(state.filename).toBe('')
})
it('unsupported files and invalid encoding show recoverable errors',async()=>{
 mount();await choose(file(csv(),'file.xlsx'));expect(host.querySelector('[role="alert"]')?.textContent).toContain('UTF-8 CSV')
 await choose(file('','bad.csv',()=>Promise.resolve(new Uint8Array([255]).buffer)));expect(state.error).toContain('有效 UTF-8')
 await choose(file(csv()));expect(state.error).toBe('');expect(state.result).not.toBeNull()
})
it('filters reset pagination and original content is text, not HTML',async()=>{
 mount();await choose(file(csv().replace('合成设备','<img src=x onerror=alert(1)>')))
 state.page=4;state.filter='ERROR';await settle();expect(state.page).toBe(0)
 await state.locate(2);expect(host.querySelector('img')).toBeNull();expect(host.textContent).toContain('<img src=x onerror=alert(1)>')
})
it('unmount invalidates an in-flight read',async()=>{
 mount();let resolve!:(v:ArrayBuffer)=>void;await choose(file(csv(),'slow.csv',()=>new Promise(r=>{resolve=r})))
 app!.unmount();app=undefined;resolve(new TextEncoder().encode(csv()).buffer);await settle();expect(state.result).toBeNull()
})
it('clearing a completed result returns keyboard focus to file selection',async()=>{
 mount();await choose(file(csv()));Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='清除本地预检')!.click();await settle()
 expect(state.result).toBeNull();expect(document.activeElement).toBe(host.querySelector('input'))
})
