<script setup lang="ts">
import {computed,nextTick,ref,useId,watch} from 'vue'
import type {NetworkHistory,ResourceHistory,HistoryMetric} from '../utils/networkMonitoring'
import {displayTime} from '../utils/display'
const props=defineProps<{history:NetworkHistory|ResourceHistory}>()
const page=ref(0),region=ref<HTMLElement|null>(null),statusId=useId(),pageSize=20
const labels:Record<HistoryMetric,string>={latency:'响应延迟',loss:'丢包率',icmp:'ICMP 响应',tcp_22:'TCP 22',tcp_80:'TCP 80',tcp_443:'TCP 443',cpu:'CPU 使用率',memory:'内存使用率'}
const label=computed(()=>labels[props.history.metric]),pages=computed(()=>Math.max(1,Math.ceil(props.history.points.length/pageSize))),start=computed(()=>page.value*pageSize)
const rows=computed(()=>props.history.points.slice(start.value,start.value+pageSize).map((point,index)=>{const absolute=start.value+index,previous=props.history.points[absolute-1];return {...point,number:absolute+1,gap:previous!==undefined&&Date.parse(point.recorded_at)-Date.parse(previous.recorded_at)>props.history.gap_after_seconds*1000}}))
watch(()=>[props.history,props.history.points.length],()=>{page.value=0},{flush:'sync'})
async function go(target:number){if(!Number.isInteger(target)||target<0||target>=pages.value||target===page.value)return;page.value=target;await nextTick();region.value?.focus()}
</script>
<template>
<details class="sample-details">
 <summary>查看{{label}}采样数值（{{history.points.length}} 条）</summary>
 <p>设备引用：{{history.asset_id}}</p>
 <p>时间来源：{{history.timestamp_meaning==='COLLECTOR_RECEIPT_TIME'?'采集端接收时刻':'Zabbix 记录时刻'}}。数值保留接口精度；仅列已返回采样，不补零、不声明窗口完整。阅读时可使用页面的“暂停自动刷新”。</p>
 <p :id="statusId" role="status">{{history.points.length?`第 ${page+1} / ${pages} 页，显示 ${start+1}—${start+rows.length} / ${history.points.length} 条`:'当前返回没有采样点，不表示设备正常或一直没有采样。'}} · 每页最多20条</p>
 <div ref="region" tabindex="0" role="region" :aria-label="`${label}采样数值，可横向滚动`" :aria-describedby="statusId" class="sample-scroll">
  <table><caption>{{label}} · 已返回采样明细</caption><thead><tr><th scope="col">序号</th><th scope="col">时间（北京时间）</th><th scope="col">原始 UTC 时间</th><th scope="col">数值</th><th scope="col">单位</th><th scope="col">采样间隔提示</th></tr></thead><tbody>
   <tr v-for="point in rows" :key="point.recorded_at"><th scope="row">{{point.number}}</th><td>{{displayTime(point.recorded_at)}}</td><td><time :datetime="point.recorded_at">{{point.recorded_at}}</time></td><td class="sample-value">{{point.value}}</td><td>{{history.unit||'状态值（0/1）'}}</td><td>{{point.gap?`与前一点间隔超过 ${history.gap_after_seconds} 秒，期间未补值`:point.number===1?'首个返回点':'未超过间隔阈值'}}</td></tr>
   <tr v-if="!rows.length"><td colspan="6">当前返回没有采样明细。</td></tr>
  </tbody></table>
 </div>
 <nav v-if="pages>1" :aria-label="`${label}采样本地分页`"><button type="button" :disabled="page===0" @click="go(0)">采样首页</button><button type="button" :disabled="page===0" @click="go(page-1)">上一页采样</button><button type="button" :disabled="page+1>=pages" @click="go(page+1)">下一页采样</button><button type="button" :disabled="page+1>=pages" @click="go(pages-1)">采样末页</button></nav>
</details>
</template>
<style scoped>
.sample-details{margin:16px 0;padding:12px;border:1px solid #dce4ee;border-radius:6px;min-width:0;color:#24374a}.sample-details summary{min-height:44px;box-sizing:border-box;padding:10px 0;font-weight:600;cursor:pointer;overflow-wrap:anywhere}.sample-details p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.sample-scroll{overflow:auto;max-width:100%;border:1px solid #dce4ee}.sample-details table{border-collapse:collapse;width:100%;min-width:720px}.sample-details caption{padding:10px;text-align:left;font-weight:600}.sample-details th,.sample-details td{padding:10px;border-bottom:1px solid #cbd5e1;text-align:left;vertical-align:top;overflow-wrap:anywhere;font-size:13px;line-height:1.6}.sample-details thead{background:#eef4fa}.sample-details time,.sample-value{font-family:monospace;font-variant-numeric:tabular-nums}.sample-details nav{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.sample-details button{min-height:44px;padding:8px 12px;background:white;color:#24374a;border:1px solid #dce4ee;border-radius:4px;font:inherit;cursor:pointer}.sample-details button:disabled{opacity:.6;cursor:default}.sample-details :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
