// Development-only disposable metadata, never authority or production bootstrap.
import {createApp,h,onMounted,onUnmounted} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/AssetImportSourcesPanel.vue'
import {createAssetImportWorkspace} from '../src/utils/assetImportWorkspace'
import {session,contextGeneration} from '../src/stores/session'
import fixture from '../../../fixtures/import-source-list-http-synthetic-20261021.json'
if(!import.meta.env.DEV)throw Error('Synthetic import-source recovery entry is development-only')
session.value={user:{id:fixture.first.created_by,display_name:'Synthetic',role:'msp'},tenant:{id:fixture.first.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:write'],permission_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
createApp({setup(){const work=createAssetImportWorkspace();onMounted(()=>work.loadPolicy());onUnmounted(work.dispose);return()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[h('h1','来源恢复合成交互验收'),h('p','合成身份与模拟API，不是生产授权或真实浏览器验收。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h('h2','恢复来源'),h(Panel,{canOpen:work.canResume.value,onSelect:work.resume}),work.source.value?h('p',{role:'status'},'已重新打开来源 '+work.source.value.id+'，未重新上传。'):null])}}).use(ElementPlus).mount('#fixture')
