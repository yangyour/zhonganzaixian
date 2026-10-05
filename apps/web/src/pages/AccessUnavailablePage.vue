<script setup lang="ts">
function refresh() { window.location.reload() }
</script>
<template>
  <section class="access-unavailable" aria-labelledby="access-unavailable-title">
    <h1 id="access-unavailable-title">当前账号暂无可访问的业务页面</h1>
    <p>当前租户没有授予可用的页面读取权限。账号仍可退出；请联系管理员核对成员授权。</p>
    <p>如果权限刚刚调整，可以重新加载以读取当前会话。这里不会自动补齐角色权限或创建业务请求。</p>
    <el-button @click="refresh">重新检查权限</el-button>
  </section>
</template>
<style scoped>
.access-unavailable{max-width:48rem;padding:1.5rem;background:#fff;border:1px solid #dce5ef;border-radius:12px}
p{line-height:1.7;color:#52667f}.access-unavailable :deep(.el-button){min-height:44px}
</style>
