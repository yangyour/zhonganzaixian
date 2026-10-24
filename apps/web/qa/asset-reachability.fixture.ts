import {createApp,h,shallowRef,ref} from 'vue'
import {createRouter,createWebHistory} from 'vue-router'
import '../src/styles/theme.css'
import Panel from '../src/components/AssetReachabilityPanel.vue'
import {createReachabilityReader,emptyReachability} from '../src/utils/assetReachability'
import {api} from '../src/api/client'
import {reachabilityScope} from '../tests/assetReachability.fixture'
const state=shallowRef(emptyReachability()),visible=ref(true),now=ref(performance.now()),reader=createReachabilityReader(api,v=>state.value=v)
reader.reset(reachabilityScope);void reader.load();setInterval(()=>now.value=performance.now(),1000)
const router=createRouter({history:createWebHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<p />'}}]})
createApp({render:()=>h('main',{style:'max-width:1100px;margin:20px auto'},[h('h1','资产在线范围'),h('p','合成身份与模拟采样，仅检查交互，不是实际设备证据。'),h('button',{onClick:()=>{reader.reset(null);visible.value=false}},'模拟撤权'),visible.value?h(Panel,{state:state.value,now:now.value,onRefresh:()=>reader.load(),onCancel:()=>reader.cancel(),onClose:()=>visible.value=false}):h('p','明细已关闭')])}).use(router).mount('#fixture')
