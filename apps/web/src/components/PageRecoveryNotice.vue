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
    <p>{{ resourceLoadFailed ? '可能是网络中断或页面版本已更新。请检查连接，处理当前未保存内容后重新加载。' : '请保留当前输入，先核对刚才的提交结果。处理完当前操作后，可以重新加载页面。' }}</p>
    <p>重新加载不会自动重发业务请求。</p>
    <el-button type="primary" :loading="busy" @click="refresh">重新加载页面</el-button>
    <p v-if="blocked" class="recovery-blocked" role="status">本次未重新加载。请先处理未保存内容或未确认的请求，再重试。</p>
  </section>
</template>
