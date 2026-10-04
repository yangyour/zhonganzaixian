<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { estimateSampleAge, type HttpClockObservation } from '../utils/httpResponseClock'
import { isUtcInstant } from '../utils/utcInstant'
const props = defineProps<{ clock: HttpClockObservation | null; sampledAt: string | null; contextKey: string }>()
const now = ref(performance.now()), invalidated = ref<'VISIBILITY_CHANGED' | 'PAGE_RESTORED' | 'CONTEXT_CHANGED' | null>(null)
let mounted = false, timer: ReturnType<typeof setInterval> | undefined
const visible = () => typeof document !== 'undefined' && document.visibilityState === 'visible'
function stop() { if (timer !== undefined) clearInterval(timer); timer = undefined }
function invalidate(reason: NonNullable<typeof invalidated.value>) { invalidated.value = reason; stop() }
function synchronize() {
  stop(); now.value = performance.now()
  if (mounted && !invalidated.value && visible() && props.clock?.status === 'OBSERVED' && estimateSampleAge(props.clock, props.sampledAt, now.value).status === 'ESTIMATED') timer = setInterval(() => {
    if (!visible()) invalidate('VISIBILITY_CHANGED')
    else now.value = performance.now()
  }, 1000)
}
watch(() => [props.clock, props.contextKey, props.sampledAt] as const, ([clock, key], prior) => {
  if (prior && key !== prior[1] && clock === prior[0]) invalidated.value = 'CONTEXT_CHANGED'
  else if (!visible()) invalidated.value = 'VISIBILITY_CHANGED'
  else if (!prior || clock !== prior[0]) invalidated.value = null
  synchronize()
}, { immediate: true, flush: 'sync' })
const onVisibility = () => invalidate('VISIBILITY_CHANGED')
const onPageShow = (event: PageTransitionEvent) => { if (event.persisted) invalidate('PAGE_RESTORED') }
onMounted(() => { mounted = true; document.addEventListener('visibilitychange', onVisibility); window.addEventListener('pageshow', onPageShow); synchronize() })
onBeforeUnmount(() => { mounted = false; stop(); document.removeEventListener('visibilitychange', onVisibility); window.removeEventListener('pageshow', onPageShow) })
const noClock: HttpClockObservation = { status: 'UNKNOWN', reason: 'NOT_REQUESTED', clock_accuracy_verified: false }
const estimate = computed(() => invalidated.value ? null : estimateSampleAge(props.clock ?? noClock, props.sampledAt, now.value))
const responseDate = computed(() => props.clock?.status === 'OBSERVED' && isUtcInstant(props.clock.date_iso) ? props.clock.date_iso : '未取得可用响应时间')
const sampleDate = computed(() => props.sampledAt === null ? '未取得采样' : isUtcInstant(props.sampledAt) ? props.sampledAt : '采样时间无效')
const ageText = computed(() => {
  if (!estimate.value) return '页面或上下文已变化，请只读刷新后重新估计'
  const value = estimate.value
  if (value.status === 'ESTIMATED') return `约 ${BigInt(value.min_age_ms!) / 1000n}—${(BigInt(value.max_age_ms!) + 999n) / 1000n} 秒（估计范围）`
  return ({ NO_SAMPLE: '无法估计：未取得采样', INVALID_SAMPLE: '无法估计：采样时间无效', CLOCK_UNAVAILABLE: '无法估计：响应时钟不可用', CLOCK_REGRESSED: '页面计时基准已变化，请只读刷新', SAMPLE_AFTER_CLOCK_ESTIMATE: '采样时间晚于响应时钟估计，待核对' } as const)[value.status]
})
</script>
<template>
  <section class="sample-freshness" aria-label="采样与读取时间">
    <h3>采样与读取时间</h3>
    <dl><div><dt>本次响应 Date（UTC，秒级）</dt><dd data-testid="response-date">{{ responseDate }}</dd></div><div><dt>原采样时间（UTC）</dt><dd data-testid="sample-date">{{ sampleDate }}</dd></div><div><dt>样本年龄估计</dt><dd data-testid="sample-age">{{ ageText }}</dd></div></dl>
    <p class="sample-freshness-note">刷新只读取现有记录，不代表新增采样。响应时钟准确性未核验，年龄估计不用于判断设备正常或SLA达标。</p>
  </section>
</template>
<style scoped>
.sample-freshness{margin-top:16px;padding:12px;border:1px solid var(--el-border-color-light);border-radius:8px;background:var(--el-fill-color-light);min-width:0}.sample-freshness h3{font-size:14px;margin:0 0 10px}.sample-freshness dl{margin:0;display:grid;gap:10px}.sample-freshness dt{font-size:12px;color:var(--el-text-color-secondary)}.sample-freshness dd{margin:3px 0 0;font-size:13px;overflow-wrap:anywhere}.sample-freshness-note{font-size:12px;line-height:1.6;color:var(--el-text-color-secondary);margin:12px 0 0}
</style>
