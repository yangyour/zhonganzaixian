<script setup lang="ts">
import { resumePhase, resumeSession } from '../stores/pageResume'
</script>
<template>
  <section class="session-resume-panel" aria-labelledby="session-resume-title" aria-live="polite">
    <div>
      <h1 id="session-resume-title">{{ resumePhase === 'failed' ? '暂时无法确认登录状态' : '正在重新核对登录状态' }}</h1>
      <p>{{ resumePhase === 'failed' ? '请检查网络连接后重新核对。确认前，旧页面内容保持隐藏。' : '正在确认当前账号和租户权限，请稍候…' }}</p>
      <p>尚未确认的提交不会自动重发。</p>
      <el-button v-if="resumePhase === 'failed'" type="primary" @click="resumeSession.retry">重新核对</el-button>
    </div>
  </section>
</template>
