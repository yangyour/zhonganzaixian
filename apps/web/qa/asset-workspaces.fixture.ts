import {createApp,defineComponent,h,ref,type Component} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Metadata from '../src/components/AssetMetadataWorkspace.vue'
import Warranty from '../src/components/ManualWarrantyWorkspace.vue'
import Dependency from '../src/components/AssetDependencyWorkspace.vue'
import MetadataQuery from '../src/components/AssetMetadataQuery.vue'
import DateQuery from '../src/components/AssetDateQuery.vue'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
import {metadata,warranty,dependency,dateQuery,dependencyAsset} from './asset-workspaces.data'
if(!import.meta.env.DEV)throw Error('Synthetic asset workspace browser entry is development-only')
const panels:Record<string,Component>={metadata:Metadata,warranty:Warranty,dependency:Dependency,metadataQuery:MetadataQuery,dateQuery:DateQuery}
const mode=new URL(location.href).searchParams.get('panel')??'metadata';if(!Object.hasOwn(panels,mode))throw Error('Unknown synthetic panel')
const scope=mode==='dependency'?dependency.context:mode==='dateQuery'?dateQuery.context:metadata.context
session.value={user:{id:scope.actorId,role:'msp',display_name:'Synthetic'},tenant:{id:scope.tenantId,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read','assets:write','asset_dates:read'],permission_version:scope.permissionVersion,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
const opened=ref(false),selected=ref('')
const Route=defineComponent({setup(){return()=>h('section',[
 h('h1','资产工作区合成验收'),h('p','仅模拟API、账号变化及路由，不代表真实生产授权或设备操作。'),
 h('button',{style:'min-height:44px',onClick:()=>{opened.value=true}},'打开验收面板'),
 h('button',{style:'min-height:44px',onClick:()=>void router.push('/away')},'离开验收页面'),
 h('p',{role:'status'},selected.value?`已选择合成资产 ${selected.value}`:'尚未选择资产'),
 h(panels[mode]!,{modelValue:opened.value,'onUpdate:modelValue':(value:boolean)=>{opened.value=value},...(['metadata','warranty','dependency'].includes(mode)?{asset:mode==='dependency'?dependencyAsset():mode==='warranty'?warranty.current().asset:metadata.current().asset}:{}),onSelect:(value:string|{id:string})=>{selected.value=typeof value==='string'?value:value.id}})
])}})
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Route},{path:'/away',component:{render:()=>h('h1','已离开合成工作区')}}]})
// Simulates an externally arriving session update, not a click through a modal overlay.
window.addEventListener('synthetic-asset-account-version',()=>{if(session.value)session.value={...session.value,account_version:(session.value.account_version??0)+1}})
window.addEventListener('synthetic-asset-revoke',()=>{session.value=null;api.invalidateContext();contextGeneration.value++})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box;min-width:0'},[h(RouterView)])}).use(router).use(ElementPlus).mount('#fixture')
