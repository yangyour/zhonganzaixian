<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { fetchAssetImportTemplate } from '../utils/assetImportTemplate'
import ApiErrorNotice from './ApiErrorNotice.vue'
const permitted = computed(() => can('assets:write') && !switchingTenant.value)
const scope = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value]))
const busy = ref(false), error = ref<unknown>(null), saved = ref(false)
let controller: AbortController | undefined, generation = 0
const urls = new Set<string>()
watch(scope, () => { controller?.abort(); ++generation; busy.value = false; error.value = null; saved.value = false })
async function download() {
  if (!permitted.value || busy.value) return
  controller?.abort(); controller = new AbortController(); const signal = controller.signal, current = ++generation, context = scope.value
  busy.value = true; error.value = null; saved.value = false
  try {
    const result = await fetchAssetImportTemplate({ signal, active: () => current === generation && context === scope.value && permitted.value, onUnauthorized: () => api.onUnauthorized() })
    if (current !== generation || context !== scope.value || signal.aborted) return
    const url = URL.createObjectURL(result.blob); urls.add(url)
    const link = document.createElement('a'); link.href = url; link.download = result.filename; link.style.display = 'none'; document.body.append(link); link.click(); link.remove()
    setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1000)
    saved.value = true
  } catch (cause) { if (current === generation && !isCancelled(cause)) error.value = cause }
  finally { if (current === generation) busy.value = false }
}
onUnmounted(() => { controller?.abort(); ++generation; for (const url of urls) URL.revokeObjectURL(url); urls.clear() })
</script>
<template>
  <section class="asset-template-download">
    <el-button :disabled="!permitted" :loading="busy" @click="download">下载标准15列空模板</el-button>
    <p class="field-help">只有合同表头，没有样例资产或默认业务规则。备注必须填写；下载不会替换当前原文件、自动解析或导入，也不需要OSS连接。</p>
    <p v-if="saved" role="status" class="field-help">模板长度和SHA256已核对，已交给浏览器下载；尚未填写或导入任何资产。</p>
    <ApiErrorNotice :error="error" />
  </section>
</template>
<style>.asset-template-download{margin:12px 0 18px}.asset-template-download .el-button{min-height:40px}@media(max-width:600px){.asset-template-download .el-button{min-height:44px;white-space:normal;height:auto}}</style>
