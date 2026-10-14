<script setup lang="ts">
defineProps<{
  items: { key: string; label: string; value: string }[]
  pending: boolean
  loading: boolean
  failed: boolean
  loaded: boolean
  count: number
}>()
defineEmits<{ remove: [key: string]; clear: [] }>()
</script>

<template>
  <div class="applied-filters">
    <div class="filter-result" role="status" aria-live="polite">
      <span v-if="loading">正在读取已应用条件…</span>
      <span v-else-if="failed" class="filter-failed">查询未完成，请重试</span>
      <span v-else-if="!loaded">列表尚未读取</span>
      <span v-else>本页 <strong>{{ count }}</strong> 条<span v-if="items.length">匹配记录</span><span v-else>记录</span></span>
      <span v-if="pending" class="filter-pending">筛选已修改，点击“查询”后应用</span>
    </div>
    <div v-if="items.length || pending" class="filter-selection" aria-label="已应用筛选条件">
      <span class="filter-caption">已应用</span>
      <span v-if="!items.length" class="filter-unrestricted">全部记录</span>
      <button v-for="item in items" :key="item.key" type="button" class="filter-chip" :disabled="loading" :aria-label="`移除${item.label}筛选：${item.value}`" @click="$emit('remove', item.key)">
        <span>{{ item.label }}：<strong>{{ item.value }}</strong></span><span class="filter-chip-close" aria-hidden="true">×</span>
      </button>
      <button type="button" class="filter-clear" :disabled="loading" @click="$emit('clear')">清空全部</button>
    </div>
  </div>
</template>

<style scoped>
.applied-filters{padding:12px 18px;border-bottom:1px solid #e8eef5;background:#fbfcfe;font-size:12px;color:#65758b}
.filter-result{display:flex;align-items:center;flex-wrap:wrap;gap:7px 16px;line-height:1.6}
.filter-result strong{font-variant-numeric:tabular-nums;font-weight:650;color:#30455f}
.filter-pending{color:#8b641e}.filter-failed{color:#a24537}
.filter-selection{display:flex;flex-wrap:wrap;align-items:center;gap:7px;margin-top:10px;min-width:0}
.filter-caption{font-size:11px;color:#7a8aa0;flex-shrink:0}.filter-unrestricted{color:#61718a}
.applied-filters .filter-chip,#main-content .applied-filters .filter-chip{display:inline-flex;align-items:center;gap:8px;max-width:100%;min-height:30px;padding:4px 9px;border:1px solid #dce7f5;border-radius:6px;background:#edf4fd;color:#3b5d85;text-align:left;line-height:1.5;cursor:pointer;font-size:12px}
.filter-chip>span:first-child{min-width:0;overflow-wrap:anywhere}.filter-chip strong{font-weight:500}.filter-chip-close{font-size:16px;flex-shrink:0;line-height:1}
.applied-filters .filter-clear,#main-content .applied-filters .filter-clear{padding:4px 5px;min-height:30px;border:0;background:transparent;color:#2b68b3;cursor:pointer;white-space:nowrap;font-size:12px}
.applied-filters button:hover:not(:disabled){background:#e2edfd}.applied-filters button:focus-visible{outline:2px solid #377fe5;outline-offset:2px}.applied-filters button:disabled{cursor:wait;opacity:.55}
@media(max-width:760px){.applied-filters{padding:12px}.applied-filters .filter-chip,.applied-filters .filter-clear,#main-content .applied-filters .filter-chip,#main-content .applied-filters .filter-clear{min-height:44px}.filter-result{align-items:flex-start;gap:5px 12px}}
</style>
