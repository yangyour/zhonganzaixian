<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
const offline = ref(typeof navigator !== 'undefined' && navigator.onLine === false)
const update = () => { offline.value = navigator.onLine === false }
onMounted(() => { window.addEventListener('offline', update); window.addEventListener('online', update) })
onUnmounted(() => { window.removeEventListener('offline', update); window.removeEventListener('online', update) })
</script>
<template>
  <div class="connection-notice" role="status" aria-live="polite" aria-atomic="true">
    <p v-if="offline">网络连接已断开。请保留当前输入，连接恢复后先核对提交结果；页面不会自动重发。</p>
  </div>
</template>
