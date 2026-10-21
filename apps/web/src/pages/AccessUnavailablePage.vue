<script setup lang="ts">
function refresh() { window.location.reload() }
</script>
<template>
  <section class="access-unavailable" aria-labelledby="access-unavailable-title">
    <h1 id="access-unavailable-title">当前账号暂无可访问的业务页面</h1>
    <p>当前租户尚未为您的账号分配可用的业务模块访问权限。您可切换至其他已授权租户、退出登录，或联系平台管理员配置成员角色授权。</p>
    <p>若管理员已完成权限调整，请点击下方按钮刷新当前会话权限状态。</p>
    <el-button type="primary" @click="refresh">重新检查权限</el-button>
  </section>
</template>
<style scoped>
.access-unavailable{max-width:48rem;margin:24px auto;padding:24px 28px;background:#fff;border:1px solid #dce5ef;border-radius:12px;box-shadow:0 4px 16px rgba(20,44,74,.04)}
.access-unavailable h1{margin:0 0 12px;font-size:20px;color:#17375e}
p{margin:8px 0;line-height:1.7;color:#52667f;font-size:14px}
.access-unavailable :deep(.el-button){margin-top:8px;min-height:40px}
</style>
