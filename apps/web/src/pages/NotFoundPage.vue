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
    <span class="empty-symbol" aria-hidden="true">?</span>
    <h1>找不到这个页面</h1>
    <p>页面地址可能已更改，请通过顶部菜单选择功能，或返回当前账号可访问的页面。</p>
    <el-button type="primary" @click="router.push({ name: home })">返回可用页面</el-button>
  </section>
</template>
