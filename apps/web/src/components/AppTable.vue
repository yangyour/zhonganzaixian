<script setup lang="ts">
import { ElTable } from 'element-plus'
defineProps<{ rows: object[]; loading?: boolean; rowKey?: string; emptyText?: string; error?: unknown; label?: string }>()
</script>
<template>
  <div class="table-region">
  <span class="table-loading-status" role="status">{{ loading ? '正在加载…' : '' }}</span>
  <div class="table-frame" role="region" :aria-label="label || '数据表格，可横向滚动查看全部列'" tabindex="0" :aria-busy="loading">
    <el-table v-loading="loading" element-loading-text="正在加载…" :data="rows" :row-key="rowKey || 'id'" :empty-text="loading ? ' ' : emptyText || '暂无符合条件的数据'" table-layout="auto" stripe>
      <slot />
      <template #empty><div v-if="!loading" class="table-empty"><span class="empty-symbol" aria-hidden="true">{{ error ? '!' : '—' }}</span><p>{{ error ? '读取未完成，请按上方提示重试' : emptyText || '暂无符合条件的数据' }}</p><slot name="empty-action" /></div></template>
    </el-table>
  </div>
  </div>
</template>
<style scoped>
.table-loading-status{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
