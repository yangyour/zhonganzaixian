<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {api,isCancelled} from '../api/client'
import {useMonitoringRefreshPolicy,refreshPermissionRejected} from '../utils/monitoringRefreshPolicy'
import {can,session,switchingTenant,contextGeneration} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {historySegments,readHistory,readResourceHistory,readResources,readSnapshot,sampleText,type Metric,type HistoryMetric,type ResourceMetric,type ResourceHistory,type ResourceSnapshot,type NetworkHistory,type NetworkSnapshot} from '../utils/networkMonitoring'
const snapshot=ref<NetworkSnapshot|null>(null),history=ref<NetworkHistory|ResourceHistory|null>(null)
const resources=ref<Record<string,{data:ResourceSnapshot;loaded:number}>>({}),resourceErrors=ref<Record<string,boolean>>({})
const error=ref<unknown>(null),historyError=ref<unknown>(null),loading=ref(false),historyLoading=ref(false)
const selected=ref(''),metric=ref<HistoryMetric>('latency'),window=ref<'HOUR_1'|'HOURS_24'>('HOUR_1')
const monitorView=ref<'list'|'cards'>('list'),deviceQuery=ref('')
const filteredDevices=computed(()=>snapshot.value?.devices.filter(device=>`${device.name} ${device.management_ip} ${device.brand} ${device.model}`.toLocaleLowerCase().includes(deviceQuery.value.trim().toLocaleLowerCase()))??[])
const reachableCount=computed(()=>snapshot.value?.devices.filter(device=>display(device,'icmp')==='可达').length??0)
const resourceCount=computed(()=>snapshot.value?.devices.filter(device=>device.vendor_metrics_status==='PARTIAL_API').length??0)
const selectedHasResources=computed(()=>snapshot.value?.devices.some(d=>d.asset_id===selected.value&&d.vendor_metrics_status==='PARTIAL_API')??false)
const now=ref(performance.now()),loadedAt=ref(0)
const expired=computed(()=>!!snapshot.value&&now.value-loadedAt.value>90000)
const permitted=computed(()=>!switchingTenant.value&&can('assets:read'))
const {intervalSeconds:refreshInterval,label:refreshPolicyLabel,error:refreshPolicyError,refresh:refreshPolicy}=useMonitoringRefreshPolicy(permitted)
const segments=computed(()=>history.value?historySegments(history.value):[])
let alive=true,sequence=0,detailSequence=0,controller:AbortController|null=null,detailController:AbortController|null=null,timer:ReturnType<typeof setInterval>|null=null,lastRefresh=0
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
  lastRefresh=performance.now()
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
watch(refreshPolicyError,cause=>{if(refreshPermissionRejected(cause))reset()},{flush:'sync'})
function visible(){now.value=performance.now();if(!document.hidden&&refreshInterval.value!==null)void refresh()}
onMounted(()=>{timer=setInterval(()=>{now.value=performance.now();if(refreshInterval.value!==null&&now.value-lastRefresh>=refreshInterval.value*1000&&!document.hidden)void refresh()},1000);document.addEventListener('visibilitychange',visible)})
onUnmounted(()=>{alive=false;reset();if(timer)clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
const time=(value:string|null)=>value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'未取得'
function display(d:import('../utils/networkMonitoring').Device,m:Metric){const recorded=d.metrics[m].recorded_at;const estimated=Date.parse(snapshot.value!.generated_at)+now.value-loadedAt.value;return sampleText(d,m,expired.value||recorded!==null&&estimated-Date.parse(recorded)>180000)}
function resourceText(id:string,m:ResourceMetric){const entry=resources.value[id];if(!entry)return resourceErrors.value[id]?'暂不可用':'读取中…';const v=entry.data;if(expired.value||now.value-entry.loaded>90000||v.received_at!==null&&Date.parse(v.generated_at)+now.value-entry.loaded-Date.parse(v.received_at)>180000)return '数据过期';if(v.state!=='FRESH')return ({STALE:'数据过期',NO_DATA:'等待采样',COLLECTOR_ERROR:'采集异常',CLOCK_SKEW:'时钟异常'} as const)[v.state];return `${Number(v.metrics[m].value).toFixed(1)}%`}
function resourceReason(id:string){const v=resources.value[id]?.data;if(!v)return '';if(v.reason==='PASSWORD_EXPIRED')return '设备密码已过期';if(v.reason==='ACCESS_DENIED')return '设备读取权限不足';if(v.reason==='VERSION_CHANGED')return '设备版本变化，待核验';return v.state==='FRESH'?'来源：防火墙 API':'资源采集未就绪'}
</script>
<template>
  <section class="network-page">
    <p v-if="permitted" class="network-policy" aria-label="监控自动刷新设置">{{refreshPolicyLabel}} · 设置每30秒重新核对</p><ApiErrorNotice v-if="permitted" :error="refreshPolicyError" @retry="refreshPolicy"/>
    <header class="network-heading"><div><div class="title-with-context"><h1>基础网络监控</h1><span class="context-badge">{{session?.tenant.name}}</span></div><details class="boundary-details"><summary>页面说明</summary><p>实时展示已绑定网络设备的连通性、响应延迟、丢包率及端口状态（当前页统计）。未采集指标显示为“待采集”，不计入正常或零值统计；人工录入或预置台账资产暂未开通实时采集，无监控时序指标。</p></details></div><el-button :loading="loading" :disabled="!permitted" @click="refresh">刷新监控</el-button></header>
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前没有资产读取权限" />
    <template v-else>
      <div class="feature-summary network-summary"><article><span>绑定设备</span><strong>{{snapshot?snapshot.devices.length:'—'}}</strong><small>当前租户明确授权范围</small></article><article class="success"><span>ICMP 可达</span><strong>{{snapshot&&!expired?reachableCount:'—'}}</strong><small>仅表示本次 IP 探测结果</small></article><article><span>资源采集已接入</span><strong>{{snapshot?resourceCount:'—'}}</strong><small>CPU / 内存逐设备接入</small></article><article><span>监控刷新</span><strong>{{refreshInterval??'暂停'}} <small v-if="refreshInterval!==null">秒</small></strong><small>后台暂停 · 采集间隔60秒</small></article></div>
      <ApiErrorNotice v-if="error" :error="error" @retry="refresh" />
      <el-alert v-if="expired" title="页面数据已过期，请刷新后查看最新状态" type="warning" :closable="false" />
      <p v-if="loading&&!snapshot" role="status">正在读取监控数据…</p>
      <p v-if="snapshot&&!snapshot.devices.length" class="network-empty">当前租户尚未绑定基础监控设备。</p>
      <section class="panel network-device-workspace"><div class="network-list-toolbar"><div class="network-view-tabs" aria-label="监控显示方式"><button type="button" :class="{active:monitorView==='list'}" :aria-pressed="monitorView==='list'" @click="monitorView='list'">设备列表</button><button type="button" :class="{active:monitorView==='cards'}" :aria-pressed="monitorView==='cards'" @click="monitorView='cards'">监控卡片</button></div><input v-model="deviceQuery" aria-label="搜索监控设备" placeholder="搜索设备 / IP / 型号" /><span>告警规则待确认</span></div>
      <div v-if="monitorView==='list'" class="proto-table-wrap"><table class="proto-table network-device-table"><thead><tr><th>设备 / 型号</th><th>管理 IP</th><th>连通性</th><th>响应延迟</th><th>丢包率</th><th>CPU / 内存</th><th>最近记录</th><th>操作</th></tr></thead><tbody><tr v-for="d in filteredDevices" :key="d.asset_id" class="network-device-row" :class="{chosen:selected===d.asset_id}"><td><strong>{{d.name}}</strong><small>{{d.brand}} {{d.model}}</small></td><td class="mono">{{d.management_ip}}</td><td><span class="network-badge" :class="{reachable:display(d,'icmp')==='可达'}">{{display(d,'icmp')}}</span></td><td>{{display(d,'latency')}}</td><td>{{display(d,'loss')}}</td><td><template v-if="d.vendor_metrics_status==='PARTIAL_API'"><button type="button" class="resource-inline-link" aria-label="CPU 历史" @click="selectResource(d.asset_id,'cpu')">CPU {{resourceText(d.asset_id,'cpu')}}</button><button type="button" class="resource-inline-link" aria-label="内存历史" @click="selectResource(d.asset_id,'memory')">内存 {{resourceText(d.asset_id,'memory')}}</button><small v-if="resources[d.asset_id]?.data.state!=='FRESH'" class="network-resource-reason">{{resourceReason(d.asset_id)}}</small><small v-if="resources[d.asset_id]?.data.tls_mode==='TEST_INSECURE'" class="tls-note">测试模式：已临时跳过证书校验</small></template><span v-else class="muted">— <small>待采集</small></span></td><td><small>{{time(d.metrics.icmp.recorded_at)}}</small></td><td><el-button link type="primary" @click="selectDevice(d.asset_id)">查看历史</el-button></td></tr><tr v-if="!filteredDevices.length"><td colspan="8" class="proto-empty">{{loading?'正在读取监控设备…':snapshot?'当前筛选下没有设备':'监控数据尚未读取成功'}}</td></tr></tbody></table></div>
      <div v-else-if="snapshot" class="network-grid">
        <article v-for="d in filteredDevices" :key="d.asset_id" class="device-card" :class="{chosen:selected===d.asset_id}">
          <div class="device-head"><span class="network-badge" :class="{reachable:display(d,'icmp')==='可达'}">{{display(d,'icmp')}}</span><small>IP 基础探测</small></div>
          <h2>{{d.name}}</h2><p class="device-model">{{d.brand}} {{d.model}}</p><p class="device-ip">{{d.management_ip}}</p>
          <div class="network-stats"><div><span>平均响应</span><strong>{{display(d,'latency')}}</strong></div><div><span>本次丢包</span><strong>{{display(d,'loss')}}</strong></div></div>
          <dl class="network-ports"><template v-for="p in [22,80,443]" :key="p"><dt>TCP {{p}}</dt><dd>{{display(d,`tcp_${p}` as Metric)}}</dd></template></dl>
          <section v-if="d.vendor_metrics_status==='PARTIAL_API'" class="resource-panel">
            <div class="resource-stats"><div><span>CPU 当前使用率</span><strong>{{resourceText(d.asset_id,'cpu')}}</strong><el-button size="small" @click="selectResource(d.asset_id,'cpu')">CPU 历史</el-button></div><div><span>内存使用率</span><strong>{{resourceText(d.asset_id,'memory')}}</strong><el-button size="small" @click="selectResource(d.asset_id,'memory')">内存历史</el-button></div></div>
            <p>{{resourceReason(d.asset_id)}}</p><p v-if="resources[d.asset_id]">采集接收：{{time(resources[d.asset_id]!.data.received_at)}}</p><p v-if="resources[d.asset_id]?.data.tls_mode==='TEST_INSECURE'" class="tls-note">测试模式：已临时跳过证书校验</p>
          </section>
          <p class="sample-time">ICMP记录：{{time(d.metrics.icmp.recorded_at)}}</p>
          <el-button :type="selected===d.asset_id?'primary':'default'" @click="selectDevice(d.asset_id)">查看历史 · {{d.management_ip}}</el-button>
        </article>
      </div>
      <div class="network-table-footer"><span>当前页统计：已显示 {{filteredDevices.length}} 台 / 当前绑定 {{snapshot?.devices.length??'—'}} 台</span><span>仅读取当前租户授权绑定设备</span></div></section>
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
.network-page{max-width:1440px;margin:auto;color:#20334c;min-width:0}
.network-policy{font-size:12px;line-height:1.7;background:#edf5ff;color:#315b87;padding:9px 12px;border:1px solid #d9e7f7;border-radius:6px;margin:0 0 12px}
.network-heading,.history-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px}
.network-heading h1{font-size:22px;margin:0}
.network-heading p,.history-heading p{color:#64748b;margin:4px 0 0;font-size:13px;line-height:1.6}
.boundary-details{margin-top:6px;font-size:12px;color:#64748b}
.boundary-details summary{cursor:pointer;font-weight:500;color:#475569}
.network-device-workspace{padding:16px;margin:16px 0;border:1px solid #dce5ee;border-radius:10px;background:#fff;min-width:0}
.network-list-toolbar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:14px}
.network-view-tabs{display:inline-flex;gap:6px}
.network-view-tabs button{min-height:34px;padding:6px 12px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#334155;font-size:12px;cursor:pointer}
.network-view-tabs button.active{background:#eff6ff;border-color:#3b82f6;color:#1d4ed8;font-weight:500}
.network-list-toolbar input{min-height:34px;padding:6px 12px;border:1px solid #cbd5e1;border-radius:6px;font-size:12px;min-width:220px}
.network-list-toolbar span{font-size:12px;color:#64748b}
.resource-inline-link{display:inline-block;margin:2px 6px 2px 0;padding:3px 8px;border:1px solid #d0dfef;border-radius:4px;background:#f8fbff;color:#1d5fa7;font-size:12px;cursor:pointer}
.network-resource-reason{display:block;font-size:11px;color:#64748b;margin-top:4px}
.tls-note{display:block;font-size:11px;color:#92610c;margin-top:4px}
.network-table-footer{display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px;margin-top:12px;padding-top:10px;border-top:1px solid #edf1f5;font-size:12px;color:#64748b}
.resource-panel{border-top:1px solid #e3eaf2;margin-top:16px;padding-top:16px}
.resource-stats{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.resource-stats span{font-size:12px;color:#64748b;display:block}
.resource-stats strong{display:block;font-size:21px;color:#244260;margin:7px 0 10px}
.resource-panel p{font-size:11px;color:#64748b;line-height:1.6;margin:8px 0 0;overflow-wrap:anywhere}
.resource-panel .tls-note{color:#92610c}
.network-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));align-items:start;gap:18px;margin:20px 0}
.device-card,.network-history{border:1px solid #dce5ee;border-radius:10px;background:white;padding:20px;min-width:0}
.device-card.chosen,.network-device-row.chosen{border-color:#438dcf;background:#f8fbff}
.device-head{display:flex;justify-content:space-between;align-items:center}
.device-head small{color:#7a899b}
.network-badge{display:inline-block;padding:4px 10px;border-radius:20px;background:#fff2dc;color:#8d6222;font-size:12px}
.network-badge.reachable{background:#e9f5ef;color:#24724e}
.device-card h2{font-size:18px;margin:16px 0 8px;overflow-wrap:anywhere}
.device-model,.sample-time{font-size:12px;color:#64748b;overflow-wrap:anywhere}
.device-ip{font-size:20px;font-variant-numeric:tabular-nums;margin:14px 0}
.network-stats{display:grid;grid-template-columns:1fr 1fr;padding:16px 0;border-top:1px solid #edf1f5;border-bottom:1px solid #edf1f5;gap:10px}
.network-stats span{display:block;font-size:12px;color:#718298}
.network-stats strong{display:block;font-size:18px;margin-top:8px;overflow-wrap:anywhere}
.network-ports{display:grid;grid-template-columns:1fr 1fr;row-gap:10px;font-size:13px}
.network-ports dt{color:#73859a}
.network-ports dd{margin:0;text-align:right}
.sample-time{margin:16px 0}
.device-card .el-button{width:100%;font-size:12px}
.network-history h2{font-size:18px;margin:0 0 6px}
.history-controls{display:flex;gap:12px}
.history-controls label{font-size:12px;color:#52667d;display:grid;gap:5px}
.history-controls select{padding:8px;border:1px solid #d4dfe9;border-radius:6px;background:white;color:#244260}
.network-empty{padding:30px;text-align:center;color:#7b8da3;background:#f7f9fc;border-radius:8px;font-size:14px}
.history-chart{width:100%;max-height:260px;background:#f8fbff;border-radius:8px}
.history-caption{color:#6c7e92;font-size:12px;line-height:1.7}
.history-table{overflow:auto}
.history-table table{border-collapse:collapse;width:100%;font-size:13px}
.history-table th,.history-table td{text-align:left;border-bottom:1px solid #edf1f5;padding:9px 12px}
.history-table th{color:#64748b;background:#f7f9fc}
@media(max-width:1350px){.network-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:700px){.network-grid{grid-template-columns:1fr}.network-heading,.history-heading{align-items:flex-start;flex-direction:column}.device-card,.network-history,.network-device-workspace{padding:14px}.network-heading h1{font-size:20px}.history-controls{width:100%}.history-controls label{flex:1}.history-controls select,.network-list-toolbar input{width:100%}.network-view-tabs button,.network-list-toolbar input{min-height:44px}}
</style>
