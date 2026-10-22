import {createApp,h} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/BackupVersionInventoryPanel.vue'
import type {Asset} from '../src/api/types'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
import {scope} from './backup-inventory.data'
if(!import.meta.env.DEV)throw Error('Synthetic backup inventory browser entry is development-only')
session.value={user:{id:scope.actorId,role:'viewer',display_name:'Synthetic'},tenant:{id:scope.tenantId,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read'],permission_version:scope.permissionVersion,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box;min-width:0'},[
 h('h1','备份版本合成验收'),h('p','模拟API与身份，不代表生产授权或恢复验证。'),
 h('button',{style:'min-height:44px',onClick:()=>{session.value=null;api.invalidateContext();contextGeneration.value++}},'模拟撤销权限'),
 h('button',{style:'min-height:44px',onClick:()=>{if(session.value)session.value={...session.value,account_version:(session.value.account_version??0)+1}}},'模拟账号版本变化'),
 h(Panel,{asset:{id:scope.assetId,tenant_id:scope.tenantId,version:1} as Asset})
])}).use(ElementPlus).mount('#fixture')
