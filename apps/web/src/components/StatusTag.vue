<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ status?: string | null }>()
const states: Record<string, { label: string; type: 'info' | 'success' | 'danger' | 'warning' | 'primary' }> = {
  unknown: { label: '未知', type: 'info' }, ok: { label: '正常', type: 'success' },
  problem: { label: '有问题', type: 'danger' }, maintenance: { label: '维护中', type: 'warning' },
  disabled: { label: '已停用', type: 'info' },
}
const state = computed(() => states[props.status || 'unknown'] || states.unknown!)
</script>
<template><el-tag :type="state.type" effect="light" class="status-tag"><span class="status-dot" />{{ state.label }}</el-tag></template>
