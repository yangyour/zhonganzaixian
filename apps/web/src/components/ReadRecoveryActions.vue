<script setup lang="ts">
import {nextTick,onUnmounted,ref,watch} from 'vue'
const props=defineProps<{busy:'read'|'write'|null;canRetry:boolean;scopeKey:string;cancel:()=>boolean;retry:()=>Promise<boolean>}>()
const status=ref(''),notice=ref<HTMLElement|null>(null);let epoch=0,alive=true,retryPending=false
watch(()=>[props.scopeKey,props.busy],([key,busy],[oldKey])=>{if(key!==oldKey||busy==='write'||busy==='read'&&!retryPending){epoch++;status.value='';retryPending=false}},{flush:'sync'})
async function cancel(){if(!alive||props.busy!=='read'||!props.cancel())return;retryPending=false;const turn=++epoch;status.value='已取消读取；原写入请求与未保存输入不变，可手动重试原读取。';await nextTick();if(alive&&turn===epoch)notice.value?.focus()}
async function retry(){if(!alive||props.busy||!props.canRetry||retryPending)return;status.value='';retryPending=true;const turn=++epoch
 try{const ok=await props.retry();if(alive&&turn===epoch&&ok)status.value='原读取已完成，请核对页面结果；读取不确认未知写入，也不代表发布或执行。'}catch{if(alive&&turn===epoch)status.value='读取未完成，请核对页面错误后手动重试。'}finally{if(turn===epoch)retryPending=false}}

onUnmounted(()=>{alive=false;epoch++})
</script>
<template><div v-if="busy==='read'||canRetry||status" class="read-recovery"><button v-if="busy==='read'" type="button" @click="cancel">取消当前读取</button><button v-if="canRetry&&!busy" type="button" @click="retry">重试原读取</button><p v-if="canRetry&&!busy">按原查询条件、页游标或历史版本边界重试，不采用尚未提交的新筛选；不会重发保存操作。</p><p v-if="status" ref="notice" tabindex="-1" role="status">{{status}}</p></div></template>
<style scoped>
.read-recovery{margin:12px 0;min-width:0}.read-recovery button{min-height:44px;padding:8px 12px;border:1px solid #8194ac;border-radius:4px;background:#fff;color:#24384b;font:inherit;cursor:pointer}.read-recovery p{line-height:1.7;overflow-wrap:anywhere}.read-recovery :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
