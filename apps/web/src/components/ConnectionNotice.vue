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
    <p v-if="offline">浏览器提示当前离线。请保留当前输入，连接恢复后先核对提交结果；页面不会自动重发。</p>
    <details v-else-if="reconnected" class="connection-recovered" open>
      <summary>浏览器已恢复联网<span>查看后续操作</span></summary>
      <p>如需最新数据，请手动重新读取；如果断网前提交过内容，请先核对结果。联网提示不代表服务已恢复，页面不会自动重发业务请求。</p>
    </details>
  </div>
</template>
<style scoped>
.connection-notice:has(p){position:static;overflow-wrap:anywhere}
.connection-notice.reconnected:has(p){background:#eff6ff;border-color:#bed3ea;color:#245580}
.connection-recovered summary{cursor:pointer;min-height:32px;padding:4px 0;font-size:14px;line-height:1.7}
.connection-recovered summary span{margin-left:12px;font-size:12px;text-decoration:underline;text-underline-offset:3px}
.connection-recovered p{padding-top:6px}
@media(max-width:760px){.connection-notice:has(p){padding:10px 12px}.connection-recovered summary{min-height:44px}.connection-recovered summary span{display:block;margin-left:18px}}
</style>
