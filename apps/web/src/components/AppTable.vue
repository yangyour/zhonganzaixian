<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { ElButton, ElTable } from 'element-plus'
import { ArrowLeft, ArrowRight, Document, Warning } from '@element-plus/icons-vue'
defineProps<{ rows: object[]; loading?: boolean; rowKey?: string; emptyText?: string; error?: unknown; label?: string }>()
const frame = ref<HTMLElement>()
const frameId = useId()
const overflow = ref(false), atStart = ref(true), atEnd = ref(true)
let viewport: HTMLElement | null = null
let observer: ResizeObserver | undefined
let disposed = false
function measure() {
  if (!viewport) return
  const maximum = Math.max(0, viewport.scrollWidth - viewport.clientWidth)
  overflow.value = viewport.clientWidth > 0 && maximum > 2
  atStart.value = viewport.scrollLeft <= 2
  atEnd.value = viewport.scrollLeft >= maximum - 2
}
function scroll(direction: 'left' | 'right' | 'start' | 'end') {
  if (!viewport) return
  const maximum = Math.max(0, viewport.scrollWidth - viewport.clientWidth)
  const step = Math.max(120, viewport.clientWidth * .7)
  const left = direction === 'start' ? 0 : direction === 'end' ? maximum : viewport.scrollLeft + (direction === 'left' ? -step : step)
  viewport.scrollLeft = Math.min(maximum, Math.max(0, left))
  measure()
}
function onKeydown(event: KeyboardEvent) {
  // Keep links, inputs and other controls inside the table in charge of their own keys.
  if (event.target !== event.currentTarget || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || !overflow.value) return
  const direction = ({ ArrowLeft: 'left', ArrowRight: 'right', Home: 'start', End: 'end' } as const)[event.key as 'ArrowLeft' | 'ArrowRight' | 'Home' | 'End']
  if (direction) { event.preventDefault(); scroll(direction) }
}
onMounted(async () => {
  await nextTick()
  if (disposed) return
  // Element Plus owns horizontal table scrolling; the outer region is the keyboard entry point.
  viewport = frame.value?.querySelector<HTMLElement>('.el-scrollbar__wrap') || null
  if (!viewport) return
  viewport.addEventListener('scroll', measure, { passive: true })
  window.addEventListener('resize', measure)
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(measure)
    observer.observe(viewport)
    const content = viewport.querySelector('.el-scrollbar__view')
    if (content) observer.observe(content)
  }
  measure()
})
onBeforeUnmount(() => { disposed = true; observer?.disconnect(); viewport?.removeEventListener('scroll', measure); window.removeEventListener('resize', measure) })
</script>
<template>
  <div class="table-region">
  <span class="table-loading-status" role="status">{{ loading ? '正在加载…' : '' }}</span>
  <div v-if="overflow" class="table-scroll-tools">
    <span :id="`${frameId}-hint`">{{ atEnd ? '已到最后一列，可向左查看' : '右侧还有更多列，可左右滑动查看' }}<small>聚焦表格后可用 ← → 键</small></span>
    <div class="table-scroll-buttons"><el-button :icon="ArrowLeft" :disabled="atStart" :aria-controls="frameId" aria-label="向左查看表格列" @click="scroll('left')" /><el-button :icon="ArrowRight" :disabled="atEnd" :aria-controls="frameId" aria-label="向右查看表格列" @click="scroll('right')" /></div>
  </div>
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
.table-scroll-tools{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:6px 12px;background:#fff;color:#52667d;font-size:12px;line-height:1.6}
.table-scroll-tools>span{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}
.table-scroll-buttons{display:flex;gap:6px;flex-shrink:0}.table-scroll-buttons .el-button{margin:0;min-width:30px;min-height:28px;height:28px;padding:5px;border-radius:6px}
@media(max-width:760px){.table-scroll-tools{padding:6px 12px}.table-scroll-buttons .el-button{min-width:44px;min-height:44px}}
</style>
