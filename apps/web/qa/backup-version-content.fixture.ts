import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/theme.css'
import Panel from '../src/components/BackupVersionInventoryPanel.vue'
import type { Asset } from '../src/api/types'
import { session, contextGeneration } from '../src/stores/session'
import { api } from '../src/api/client'
import { scope } from './backup-inventory.data'
if (!import.meta.env.DEV) throw Error('Synthetic backup content entry is development-only')
session.value = { user:{id:scope.actorId,role:'padmin',display_name:'合成测试'},tenant:{id:scope.tenantId,name:'合成测试'},allowed_tenants:[],capabilities:['assets:read'],permission_version:scope.permissionVersion,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only' }
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
createApp({ render: () => h('div', {class:'prototype-shell'}, [h('main', {id:'main-content',style:'max-width:1100px;margin:auto;padding:16px;box-sizing:border-box'}, [
  h('h1','备份内容合成浏览器检查'),h('p','使用模拟接口与合成文件，不连接真实设备、存储或密钥服务。'),
  h('button',{onClick:()=>{session.value=null;api.invalidateContext();contextGeneration.value++}},'模拟撤销当前上下文'),
  h(Panel,{asset:{id:scope.assetId,tenant_id:scope.tenantId,version:1} as Asset}),
])]) }).use(ElementPlus).mount('#fixture')
