<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
const offline = ref(typeof navigator !== 'undefined' && navigator.onLine === false)
const reconnected = ref(false)
const update = () => {
  const nextOffline = navigator.onLine === false
  if (nextOffline) reconnected.value = false
  else if (offline.value) reconnected.value = true
  offline.value = nextOffline
}
onMounted(() => { window.addEventListener('offline', update); window.addEventListener('online', update) })
onUnmounted(() => { window.removeEventListener('offline', update); window.removeEventListener('online', update) })
</script>
<template>
  <div class="connection-notice" :class="{ reconnected: reconnected && !offline }" role="status" aria-live="polite" aria-atomic="true">
    <p v-if="offline">网络连接已断开。请保留当前填写内容，待网络恢复后先核对提交状态；页面不会自动重发请求。</p>
    <details v-else-if="reconnected" class="connection-recovered" open>
      <summary>网络连接已恢复<span>查看操作建议</span></summary>
      <p>请手动刷新以获取最新数据；若断网前提交过内容，请先核对结果后再决定是否重试，页面不会自动重发业务请求。</p>
    </details>
  </div>
</template>
<style scoped>
.connection-notice:has(p){position:static;margin:0 0 12px;padding:10px 14px;border:1px solid #e7c37c;border-radius:6px;background:#fff4da;color:#6e4815;overflow-wrap:anywhere}
.connection-notice.reconnected:has(p){background:#eff6ff;border-color:#bed3ea;color:#245580}
.connection-recovered summary{cursor:pointer;min-height:32px;padding:4px 0;font-size:14px;line-height:1.7;font-weight:500}
.connection-recovered summary span{margin-left:12px;font-size:12px;font-weight:400;text-decoration:underline;text-underline-offset:3px}
.connection-recovered p{padding-top:6px;margin:0;line-height:1.65;font-size:13px}
@media(max-width:760px){.connection-notice:has(p){padding:10px 12px}.connection-recovered summary{min-height:44px}.connection-recovered summary span{display:block;margin-left:18px}}
</style>
