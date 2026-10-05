<script setup lang="ts">
import {computed} from 'vue'
import type {Asset} from '../api/types'
import {assetLedgerCell} from '../utils/assetLedgerColumns'
const props=defineProps<{asset:Asset}>()
const availability=computed(()=>assetLedgerCell(props.asset,'availability'))
const sync=computed(()=>assetLedgerCell(props.asset,'sync'))
</script>
<template>
 <section class="asset-state-facts" aria-label="独立可用性与配置同步状态">
  <dl><div><dt>可用性</dt><dd data-state="availability">{{availability.text}}</dd><dd v-if="availability.detail" class="state-detail">{{availability.detail}}</dd></div><div><dt>配置同步状态</dt><dd data-state="sync">{{sync.text}}</dd><dd v-if="sync.detail" class="state-detail">{{sync.detail}}</dd></div></dl>
  <p>两种状态分别来自当前响应，与台账列保持一致；不根据汇总状态、生命周期或更新时间推断监控正常</p>
 </section>
</template>
<style scoped>
.asset-state-facts{margin:12px 0 16px;border:1px solid #d8e1eb;border-radius:8px;padding:14px;background:#f8fafc;color:#344c63}.asset-state-facts dl{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:0}.asset-state-facts dt{font-size:12px;color:#65798e;margin-bottom:8px}.asset-state-facts dd{margin:0;line-height:1.6;font-weight:600;overflow-wrap:anywhere}.asset-state-facts .state-detail{font-size:12px;font-weight:400;color:#65798e}.asset-state-facts p{margin:14px 0 0;font-size:12px;line-height:1.7;color:#65798e}@media(max-width:600px){.asset-state-facts dl{grid-template-columns:1fr}}
</style>
