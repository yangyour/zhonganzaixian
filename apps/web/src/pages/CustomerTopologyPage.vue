<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {api,isCancelled} from '../api/client'
import {session,can,contextGeneration,switchingTenant} from '../stores/session'
import {navigateFromLink} from '../utils/linkNavigation'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {readCustomerTopology,topologyPositions,type CustomerTopology} from '../utils/customerTopology'
const route=useRoute(),router=useRouter(),data=ref<CustomerTopology|null>(null),error=ref<unknown>(null),busy=ref(false),cancelled=ref(false),view=ref<'graph'|'table'>('graph'),query=ref('')
const focus=computed(()=>typeof route.query.asset_id==='string'?route.query.asset_id:null)
const permitted=computed(()=>!!session.value&&!switchingTenant.value&&can('assets:read'))
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value,permitted.value,focus.value]))
let epoch=0,alive=true,controller:AbortController|undefined,previousTenant:string|undefined
let attempt:{offset:number;version?:number}={offset:0}
function reset(){epoch++;controller?.abort();data.value=null;error.value=null;busy.value=false;cancelled.value=false;query.value=''}
async function load(offset=0,version?:number){
 if(!permitted.value||busy.value)return
 const owner=scope.value,turn=++epoch,tenant=session.value!.tenant.id,selected=focus.value
 controller?.abort();controller=new AbortController();const signal=controller.signal
 attempt={offset,version};busy.value=true;data.value=null;error.value=null;cancelled.value=false
 const params=new URLSearchParams({offset:String(offset)});if(version!==undefined)params.set('graph_version',String(version));if(selected)params.set('asset_id',selected)
 try{const raw=await api.request<unknown>(`/customer-topology?${params}`,{signal});if(!alive||turn!==epoch||owner!==scope.value||signal.aborted)return;data.value=readCustomerTopology(raw,tenant,offset,selected,version)}
 catch(cause){if(alive&&turn===epoch&&owner===scope.value&&!isCancelled(cause))error.value=cause}
 finally{if(alive&&turn===epoch)busy.value=false}
}
function cancel(){epoch++;controller?.abort();busy.value=false;data.value=null;cancelled.value=true}
watch(scope,()=>{
 const tenant=session.value?.tenant.id,changed=previousTenant!==undefined&&previousTenant!==tenant;previousTenant=tenant
 reset();attempt={offset:0}
 if(changed&&focus.value){void router.replace({name:'customer-topology',query:{}}).catch(cause=>{error.value=cause});return}
 if(permitted.value)void load()
},{immediate:true,flush:'sync'})
onUnmounted(()=>{alive=false;reset()})
const names=computed(()=>new Map(data.value?.nodes.map(n=>[n.id,n])||[]))
const edges=computed(()=>data.value?.edges.filter(e=>{const text=query.value.trim().toLocaleLowerCase();return !text||[names.value.get(e.parent_id),names.value.get(e.child_id)].some(n=>`${n?.name} ${n?.management_ip}`.toLocaleLowerCase().includes(text))})||[])
const nodes=computed(()=>data.value?.nodes.filter(n=>!query.value.trim()||edges.value.some(e=>e.parent_id===n.id||e.child_id===n.id))||[])
const positioned=computed(()=>topologyPositions(nodes.value,edges.value)),positions=computed(()=>new Map(positioned.value.map(n=>[n.id,n])))
const graphAllowed=computed(()=>nodes.value.length<=24),width=computed(()=>Math.max(300,...positioned.value.map(n=>n.x+220))),height=computed(()=>Math.max(140,...positioned.value.map(n=>n.y+75)))
const assetLink=(id:string)=>router.resolve({name:'assets',query:{asset_id:id}}).href
function openAsset(id:string){return router.push({name:'assets',query:{asset_id:id}}).catch(cause=>{error.value=cause})}
function choose(id:string|null){void router.push({name:'customer-topology',query:id?{asset_id:id}:{}}).catch(cause=>{error.value=cause})}
</script>
<template>
<section class="customer-topology-page">
<h1 class="page-title-sr">客户拓扑</h1>
<div class="content-toolbar"><strong>{{session?.tenant.name}} · 客户拓扑</strong><span>登记依赖 · 未核验物理连接</span><div class="content-actions"><el-button v-if="focus" :disabled="busy" @click="choose(null)">全部关系</el-button><el-button :disabled="!permitted||busy" @click="load()">刷新</el-button><el-button v-if="busy" @click="cancel">取消读取</el-button><router-link :to="{name:'assets',query:focus?{asset_id:focus}:{}}">{{focus?'编辑该设备关系':'管理设备与关系'}}</router-link><router-link :to="{name:'network-monitoring'}">设备监控</router-link></div></div>
<p v-if="!permitted" role="status">当前客户无资产读取权限，请切换至已授权客户。</p>
<ApiErrorNotice v-if="error" :error="error" />
<div v-if="error||cancelled" class="topology-recovery"><span v-if="cancelled" role="status">读取已取消</span><el-button :disabled="!permitted||busy" @click="load(attempt.offset,attempt.version)">重试原请求</el-button><el-button :disabled="!permitted||busy" @click="load()">重新读取第一页</el-button></div>
<p v-if="busy" role="status">正在读取当前客户拓扑…</p>
<template v-if="data">
<div class="content-toolbar"><label>本页查找 <input v-model="query" type="search" placeholder="设备名称 / IP" /></label><div class="content-actions"><button :disabled="!graphAllowed" :aria-pressed="view==='graph'&&graphAllowed" @click="view='graph'">关系图</button><button :aria-pressed="view==='table'||!graphAllowed" @click="view='table'">关系列表</button><span v-if="!graphAllowed">本页设备较多，使用列表查看</span></div></div>
<p v-if="!data.total_edges" role="status">{{focus?'此设备尚未登记依赖关系。':'当前客户尚未登记依赖关系。'}}可从设备详情添加；系统不会推测或补造连接。</p>
<p v-else-if="!edges.length" role="status">本页没有匹配关系。<button @click="query=''">清除查找</button></p>
<div v-else-if="view==='graph'&&graphAllowed" class="topology-canvas" tabindex="0" role="region" aria-label="本页登记依赖关系图，可滚动；箭头从上游指向下游">
<svg :width="width" :height="height" role="img" aria-label="登记依赖草稿，箭头从上游指向下游，非已核验物理网络">
<defs><marker id="topology-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8" fill="#526276" /></marker></defs>
<path v-for="e in edges" :key="e.parent_id+'/'+e.child_id" :d="`M${positions.get(e.parent_id)!.x+200},${positions.get(e.parent_id)!.y+28} L${positions.get(e.child_id)!.x},${positions.get(e.child_id)!.y+28}`" fill="none" stroke="#526276" stroke-dasharray="5 4" marker-end="url(#topology-arrow)" />
<a v-for="n in positioned" :key="n.id" :href="assetLink(n.id)" :aria-label="n.name+'，查看设备详情'" @click="navigateFromLink($event,()=>openAsset(n.id))"><rect :x="n.x" :y="n.y" width="200" height="58" rx="6" fill="white" stroke="#245daf" /><title>{{n.name}} · {{n.management_ip}}</title><text :x="n.x+10" :y="n.y+22">{{n.name.length>14?n.name.slice(0,14)+'…':n.name}}</text><text :x="n.x+10" :y="n.y+43" class="topology-ip">{{n.management_ip}}</text></a>
</svg></div>
<div v-else class="topology-table" tabindex="0" role="region" aria-label="本页登记依赖关系列表，可横向滚动"><table><caption class="page-title-sr">当前客户登记依赖草稿</caption><thead><tr><th scope="col">上游设备</th><th scope="col">下游设备</th><th scope="col">登记类型</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="e in edges" :key="e.parent_id+'/'+e.child_id"><td><a :href="assetLink(e.parent_id)" @click="navigateFromLink($event,()=>openAsset(e.parent_id))">{{names.get(e.parent_id)?.name}}</a><small>{{names.get(e.parent_id)?.management_ip}}</small></td><td><a :href="assetLink(e.child_id)" @click="navigateFromLink($event,()=>openAsset(e.child_id))">{{names.get(e.child_id)?.name}}</a><small>{{names.get(e.child_id)?.management_ip}}</small></td><td>{{e.proposed_type||'未填写'}} · 草稿</td><td><button @click="choose(e.parent_id)">查看上游关联</button><button @click="choose(e.child_id)">查看下游关联</button></td></tr></tbody></table></div>
<div class="topology-paging"><el-button :disabled="data.offset===0||busy" @click="load(Math.max(0,data.offset-50),data.version)">上一页</el-button><span role="status">{{data.total_edges?data.offset+1:0}}–{{data.offset+data.edges.length}} / {{data.total_edges}} 条登记关系 · 草稿 v{{data.version}}</span><el-button :disabled="!data.has_more||busy" @click="load(data.offset+50,data.version)">下一页</el-button></div>
</template>
</section>
</template>
<style scoped>
.customer-topology-page{min-width:0}.content-toolbar{flex-wrap:wrap;gap:8px}.content-toolbar>span,.topology-paging{font-size:12px;color:#526276}.content-actions{flex-wrap:wrap}.topology-canvas,.topology-table{overflow:auto;max-height:560px;background:#fff;border:1px solid #d5dfec;border-radius:6px}.topology-canvas svg{display:block}.topology-canvas text{font-size:13px;fill:#173352}.topology-canvas .topology-ip{font-size:12px;fill:#526276}.topology-canvas a:focus rect{stroke-width:3}.topology-table table{width:100%;border-collapse:collapse}.topology-table td,.topology-table th{text-align:left;padding:10px;border-bottom:1px solid #d5dfec}.topology-table small{display:block;color:#526276}.topology-paging{display:flex;justify-content:flex-end;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 0}button,input{font:inherit;min-height:36px}button{cursor:pointer}button:disabled{cursor:not-allowed}a{color:#245daf}input{max-width:100%;border:1px solid #d5dfec;border-radius:4px;padding:4px 8px}.topology-recovery{display:flex;flex-wrap:wrap;align-items:center;gap:8px}:focus-visible{outline:2px solid #245daf;outline-offset:2px}@media(max-width:600px){button,input{min-height:44px}.topology-paging{justify-content:space-between}.content-toolbar label{width:100%}.topology-table table{min-width:620px}}
</style>
