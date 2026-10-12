<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {api,isCancelled} from '../api/client'
import {can,session,switchingTenant,contextGeneration} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {historySegments,readHistory,readSnapshot,sampleText,type Metric,type NetworkHistory,type NetworkSnapshot} from '../utils/networkMonitoring'
const snapshot=ref<NetworkSnapshot|null>(null),history=ref<NetworkHistory|null>(null)
const error=ref<unknown>(null),historyError=ref<unknown>(null),loading=ref(false),historyLoading=ref(false)
const selected=ref(''),metric=ref<Metric>('latency'),window=ref<'HOUR_1'|'HOURS_24'>('HOUR_1')
const now=ref(performance.now()),loadedAt=ref(0)
const expired=computed(()=>!!snapshot.value&&now.value-loadedAt.value>90000)
const permitted=computed(()=>!switchingTenant.value&&can('assets:read'))
const segments=computed(()=>history.value?historySegments(history.value):[])
let alive=true,sequence=0,detailSequence=0,controller:AbortController|null=null,detailController:AbortController|null=null,timer:ReturnType<typeof setInterval>|null=null,ticks=0
function reset(){sequence++;detailSequence++;controller?.abort();detailController?.abort();snapshot.value=null;history.value=null;error.value=null;historyError.value=null;selected.value='';loading.value=false;historyLoading.value=false}
async function refresh(){
  if(!permitted.value||loading.value)return
  const tenant=session.value!.tenant.id,seq=++sequence;controller?.abort();controller=new AbortController();loading.value=true;error.value=null
  try{const value=readSnapshot(await api.request<NetworkSnapshot>('/network-monitoring',{signal:controller.signal}),tenant)
    if(!alive||seq!==sequence||tenant!==session.value?.tenant.id)return
    snapshot.value=value;loadedAt.value=performance.now();now.value=loadedAt.value
    if(selected.value&&!value.devices.some(d=>d.asset_id===selected.value)){selected.value='';history.value=null}
    if(selected.value)void loadHistory()
  }catch(cause){if(alive&&seq===sequence&&!isCancelled(cause)){snapshot.value=null;history.value=null;detailSequence++;detailController?.abort();historyLoading.value=false;error.value=cause}}
  finally{if(alive&&seq===sequence)loading.value=false}
}
async function loadHistory(){
  detailSequence++;detailController?.abort();history.value=null;historyError.value=null;historyLoading.value=false
  if(!permitted.value||!selected.value)return
  const seq=detailSequence,id=selected.value,m=metric.value,w=window.value
  detailController=new AbortController();historyLoading.value=true
  try{const data=readHistory(await api.request<NetworkHistory>(`/network-monitoring/${encodeURIComponent(id)}/history?metric=${m}&window=${w}`,{signal:detailController.signal}),id,m,w)
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
</script>
<template>
  <section class="network-page">
    <header class="network-heading"><div><p class="eyebrow">NETWORK MONITORING</p><h1>基础网络监控</h1><p>持续查看设备连通性、延迟、丢包和端口状态</p></div><el-button :loading="loading" :disabled="!permitted" @click="refresh">刷新监控</el-button></header>
    <el-alert v-if="!permitted" type="warning" :closable="false" title="当前没有资产读取权限" />
    <template v-else>
      <div class="network-context"><span>每 60 秒采集 · 页面每 30 秒刷新</span><span>设备资源指标待文档接入 · 告警规则待确认</span></div>
      <ApiErrorNotice v-if="error" :error="error" @retry="refresh" />
      <el-alert v-if="expired" title="页面数据已过期，请刷新后查看最新状态" type="warning" :closable="false" />
      <p v-if="loading&&!snapshot" role="status">正在读取监控数据…</p>
      <p v-if="snapshot&&!snapshot.devices.length" class="network-empty">当前租户尚未绑定基础监控设备。</p>
      <div v-if="snapshot" class="network-grid">
        <article v-for="d in snapshot.devices" :key="d.asset_id" class="device-card" :class="{chosen:selected===d.asset_id}">
          <div class="device-head"><span class="network-badge" :class="{reachable:display(d,'icmp')==='可达'}">{{display(d,'icmp')}}</span><small>IP 基础探测</small></div>
          <h2>{{d.name}}</h2><p class="device-model">{{d.brand}} {{d.model}}</p><p class="device-ip">{{d.management_ip}}</p>
          <div class="network-stats"><div><span>平均响应</span><strong>{{display(d,'latency')}}</strong></div><div><span>本次丢包</span><strong>{{display(d,'loss')}}</strong></div></div>
          <dl class="network-ports"><template v-for="p in [22,80,443]" :key="p"><dt>TCP {{p}}</dt><dd>{{display(d,`tcp_${p}` as Metric)}}</dd></template></dl>
          <p class="sample-time">ICMP记录：{{time(d.metrics.icmp.recorded_at)}}</p>
          <el-button :type="selected===d.asset_id?'primary':'default'" @click="selected=d.asset_id">查看历史 · {{d.management_ip}}</el-button>
        </article>
      </div>
      <section class="network-history">
        <div class="history-heading"><div><h2>采样历史</h2><p>选择设备后查看。缺失采样会留空，不补零、不延续旧值。</p></div><div class="history-controls"><label>指标<select v-model="metric"><option value="latency">响应延迟</option><option value="loss">丢包率</option><option value="icmp">ICMP响应</option><option value="tcp_22">TCP 22</option><option value="tcp_80">TCP 80</option><option value="tcp_443">TCP 443</option></select></label><label>时间<select v-model="window"><option value="HOUR_1">最近1小时</option><option value="HOURS_24">最近24小时</option></select></label></div></div>
        <p v-if="!selected" class="network-empty">请选择上方一台设备查看历史。</p>
        <p v-if="historyLoading" role="status">正在读取历史采样…</p>
        <ApiErrorNotice v-if="historyError" :error="historyError" @retry="loadHistory" />
        <template v-if="history">
          <p class="history-caption">{{history.points.length}} 个实际采样点 · {{time(history.from)}} — {{time(history.to)}} · 原始单位 {{history.unit||'0 / 1'}}</p>
          <p v-if="!history.points.length" class="network-empty">该时间窗口尚无采样记录。</p>
          <svg v-else class="history-chart" viewBox="0 0 720 180" role="img" :aria-label="`${metric}历史采样，空窗不连线`"><path d="M16 20V154H704" stroke="#cedbe8" fill="none"/><g v-for="(segment,i) in segments" :key="i"><polyline :points="segment.line" fill="none" stroke="#1667b9" stroke-width="2"/><circle v-for="(p,j) in segment.dots" :key="j" :cx="p.x" :cy="p.y" r="2.5" fill="#1667b9"/></g></svg>
          <p v-if="metric==='latency'" class="history-caption">延迟为回应包的平均值；无回应返回的零值不画成正常延迟，请结合 ICMP 响应和丢包查看。</p>
          <div class="history-table"><table><thead><tr><th>最近采样时间</th><th>原始值 {{history.unit}}</th></tr></thead><tbody><tr v-for="p in history.points.slice(-12).reverse()" :key="p.recorded_at"><td>{{time(p.recorded_at)}}</td><td>{{p.value}}</td></tr></tbody></table></div>
        </template>
      </section>
    </template>
  </section>
</template>
<style scoped>
.network-page{max-width:1440px;margin:auto;color:#20334c}.network-heading,.history-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:22px}.network-heading h1{font-size:28px;margin:5px 0 8px}.network-heading p,.history-heading p{color:#64748b;margin:0;font-size:14px}.network-heading .eyebrow{font-size:11px;letter-spacing:2px;color:#3972a6}.network-context{display:flex;justify-content:space-between;flex-wrap:wrap;gap:10px;background:#edf4fb;padding:12px 16px;border-radius:10px;margin:18px 0;font-size:13px;color:#486580}.network-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin:20px 0}.device-card,.network-history{border:1px solid #dce5ee;border-radius:14px;background:white;padding:22px;min-width:0}.device-card.chosen{border-color:#438dcf;box-shadow:0 0 0 1px #438dcf}.device-head{display:flex;justify-content:space-between;align-items:center}.device-head small{color:#7a899b}.network-badge{padding:5px 10px;border-radius:20px;background:#fff2dc;color:#8d6222;font-size:12px}.network-badge.reachable{background:#e9f5ef;color:#24724e}.device-card h2{font-size:18px;margin:20px 0 8px;overflow-wrap:anywhere}.device-model,.sample-time{font-size:12px;color:#64748b;overflow-wrap:anywhere}.device-ip{font-size:20px;font-variant-numeric:tabular-nums;margin:14px 0}.network-stats{display:grid;grid-template-columns:1fr 1fr;padding:16px 0;border-top:1px solid #edf1f5;border-bottom:1px solid #edf1f5;gap:10px}.network-stats span{display:block;font-size:12px;color:#718298}.network-stats strong{display:block;font-size:18px;margin-top:8px;overflow-wrap:anywhere}.network-ports{display:grid;grid-template-columns:1fr 1fr;row-gap:10px;font-size:13px}.network-ports dt{color:#73859a}.network-ports dd{margin:0;text-align:right}.sample-time{margin:18px 0}.device-card .el-button{width:100%;font-size:12px}.network-history h2{font-size:19px;margin:0 0 8px}.history-controls{display:flex;gap:12px}.history-controls label{font-size:12px;color:#52667d;display:grid;gap:5px}.history-controls select{padding:8px;border:1px solid #d4dfe9;border-radius:6px;background:white;color:#244260}.network-empty{padding:30px;text-align:center;color:#7b8da3;background:#f7f9fc;border-radius:8px;font-size:14px}.history-chart{width:100%;max-height:260px;background:#f8fbff;border-radius:8px}.history-caption{color:#6c7e92;font-size:12px;line-height:1.7}.history-table{overflow:auto}.history-table table{border-collapse:collapse;width:100%;font-size:13px}.history-table th,.history-table td{text-align:left;border-bottom:1px solid #edf1f5;padding:9px 12px}.history-table th{color:#64748b;background:#f7f9fc}@media(max-width:1100px){.network-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:700px){.network-grid{grid-template-columns:1fr}.network-heading,.history-heading{align-items:flex-start;flex-direction:column}.device-card,.network-history{padding:16px}.network-heading h1{font-size:24px}.history-controls{width:100%}.history-controls label{flex:1}.history-controls select{width:100%}}
</style>
