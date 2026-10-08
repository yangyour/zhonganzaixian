<script setup lang="ts">
import { ElTable } from 'element-plus'
defineProps<{ rows: object[]; loading?: boolean; rowKey?: string; emptyText?: string }>()
</script>
<template>
  <div class="table-region">
  <span class="table-loading-status" role="status">{{ loading ? '正在加载…' : '' }}</span>
  <div class="table-frame" :aria-busy="loading">
    <el-table v-loading="loading" element-loading-text="正在加载…" :data="rows" :row-key="rowKey || 'id'" :empty-text="loading ? ' ' : emptyText || '暂无符合条件的数据'" table-layout="auto" stripe>
      <slot />
    </el-table>
  </div>
  </div>
</template>
<style scoped>
.table-loading-status{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
