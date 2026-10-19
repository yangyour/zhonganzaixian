<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { session } from '../stores/session'
import { landingRoute } from '../utils/routeAccess'
const router = useRouter()
const home = computed(() => landingRoute(session.value, router.getRoutes()))
</script>
<template>
  <section class="not-found panel">
    <span class="empty-symbol" aria-hidden="true">404</span>
    <h1>页面不存在或无法访问</h1>
    <p>您访问的地址可能已变更，请通过顶部导航切换模块，或返回当前账号的默认工作台。</p>
    <el-button type="primary" @click="router.push({ name: home })">返回首页</el-button>
  </section>
</template>
<style scoped>
.not-found{max-width:42rem;margin:32px auto;padding:36px 28px;text-align:center}
.empty-symbol{display:inline-flex;align-items:center;justify-content:center;padding:4px 14px;border-radius:999px;background:#eef5ff;color:#2563eb;font-size:14px;font-weight:700;letter-spacing:.08em}
.not-found h1{margin:14px 0 8px;font-size:20px;color:#17375e}
.not-found p{margin:0 0 18px;color:#52667f;line-height:1.7;font-size:14px}
</style>
