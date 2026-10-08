<script setup lang="ts">
import { ElTable } from 'element-plus'
defineProps<{ rows: object[]; loading?: boolean; rowKey?: string; emptyText?: string }>()
</script>
<template>
  <div class="table-frame" :aria-busy="loading">
    <el-table v-loading="loading" element-loading-text="正在加载…" :data="rows" :row-key="rowKey || 'id'" :empty-text="loading ? '正在加载…' : emptyText || '暂无符合条件的数据'" table-layout="auto" stripe>
      <slot />
    </el-table>
  </div>
</template>
