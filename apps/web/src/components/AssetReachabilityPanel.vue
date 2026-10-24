<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {projectReachability,type ReachabilityState} from '../utils/assetReachability'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{state:ReachabilityState;now:number}>();defineEmits<{refresh:[];cancel:[];close:[]}>()
const page=ref(1),filter=ref('ALL')
const view=computed(()=>props.state.current?projectReachability(props.state.current,props.now-props.state.loadedAt):null)
const rows=computed(()=>view.value?.items.filter(row=>filter.value==='ALL'||row.state===filter.value)??[])
const pages=computed(()=>Math.max(1,Math.ceil(rows.value.length/20)))
watch(()=>props.state.current,()=>page.value=1);watch(filter,()=>page.value=1);watch(pages,n=>page.value=Math.min(page.value,n))
const labels={NORMAL:'正常（ICMP可达）',NO_RESPONSE:'无响应',UNKNOWN:'未知'}
const reasons:Record<string,string>={SOURCE_NOT_CONFIGURED:'采样来源未配置',NO_EXPLICIT_BINDING:'资产未绑定采集项',STALE:'超过60秒新鲜度',NO_DATA:'等待采样',COLLECTOR_ERROR:'采集异常',DISABLED:'采集停用',CLOCK_SKEW:'时钟异常',FRESH_ICMP_SAMPLE:'有效ICMP采样'}
</script>
<template><section class="panel" aria-labelledby="asset-reachability-title"><div class="actions"><h2 id="asset-reachability-title">资产在线明细</h2><button :disabled="state.busy" @click="$emit('refresh')">重新读取</button><button v-if="state.busy" @click="$emit('cancel')">停止等待</button><button @click="$emit('close')">关闭明细</button></div><ApiErrorNotice :error="state.error"/><p v-if="state.busy" role="status">正在核对完整台账与采样…</p><p v-else-if="state.cancelled" role="status">已停止等待，可重新读取。</p><template v-else-if="view"><p>全部 {{view.total}} · 正常 {{view.normal}} · 无响应 {{view.no_response}} · 未知 {{view.unknown}}</p><p>仅表示显式绑定的ICMP可达性，不代表设备业务健康。未绑定、停采及超过60秒的样本均计为未知；存在未知时不展示完整在线率。</p><label>状态筛选 <select v-model="filter"><option value="ALL">全部</option><option value="NORMAL">正常</option><option value="NO_RESPONSE">无响应</option><option value="UNKNOWN">未知</option></select></label><table class="admin-table"><thead><tr><th>资产</th><th>状态</th><th>依据</th><th>采样时间</th></tr></thead><tbody><tr v-for="row in rows.slice((page-1)*20,page*20)" :key="row.id"><td><router-link :to="{path:'/assets',query:{asset_id:row.id}}">{{row.name}}</router-link></td><td>{{labels[row.state]}}</td><td>{{reasons[row.reason]}}</td><td>{{displayTime(row.sampled_at)}}</td></tr><tr v-if="!rows.length"><td colspan="4">当前筛选没有资产记录</td></tr></tbody></table><nav aria-label="资产在线明细分页"><button :disabled="page<=1" @click="page--">上一页</button><span>第 {{page}} / {{pages}} 页 · {{rows.length}} 条</span><button :disabled="page>=pages" @click="page++">下一页</button></nav></template></section></template>
