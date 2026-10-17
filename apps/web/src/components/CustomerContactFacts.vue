<script setup lang="ts">
import type { ContactFacts } from '../utils/customerContactWorkspace'
import { contactRoleLabels, contactQuietLabel, contactPrioritiesLabel } from '../utils/customerContactPresentation'
defineProps<{ facts: ContactFacts }>()
</script>
<template>
 <dl class="contact-facts">
  <dt>姓名</dt><dd class="verbatim">{{ facts.name }}</dd>
  <dt>联系角色</dt><dd>{{ facts.roles.map(r => contactRoleLabels[r]).join('、') }}</dd>
  <dt>国际手机号</dt><dd>{{ facts.mobile ?? '未提供' }}</dd>
  <dt>电子邮箱</dt><dd>{{ facts.email ?? '未提供' }}</dd>
  <dt>企业微信 UserID</dt><dd class="verbatim">{{ facts.wecom_user_id ?? '未提供' }}</dd>
  <dt>订阅级别偏好</dt><dd>{{ contactPrioritiesLabel(facts.preferred_priorities) }}</dd>
  <dt>免打扰偏好</dt><dd>{{ contactQuietLabel(facts.quiet_window) }}</dd>
  <dt>启用状态</dt><dd>{{ facts.enabled ? '已启用（通知路由待关联）' : '停用' }}</dd>
 </dl>
</template>
<style scoped>
.contact-facts{display:grid;grid-template-columns:minmax(110px,1fr) minmax(0,3fr);gap:8px 16px;margin:12px 0}.contact-facts dt{color:#526176}.contact-facts dd{margin:0;overflow-wrap:anywhere}.verbatim{white-space:pre-wrap}@media(max-width:700px){.contact-facts{grid-template-columns:1fr;gap:4px}.contact-facts dd{margin-bottom:8px}}
</style>
