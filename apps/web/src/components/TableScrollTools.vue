<script setup lang="ts">
import { ElButton } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
// `controls` is the id of the scrolling region; the hint id derives from it for aria-describedby.
defineProps<{ controls: string; atStart: boolean; atEnd: boolean }>()
defineEmits<{ step: [direction: 'left' | 'right'] }>()
</script>
<template>
  <div class="table-scroll-tools">
    <span :id="`${controls}-hint`">{{ atEnd ? '已到最后一列，可向左查看' : '右侧还有更多列，可左右滑动查看' }}<small>聚焦表格后可用 ← → 键</small></span>
    <div class="table-scroll-buttons"><el-button :icon="ArrowLeft" :disabled="atStart" :aria-controls="controls" aria-label="向左查看表格列" @click="$emit('step', 'left')" /><el-button :icon="ArrowRight" :disabled="atEnd" :aria-controls="controls" aria-label="向右查看表格列" @click="$emit('step', 'right')" /></div>
  </div>
</template>
<style scoped>
.table-scroll-tools{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:36px;padding:4px 12px 4px 16px;background:var(--mop-sunken,#f7f9fc);border-bottom:1px solid var(--mop-line-soft,#edf1f6);color:var(--mop-muted,#5d6e83);font-size:12px;line-height:1.6}
.table-scroll-tools>span{display:inline-flex;align-items:center;gap:8px;min-width:0}
.table-scroll-tools small{color:var(--mop-faint,#8696aa);font-size:12px}
.table-scroll-buttons{display:flex;gap:4px;flex-shrink:0}.table-scroll-buttons .el-button{margin:0;min-width:28px;min-height:26px;height:26px;padding:4px;border-radius:6px}
@media(max-width:760px){.table-scroll-tools{padding:6px 12px}.table-scroll-tools small{display:none}.table-scroll-buttons .el-button{min-width:44px;min-height:44px}}
</style>
