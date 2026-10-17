<script setup lang="ts">
function refresh() { window.location.reload() }
</script>
<template>
  <section class="access-unavailable" aria-labelledby="access-unavailable-title">
    <h1 id="access-unavailable-title">当前账号暂无可访问的业务页面</h1>
    <p>当前租户尚未为您的账号分配可用的业务模块访问权限。您可切换至其他已授权租户、退出登录，或联系平台管理员配置成员角色授权。</p>
    <p>若管理员刚刚完成权限调整，请点击下方按钮刷新当前会话权限状态。</p>
    <el-button @click="refresh">重新检查权限</el-button>
  </section>
</template>
<style scoped>
.access-unavailable{max-width:48rem;padding:1.5rem;background:#fff;border:1px solid #dce5ef;border-radius:12px}
p{line-height:1.7;color:#52667f}.access-unavailable :deep(.el-button){min-height:44px}
</style>
