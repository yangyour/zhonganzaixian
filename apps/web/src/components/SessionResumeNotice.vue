<script setup lang="ts">
import { resumePhase, resumeSession } from '../stores/pageResume'
</script>
<template>
  <section class="session-resume-panel" aria-labelledby="session-resume-title" aria-live="polite">
    <div>
      <h1 id="session-resume-title">{{ resumePhase === 'failed' ? '暂时无法验证会话状态' : '正在同步会话状态' }}</h1>
      <p>{{ resumePhase === 'failed' ? '请检查网络连接后点击重试。在会话验证完成前，页面内容将暂不展示。' : '正在确认当前账号和租户权限，请稍候…' }}</p>
      <p class="muted">系统不会在后台自动重发未确认的提交请求。</p>
      <el-button v-if="resumePhase === 'failed'" type="primary" @click="resumeSession.retry">重新核对</el-button>
    </div>
  </section>
</template>
