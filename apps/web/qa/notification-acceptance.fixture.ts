import {createApp,h,shallowRef,ref} from 'vue'
import '../src/styles/theme.css'
import Panel from '../src/components/NotificationAcceptancePanel.vue'
import {createAcceptanceReader,emptyAcceptance} from '../src/utils/notificationAcceptance'
import {acceptanceScope} from '../tests/notificationAcceptance.fixture'
import {api} from '../src/api/client'
const state=shallowRef(emptyAcceptance()),visible=ref(true),reader=createAcceptanceReader(api,v=>state.value=v)
reader.reset(acceptanceScope);void reader.load()
createApp({render:()=>h('main',{style:'max-width:1100px;margin:20px auto'},[h('h1','通知受理范围'),h('p','合成身份与模拟API，仅检查交互，不是供应商送达证据。'),h('button',{onClick:()=>{reader.reset(null);visible.value=false}},'模拟撤权'),visible.value?h(Panel,{state:state.value,expired:false,onRefresh:()=>reader.load(),onCancel:()=>reader.cancel(),onClose:()=>visible.value=false}):h('p','明细已关闭')])}).mount('#fixture')
