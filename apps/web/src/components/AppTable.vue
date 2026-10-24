<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { ElTable } from 'element-plus'
import { Document, Warning } from '@element-plus/icons-vue'
import TableScrollTools from './TableScrollTools.vue'
import { useHorizontalScroll } from '../utils/useHorizontalScroll'
defineProps<{ rows: object[]; loading?: boolean; rowKey?: string; emptyText?: string; error?: unknown; label?: string }>()
const frame = ref<HTMLElement>()
const frameId = useId()
// Element Plus owns horizontal table scrolling; the outer region is the keyboard entry point.
const viewport = ref<HTMLElement | null>(null)
const { overflow, atStart, atEnd, scroll, onKeydown } = useHorizontalScroll(viewport, element => element.querySelector('.el-scrollbar__view'))
let disposed = false
onMounted(async () => {
  await nextTick()
  if (!disposed) viewport.value = frame.value?.querySelector<HTMLElement>('.el-scrollbar__wrap') || null
})
onBeforeUnmount(() => { disposed = true })
</script>
<template>
  <div class="table-region">
  <span class="table-loading-status" role="status">{{ loading ? '正在加载…' : '' }}</span>
  <TableScrollTools v-if="overflow" :controls="frameId" :at-start="atStart" :at-end="atEnd" @step="scroll" />
  <div :id="frameId" ref="frame" class="table-frame" role="region" :aria-label="label || '数据表格，可横向滚动查看全部列'" :aria-describedby="overflow ? `${frameId}-hint` : undefined" tabindex="0" :aria-busy="loading" @keydown="onKeydown">
    <el-table v-loading="loading" element-loading-text="正在加载…" :data="rows" :row-key="rowKey || 'id'" :empty-text="loading ? ' ' : emptyText || '暂无符合条件的数据'" table-layout="auto" stripe>
      <slot />
      <template #empty><div v-if="!loading" class="table-empty"><span class="empty-symbol" aria-hidden="true"><component :is="error ? Warning : Document" /></span><p>{{ error ? '读取未完成，请按上方提示重试' : emptyText || '暂无符合条件的数据' }}</p><slot name="empty-action" /></div></template>
    </el-table>
  </div>
  </div>
</template>
<style scoped>
.table-loading-status{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
