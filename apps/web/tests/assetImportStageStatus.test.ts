// @vitest-environment jsdom
// Isolated UI stage state, not server acceptance.
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import {createApp,computed,ref,shallowRef,nextTick,type App} from 'vue'
import {createMemoryHistory,createRouter} from 'vue-router'
const holder=vi.hoisted(()=>({workspace:null as any}))
vi.mock('../src/utils/assetImportWorkspace',()=>({createAssetImportWorkspace:()=>holder.workspace}))
vi.mock('../src/components/AppTable.vue',()=>({default:{template:'<div />'}}))
import Workspace from '../src/components/AssetImportWorkspace.vue'
import {session} from '../src/stores/session'
import capture from '../../../fixtures/cmdb-name-http-synthetic-20261021.json'
let app:App|undefined,host:HTMLElement
async function settle(){for(let i=0;i<5;i++){await Promise.resolve();await nextTick()}}
beforeEach(()=>{const source=shallowRef<any>({...structuredClone(capture.source),storage_status:'PENDING',parse_status:'NOT_REQUESTED',parsed_artifact:null,mapping:null});const artifact=computed(()=>source.value?.parsed_artifact);holder.workspace={source,artifact,file:ref(null),format:ref('CSV'),operation:ref(null),policy:ref(null),policyLoading:ref(false),policyError:ref(null),permitted:ref(true),busy:ref(null),error:ref(null),uploadError:ref(null),parseError:ref(null),mappingError:ref(null),fields:ref({}),selectionMessage:ref(''),selection:ref({headerRow:1}),mappingDraft:ref(null),uploadAttempt:ref(null),parseAttempt:ref(null),mappingAttempt:ref(null),hasUnresolved:ref(false),canReset:ref(true),canResume:ref(false),resumeError:ref(null),resume:vi.fn(),cancelResume:vi.fn(),isParsed:computed(()=>source.value.parse_status==='PARSED'&&artifact.value?.state==='PARSED'),selectionLocked:ref(false),mappingLocked:ref(false),loadPolicy:vi.fn(),dispose:vi.fn()};session.value=null})
afterEach(()=>{app?.unmount();host?.remove();vi.restoreAllMocks()})
async function mount(){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Workspace}]});await router.push('/');host=document.createElement('div');document.body.append(host);app=createApp(Workspace,{modelValue:true});const stub={template:'<div><slot /></div>'};for(const name of ['el-drawer','el-steps','el-step','el-skeleton','el-empty','el-form','el-form-item','el-select','el-option'])app.component(name,stub);app.component('el-button',{template:'<button><slot /></button>'});app.component('el-alert',{props:['title'],template:'<p>{{title}}<slot /></p>'});app.use(router).mount(host);await settle()}
it('pending upload does not claim archiving parsing or mapping completed',async()=>{await mount();const status=host.querySelector('[aria-label="当前来源实际完成情况"]')!;expect(status.textContent).toContain('归档：处理中');expect(status.textContent).toContain('解析：尚未完成');expect(status.textContent).toContain('映射：尚未保存');expect(host.textContent).not.toContain('当前已完成源文件归档');expect(host.textContent).toContain('仍需完成引用与地址策略、配额及事务入库链路')})
it('parsed source still reports unsaved mapping and no formal import',async()=>{holder.workspace.source.value=structuredClone(capture.source);await mount();const status=host.querySelector('[aria-label="当前来源实际完成情况"]')!;expect(status.textContent).toContain('归档：已完成校验');expect(status.textContent).toContain('解析：已解析');expect(status.textContent).toContain('映射：尚未保存');expect(host.textContent).toContain('不执行正式资产入库')})
it('failed parsing uses failure text rather than success',async()=>{holder.workspace.source.value={...holder.workspace.source.value,storage_status:'READY',parse_status:'FAILED'};await mount();const status=host.querySelector('[aria-label="当前来源实际完成情况"]')!;expect(status.textContent).toContain('归档：已完成校验');expect(status.textContent).toContain('解析：失败');expect(status.textContent).toContain('映射：尚未保存')})

it('failed storage does not imply parsed or mapped data',async()=>{holder.workspace.source.value={...holder.workspace.source.value,storage_status:'FAILED'};await mount();const status=host.querySelector('[aria-label="当前来源实际完成情况"]')!;expect(status.textContent).toContain('归档：失败');expect(status.textContent).toContain('解析：尚未完成');expect(status.textContent).toContain('映射：尚未保存')})
