<script setup lang="ts">
import { computed } from 'vue'
import type {ServiceState,ServiceSpan} from '../utils/serviceObservations'
import {displayTime,clockTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{state:ServiceState;mode:'health'|'timeline';permitted:boolean}>()
defineEmits<{refresh:[];cancel:[]}>()
const labels={UNKNOWN:'未知',NOT_MET:'未达标',WARNING:'需关注',MET:'达标'}
const kinds={FAULT:'故障',MAINTENANCE:'批准维护',UNKNOWN:'数据缺失·记为未知',AVAILABLE:'已观测正常'}
const services=computed(()=>props.state.current?.services??[])
const ticks=computed(()=>{const s=props.state.current;if(!s)return [];const start=Date.parse(s.timeline_start);return [0,1,2,3,4].map(step=>({offset:step*25,label:clockTime(start+step*21600000)}))})
function geometry(span:ServiceSpan){const start=Date.parse(props.state.current!.timeline_start);return {left:(Date.parse(span.start)-start)/864000+'%',width:(Date.parse(span.end)-Date.parse(span.start))/864000+'%'}}
function intervalTitle(span:ServiceSpan){return `${kinds[span.kind]}${span.severity?' · '+span.severity:''}：${displayTime(span.start)} 至 ${displayTime(span.end)}`}
function duration(value:number){return (value/60000).toLocaleString('zh-CN',{maximumFractionDigits:2})+' 分钟'}
</script>
<template>
 <div class="service-observations">
  <p v-if="!permitted" class="mop-empty">当前账号没有服务观测读取权限。</p>
  <template v-else>
   <div v-if="mode==='health'" class="service-actions"><el-button size="small" :disabled="state.busy" @click="$emit('refresh')">刷新</el-button><el-button v-if="state.busy" size="small" @click="$emit('cancel')">停止等待</el-button><span v-if="state.current">截至 {{ clockTime(Date.parse(state.current.observed_at)) }}</span></div>
   <ApiErrorNotice v-if="state.error" :error="state.error" />
   <p v-else-if="state.busy" role="status" class="mop-empty">正在读取服务观测…</p>
   <p v-else-if="state.cancelled" role="status" class="mop-empty">已停止等待，可重新读取。</p>
   <p v-else-if="!state.current" class="mop-empty">尚未读取服务观测。</p>
   <template v-else-if="mode==='health'">
    <article v-for="service in services" :key="service.id" class="service-row">
     <div class="service-line"><strong>{{ service.name }}</strong><span class="mop-badge" :class="service.health_state==='MET'?'ok':service.health_state==='NOT_MET'?'danger':'unknown'">{{ labels[service.health_state] }}</span></div>
     <div class="service-line"><span>本月可用率 {{ service.verified_availability_pct===null?'未知':service.verified_availability_pct.toFixed(4)+'%' }}</span><span>目标 {{ service.target_pct===null?'未配置':service.target_pct+'%' }}</span></div>
     <details><summary>{{ service.assets.length }} 个设备 · {{ service.active_problems }} 个当前问题</summary><p>已知故障 {{ duration(service.unavailable_millis) }} · 未知 {{ duration(service.unknown_millis) }}</p><p>{{ service.failure_mode==='ANY_ASSET_UNAVAILABLE'?'任一设备故障即服务不可用':'全部设备故障才服务不可用' }}</p><ul><li v-for="asset in service.assets" :key="asset.id"><router-link :to="{path:'/assets',query:{asset_id:asset.id}}">{{ asset.name }}</router-link></li></ul></details>
    </article>
   </template>
   <template v-else>
    <div v-for="service in services.filter(item=>item.timeline_included)" :key="service.id" class="service-timeline-row">
     <strong>{{ service.name }}</strong>
     <div class="service-track" :aria-label="service.name+' 故障与维护区间'"><span v-for="span in service.timeline.filter(item=>item.kind!=='UNKNOWN')" :key="span.start" class="service-span" :class="[span.kind.toLowerCase(),span.severity?.toLowerCase()]" :style="geometry(span)" :title="intervalTitle(span)" /></div>
     <div class="service-track unknown-track" :aria-label="service.name+' 未知区间'"><span v-for="span in service.timeline.filter(item=>item.kind==='UNKNOWN')" :key="span.start" class="service-span unknown" :style="geometry(span)" :title="intervalTitle(span)" /></div>
     <details><summary>区间明细</summary><ul><li v-for="span in service.timeline" :key="span.start">{{ intervalTitle(span) }}</li></ul></details>
    </div>
    <div class="service-axis" aria-hidden="true"><span v-for="tick in ticks" :key="tick.offset" :style="{left:tick.offset+'%'}">{{ tick.label }}</span></div>
    <p v-if="services.length>8" class="service-note">时间线显示前 8 个服务，服务健康列表保留全部 {{ services.length }} 个服务。</p>
   </template>
  </template>
 </div>
</template>
<style scoped>
.service-actions,.service-line{display:flex;align-items:center;justify-content:space-between;gap:8px}.service-actions{justify-content:flex-start;margin-bottom:8px}.service-actions span,.service-note{font-size:12px;color:var(--text-muted)}.service-row{padding:8px 0;border-bottom:1px solid var(--border-color)}.service-line{margin-bottom:5px}.service-row details,.service-timeline-row details{font-size:12px}.service-row summary,.service-timeline-row summary{cursor:pointer}.service-row ul,.service-timeline-row ul{padding-left:18px}.service-row p{margin:5px 0}.service-timeline-row{margin:8px 0}.service-timeline-row>strong{font-size:13px}.service-track{position:relative;height:13px;background:var(--bg-muted);margin-top:4px;overflow:hidden;border-radius:3px}.unknown-track{height:7px}.service-span{position:absolute;height:100%;min-width:1px}.fault{background:var(--color-danger,#e05252)}.fault.warning,.fault.average{opacity:.7}.fault.info{opacity:.45}.maintenance{background:var(--color-warning,#d2a349)}.available{background:var(--color-success,#44a87e)}.service-span.unknown{background:repeating-linear-gradient(135deg,#a0a5b0 0 3px,transparent 3px 6px)}.service-axis{position:relative;height:22px;margin:10px 22px 0;font-size:11px}.service-axis span{position:absolute;transform:translateX(-50%)}
</style>
