// Development-only disposable synthetic capture; never production authority.
import {createApp,h,reactive} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/CmdbSourcePreflightPanel.vue'
import type {AssetImportSource} from '../src/api/assetImportTypes'
import {session,contextGeneration} from '../src/stores/session'
import capture from '../../../fixtures/cmdb-name-http-synthetic-20261021.json'
if(!import.meta.env.DEV)throw Error('Synthetic CMDB name-check entry is development-only')
const source=reactive(capture.source as unknown as AssetImportSource)
session.value={user:{id:source.created_by,display_name:'Synthetic',role:'msp'},tenant:{id:source.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:write'],permission_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[h('h1','CMDB 重名检查合成交互验收'),h('p','合成身份与模拟API，不是客户验收或生产授权。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h('h2','当前来源'),h(Panel,{source})])}).use(ElementPlus).mount('#fixture')
