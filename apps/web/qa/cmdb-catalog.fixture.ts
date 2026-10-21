// Component/browser fixture only: synthetic identity, network responses supplied by the browser test.
// Not a production entrypoint and not proof of backend authorization.
import {createApp,h} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/CmdbTypeCatalogPanel.vue'
import {session,contextGeneration} from '../src/stores/session'
import fixture from '../../../fixtures/cmdb-type-catalog-controller.json'
session.value={user:{id:'64000000-0000-4000-8000-000000000001',display_name:'Synthetic',role:'msp'},tenant:{id:fixture.data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[
 h('h1',{style:'font-size:22px'},'CMDB 字典交互验收'),
 h('p','仅使用合成身份和模拟 API 响应，不代表后台权限或客户验收。'),
 h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(Panel)
])}).use(ElementPlus).mount('#fixture')
