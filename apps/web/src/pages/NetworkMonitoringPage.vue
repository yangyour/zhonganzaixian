<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {api,isCancelled} from '../api/client'
import {can,session,switchingTenant,contextGeneration} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {historySegments,readHistory,readResourceHistory,readResources,readSnapshot,sampleText,type Metric,type HistoryMetric,type ResourceMetric,type ResourceHistory,type ResourceSnapshot,type NetworkHistory,type NetworkSnapshot} from '../utils/networkMonitoring'
const snapshot=ref<NetworkSnapshot|null>(null),history=ref<NetworkHistory|ResourceHistory|null>(null)
const resources=ref<Record<string,{data:ResourceSnapshot;loaded:number}>>({}),resourceErrors=ref<Record<string,boolean>>({})
const error=ref<unknown>(null),historyError=ref<unknown>(null),loading=ref(false),historyLoading=ref(false)
const selected=ref(''),metric=ref<HistoryMetric>('latency'),window=ref<'HOUR_1'|'HOURS_24'>('HOUR_1')
const selectedHasResources=computed(()=>snapshot.value?.devices.some(d=>d.asset_id===selected.value&&d.vendor_metrics_status==='PARTIAL_API')??false)
const now=ref(performance.now()),loadedAt=ref(0)
const expired=computed(()=>!!snapshot.value&&now.value-loadedAt.value>90000)
const permitted=computed(()=>!switchingTenant.value&&can('assets:read'))
const segments=computed(()=>history.value?historySegments(history.value):[])
let alive=true,sequence=0,detailSequence=0,controller:AbortController|null=null,detailController:AbortController|null=null,timer:ReturnType<typeof setInterval>|null=null,ticks=0
function reset(){sequence++;detailSequence++;controller?.abort();detailController?.abort();snapshot.value=null;history.value=null;resources.value={};resourceErrors.value={};error.value=null;historyError.value=null;selected.value='';loading.value=false;historyLoading.value=false}
function selectDevice(id:string){if((metric.value==='cpu'||metric.value==='memory')&&!snapshot.value?.devices.some(d=>d.asset_id===id&&d.vendor_metrics_status==='PARTIAL_API'))metric.value='latency';selected.value=id}
function selectResource(id:string,m:ResourceMetric){selected.value=id;metric.value=m}
async function loadResources(value:NetworkSnapshot,seq:number,tenant:string,signal:AbortSignal){
  resources.value={};resourceErrors.value={}
  await Promise.all(value.devices.filter(d=>d.vendor_metrics_status==='PARTIAL_API').map(async d=>{
    try{const data=readResources(await api.request<ResourceSnapshot>(`/network-monitoring/${encodeURIComponent(d.asset_id)}/resources`,{signal}),d.asset_id);if(alive&&seq===sequence&&tenant===session.value?.tenant.id)resources.value[d.asset_id]={data,loaded:performance.now()}}
    catch(cause){if(alive&&seq===sequence&&!isCancelled(cause))resourceErrors.value[d.asset_id]=true}
  }))
}
async function refresh(){
  if(!permitted.value||loading.value)return
  const tenant=session.value!.tenant.id,seq=++sequence;controller?.abort();controller=new AbortController();loading.value=true;error.value=null
  try{const value=readSnapshot(await api.request<NetworkSnapshot>('/network-monitoring',{signal:controller.signal}),tenant)
    if(!alive||seq!==sequence||tenant!==session.value?.tenant.id)return
    snapshot.value=value;loadedAt.value=performance.now();now.value=loadedAt.value
    void loadResources(value,seq,tenant,controller.signal)
    if(selected.value&&!value.devices.some(d=>d.asset_id===selected.value)){selected.value='';history.value=null}
    if(selected.value)void loadHistory()
  }catch(cause){if(alive&&seq===sequence&&!isCancelled(cause)){snapshot.value=null;history.value=null;resources.value={};resourceErrors.value={};detailSequence++;detailController?.abort();historyLoading.value=false;error.value=cause}}
  finally{if(alive&&seq===sequence)loading.value=false}
}
async function loadHistory(){
  detailSequence++;detailController?.abort();history.value=null;historyError.value=null;historyLoading.value=false
  if(!permitted.value||!selected.value)return
  const seq=detailSequence,id=selected.value,m=metric.value,w=window.value
  detailController=new AbortController();historyLoading.value=true
  try{const data=m==='cpu'||m==='memory'?readResourceHistory(await api.request<ResourceHistory>(`/network-monitoring/${encodeURIComponent(id)}/resources/history?metric=${m}&window=${w}`,{signal:detailController.signal}),id,m,w):readHistory(await api.request<NetworkHistory>(`/network-monitoring/${encodeURIComponent(id)}/history?metric=${m}&window=${w}`,{signal:detailController.signal}),id,m,w)
    if(alive&&seq===detailSequence)history.value=data
  }catch(cause){if(alive&&seq===detailSequence&&!isCancelled(cause))historyError.value=cause}
  finally{if(alive&&seq===detailSequence)historyLoading.value=false}
}
watch([()=>session.value?.tenant.id,()=>session.value?.permission_version,contextGeneration,permitted],()=>{reset();if(permitted.value)void refresh()},{immediate:true,flush:'sync'})
watch([selected,metric,window],()=>void loadHistory())
function visible(){now.value=performance.now();if(!document.hidden)void refresh()}
onMounted(()=>{timer=setInterval(()=>{now.value=performance.now();ticks++;if(ticks%30===0&&!document.hidden)void refresh()},1000);document.addEventListener('visibilitychange',visible)})
onUnmounted(()=>{alive=false;reset();if(timer)clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
const time=(value:string|null)=>value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'未取得'
function display(d:import('../utils/networkMonitoring').Device,m:Metric){const recorded=d.metrics[m].recorded_at;const estimated=Date.parse(snapshot.value!.generated_at)+now.value-loadedAt.value;return sampleText(d,m,expired.value||recorded!==null&&estimated-Date.parse(recorded)>180000)}
function resourceText(id:string,m:ResourceMetric){const entry=resources.value[id];if(!entry)return resourceErrors.value[id]?'暂不可用':'读取中…';const v=entry.data;if(expired.value||now.value-entry.loaded>90000||v.received_at!==null&&Date.parse(v.generated_at)+now.value-entry.loaded-Date.parse(v.received_at)>180000)return '数据过期';if(v.state!=='FRESH')return ({STALE:'数据过期',NO_DATA:'等待采样',COLLECTOR_ERROR:'采集异常',CLOCK_SKEW:'时钟异常'} as const)[v.state];return `${Number(v.metrics[m].value).toFixed(1)}%`}
function resourceReason(id:string){const v=resources.value[id]?.data;if(!v)return '';if(v.reason==='PASSWORD_EXPIRED')return '设备密码已过期';if(v.reason==='ACCESS_DENIED')return '设备读取权限不足';if(v.reason==='VERSION_CHANGED')return '设备版本变化，待核验';return v.state==='FRESH'?'来源：防火墙 API':'资源采集未就绪'}
</script>
<template>
  <section class="network-page">
    <header class="network-heading"><div><p class="eyebrow">NETWORK MONITORING</p><h1>基础网络监控</h1><p>持续查看设备连通性、延迟、丢包和端口状态</p></div><el-button :loading="loading" :disabled="!permitted" @click="refresh">刷新监控</el-button></header>
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前没有资产读取权限" />
    <template v-else>
      <div class="network-context"><span>每 60 秒采集 · 页面每 30 秒刷新</span><span>资源指标按设备接入 · 告警规则待确认</span></div>
      <ApiErrorNotice v-if="error" :error="error" @retry="refresh" />
      <el-alert v-if="expired" title="页面数据已过期，请刷新后查看最新状态" type="warning" :closable="false" />
      <p v-if="loading&&!snapshot" role="status">正在读取监控数据…</p>
      <p v-if="snapshot&&!snapshot.devices.length" class="network-empty">当前租户尚未绑定基础监控设备。</p>
      <div v-if="snapshot" class="record-table network-records" role="region" tabindex="0" aria-label="设备监控列表，可横向滚动"><table><thead><tr><th>设备</th><th>管理IP</th><th>连通状态</th><th>平均响应</th><th>丢包</th><th>CPU / 内存</th><th>端口</th><th>采样时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="d in snapshot.devices" :key="d.asset_id" class="device-row" :class="{chosen:selected===d.asset_id}"><td><strong>{{d.name}}</strong><span class="row-secondary">{{d.brand}} {{d.model}}</span></td><td class="mono">{{d.management_ip}}</td><td><span class="network-badge" :class="{reachable:display(d,'icmp')==='可达'}">{{display(d,'icmp')}}</span><span class="row-secondary">IP 基础探测</span></td><td>{{display(d,'latency')}}</td><td>{{display(d,'loss')}}</td><td><template v-if="d.vendor_metrics_status==='PARTIAL_API'"><strong>{{resourceText(d.asset_id,'cpu')}} / {{resourceText(d.asset_id,'memory')}}</strong><span class="row-secondary">{{resourceReason(d.asset_id)}}</span><details><summary>资源采集说明</summary><p v-if="resources[d.asset_id]">采集接收：{{time(resources[d.asset_id]!.data.received_at)}}</p><p v-if="resources[d.asset_id]?.data.tls_mode==='TEST_INSECURE'">测试模式：已临时跳过证书校验</p></details></template><span v-else>未接入</span></td><td><span class="port-state" v-for="p in [22,80,443]" :key="p">{{p}} · {{display(d,`tcp_${p}` as Metric)}}</span></td><td>{{time(d.metrics.icmp.recorded_at)}}</td><td><div class="row-actions"><el-button link type="primary" @click="selectDevice(d.asset_id)">查看历史 · {{d.management_ip}}</el-button><template v-if="d.vendor_metrics_status==='PARTIAL_API'"><el-button link @click="selectResource(d.asset_id,'cpu')">CPU 历史</el-button><el-button link @click="selectResource(d.asset_id,'memory')">内存历史</el-button></template></div></td></tr>
      </tbody></table></div>
      <section class="network-history">
        <div class="history-heading"><div><h2>采样历史</h2><p>选择设备后查看。缺失采样会留空，不补零、不延续旧值。</p></div><div class="history-controls"><label>指标<select v-model="metric"><option value="latency">响应延迟</option><option value="loss">丢包率</option><option value="icmp">ICMP响应</option><option value="tcp_22">TCP 22</option><option value="tcp_80">TCP 80</option><option value="tcp_443">TCP 443</option><template v-if="selectedHasResources"><option value="cpu">CPU 使用率</option><option value="memory">内存使用率</option></template></select></label><label>时间<select v-model="window"><option value="HOUR_1">最近1小时</option><option value="HOURS_24">最近24小时</option></select></label></div></div>
        <p v-if="!selected" class="network-empty">请选择上方一台设备查看历史。</p>
        <p v-if="historyLoading" role="status">正在读取历史采样…</p>
        <ApiErrorNotice v-if="historyError" :error="historyError" @retry="loadHistory" />
        <template v-if="history">
          <p class="history-caption">{{history.points.length}} 个实际采样点 · {{time(history.from)}} — {{time(history.to)}} · 原始单位 {{history.unit||'0 / 1'}}</p>
          <p v-if="history.source==='SANGFOR_AF_API'" class="history-caption">时间为采集端接收时刻；设备未提供原始采样时间。</p>
          <p v-if="!history.points.length" class="network-empty">该时间窗口尚无采样记录。</p>
          <svg v-else class="history-chart" viewBox="0 0 720 180" role="img" :aria-label="`${metric}历史采样，空窗不连线`"><path d="M16 20V154H704" stroke="#cedbe8" fill="none"/><g v-for="(segment,i) in segments" :key="i"><polyline :points="segment.line" fill="none" stroke="#1667b9" stroke-width="2"/><circle v-for="(p,j) in segment.dots" :key="j" :cx="p.x" :cy="p.y" r="2.5" fill="#1667b9"/></g></svg>
          <p v-if="metric==='latency'" class="history-caption">延迟为回应包的平均值；无回应返回的零值不画成正常延迟，请结合 ICMP 响应和丢包查看。</p>
          <div class="history-table" role="region" tabindex="0" aria-label="历史采样表，可横向滚动"><table><thead><tr><th scope="col">最近采样时间</th><th scope="col">原始值 {{history.unit}}</th></tr></thead><tbody><tr v-for="p in history.points.slice(-12).reverse()" :key="p.recorded_at"><td>{{time(p.recorded_at)}}</td><td>{{p.value}}</td></tr></tbody></table></div>
        </template>
      </section>
    </template>
  </section>
</template>
<style scoped>
.resource-panel{border-top:1px solid #e3eaf2;margin-top:16px;padding-top:16px}.resource-stats{display:grid;grid-template-columns:1fr 1fr;gap:12px}.resource-stats span{font-size:12px;color:#64748b;display:block}.resource-stats strong{display:block;font-size:21px;color:#244260;margin:7px 0 10px}.resource-panel p{font-size:11px;color:#64748b;line-height:1.6;margin:8px 0 0;overflow-wrap:anywhere}.resource-panel .tls-note{color:#92610c}
.network-page{max-width:1440px;margin:auto;color:#20334c}.network-heading,.history-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:22px}.network-heading h1{font-size:28px;margin:5px 0 8px}.network-heading p,.history-heading p{color:#64748b;margin:0;font-size:14px}.network-heading .eyebrow{font-size:11px;letter-spacing:2px;color:#3972a6}.network-context{display:flex;justify-content:space-between;flex-wrap:wrap;gap:10px;background:#edf4fb;padding:12px 16px;border-radius:10px;margin:18px 0;font-size:13px;color:#486580}.network-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));align-items:start;gap:18px;margin:20px 0}.device-card,.network-history{border:1px solid #dce5ee;border-radius:14px;background:white;padding:22px;min-width:0}.device-card.chosen{border-color:#438dcf;box-shadow:0 0 0 1px #438dcf}.device-head{display:flex;justify-content:space-between;align-items:center}.device-head small{color:#7a899b}.network-badge{padding:5px 10px;border-radius:20px;background:#fff2dc;color:#8d6222;font-size:12px}.network-badge.reachable{background:#e9f5ef;color:#24724e}.device-card h2{font-size:18px;margin:20px 0 8px;overflow-wrap:anywhere}.device-model,.sample-time{font-size:12px;color:#64748b;overflow-wrap:anywhere}.device-ip{font-size:20px;font-variant-numeric:tabular-nums;margin:14px 0}.network-stats{display:grid;grid-template-columns:1fr 1fr;padding:16px 0;border-top:1px solid #edf1f5;border-bottom:1px solid #edf1f5;gap:10px}.network-stats span{display:block;font-size:12px;color:#718298}.network-stats strong{display:block;font-size:18px;margin-top:8px;overflow-wrap:anywhere}.network-ports{display:grid;grid-template-columns:1fr 1fr;row-gap:10px;font-size:13px}.network-ports dt{color:#73859a}.network-ports dd{margin:0;text-align:right}.sample-time{margin:18px 0}.device-card .el-button{width:100%;font-size:12px}.network-history h2{font-size:19px;margin:0 0 8px}.history-controls{display:flex;gap:12px}.history-controls label{font-size:12px;color:#52667d;display:grid;gap:5px}.history-controls select{padding:8px;border:1px solid #d4dfe9;border-radius:6px;background:white;color:#244260}.network-empty{padding:30px;text-align:center;color:#7b8da3;background:#f7f9fc;border-radius:8px;font-size:14px}.history-chart{width:100%;max-height:260px;background:#f8fbff;border-radius:8px}.history-caption{color:#6c7e92;font-size:12px;line-height:1.7}.history-table{overflow:auto}.history-table table{border-collapse:collapse;width:100%;font-size:13px}.history-table th,.history-table td{text-align:left;border-bottom:1px solid #edf1f5;padding:9px 12px}.history-table th{color:#64748b;background:#f7f9fc}@media(max-width:1350px){.network-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:700px){.network-grid{grid-template-columns:1fr}.network-heading,.history-heading{align-items:flex-start;flex-direction:column}.device-card,.network-history{padding:16px}.network-heading h1{font-size:24px}.history-controls{width:100%}.history-controls label{flex:1}.history-controls select{width:100%}}
.network-records{margin:20px 0}.network-records table{min-width:1200px}.network-records td{font-variant-numeric:tabular-nums}.network-records .chosen{background:#edf4ff}.port-state{display:block;white-space:nowrap}.network-records .row-actions{min-width:130px}.network-records .el-button{height:auto;text-align:left}.network-records details{font-size:12px}
</style>
