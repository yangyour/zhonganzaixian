<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElInput, type InputInstance } from 'element-plus'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ modelValue: string; minRows?: number; maxRows?: number }>(), { minRows: 6, maxRows: 12 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const input = ref<InputInstance>()
const autosize = computed(() => ({ minRows: props.minRows, maxRows: Math.max(props.minRows, props.maxRows) }))
let observer: ResizeObserver | undefined
let frame: number | undefined

onMounted(() => {
  const textarea = input.value?.textarea
  if (!textarea || typeof ResizeObserver === 'undefined') return
  let width = 0
  // Element Plus handles edits and programmatic values. Re-measure wrapping when
  // a dialog opens or the available width changes, without reacting to height.
  observer = new ResizeObserver(entries => {
    const nextWidth = entries[0]?.contentRect.width ?? 0
    if (nextWidth === width) return
    width = nextWidth
    if (nextWidth <= 0) return
    if (frame !== undefined) cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      frame = undefined
      input.value?.resizeTextarea()
    })
  })
  observer.observe(textarea)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame !== undefined) cancelAnimationFrame(frame)
})
</script>

<template>
  <ElInput ref="input" v-bind="$attrs" class="auto-textarea" :model-value="modelValue" type="textarea" :autosize="autosize" resize="none" @update:model-value="emit('update:modelValue', String($event))" />
</template>

<style scoped>
.auto-textarea :deep(.el-textarea__inner) { overflow-y: auto; }
</style>
