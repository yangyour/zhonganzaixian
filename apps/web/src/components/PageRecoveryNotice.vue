<script setup lang="ts">
import { ref } from 'vue'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createPageReload } from '../utils/pageRecovery'
import { contextGeneration } from '../stores/session'
import { resourceLoadFailed, runtimeFailed } from '../stores/navigation'
const busy = ref(false), blocked = ref(false)
const reload = createPageReload({ approve: () => contextTransitionGuards.approve(), generation: () => contextGeneration.value, reload: () => window.location.reload() })
async function refresh() { if (busy.value) return; busy.value = true; blocked.value = false; try { blocked.value = !await reload() } finally { busy.value = false } }
</script>
<template>
  <section v-if="resourceLoadFailed || runtimeFailed" class="page-recovery" role="alert" aria-labelledby="page-recovery-title">
    <h2 id="page-recovery-title">{{ resourceLoadFailed ? '页面资源暂时未能加载' : '页面部分功能出现异常' }}</h2>
    <p>{{ resourceLoadFailed ? '可能是网络波动或系统版本已更新。请检查网络连接，在确认无未保存内容后重新加载页面。' : '请先保留当前输入并核对刚才的操作结果，确认无误后可重新加载页面（刷新页面不会自动重发未确认的请求）。' }}</p>
    <el-button type="primary" :loading="busy" @click="refresh">重新加载页面</el-button>
    <p v-if="blocked" class="recovery-blocked" role="status">当前存在未保存的填写内容或待确认的提交请求，请先处理后再重新加载。</p>
  </section>
</template>
