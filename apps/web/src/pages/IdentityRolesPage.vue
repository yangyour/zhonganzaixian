<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, session, contextGeneration, switchingTenant } from '../stores/session'
import { baseRoleLabels, readRolePage, type BaseRole, type ProjectRole, type RolePage } from '../utils/projectIdentity'
import { useIdentityEditor } from '../utils/useIdentityEditor'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import IdentityCommandNotice from '../components/IdentityCommandNotice.vue'
const data = ref<RolePage | null>(null), error = ref<unknown>(null), loading = ref(false), selected = ref<ProjectRole | null>(null), opened = ref(false)
let controller: AbortController | null = null, readGeneration = 0, disposed = false
const form = reactive({ name: '', description: '', base_role: 'viewer' as BaseRole, enabled: true, permissions: [] as string[] })
const editor = useIdentityEditor(load), command = editor.command, message = editor.message
const available = computed(() => data.value?.permission_catalog.entries.filter(x => x.declared_by_role[form.base_role]) ?? [])
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.user.role, session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), contextGeneration.value, switchingTenant.value]))
function clearView() {
  readGeneration++; controller?.abort(); controller = null
  data.value = null; selected.value = null; opened.value = false; loading.value = false; error.value = null
  Object.assign(form, { name: '', description: '', base_role: 'viewer', enabled: true, permissions: [] })
  editor.clear()
}
watch(scope, clearView, { flush: 'sync' })
onUnmounted(() => { disposed = true; clearView() })
async function load() {
  const turn = ++readGeneration, owner = scope.value, tenant = session.value?.tenant.id
  controller?.abort(); controller = null; data.value = null; selected.value = null; opened.value = false; error.value = null; loading.value = false
  if (disposed || switchingTenant.value || !tenant || !can('roles:read')) return false
  const current = () => !disposed && turn === readGeneration && owner === scope.value && can('roles:read') && !switchingTenant.value
  const request = new AbortController(); controller = request; loading.value = true
  try {
    const raw = await api.request('/identity/roles', { signal: request.signal })
    if (!current()) return false
    data.value = readRolePage(raw, tenant); return true
  } catch (e) { if (current() && !isCancelled(e)) error.value = e; return false }
  finally { if (turn === readGeneration) { controller = null; loading.value = false } }
}
onMounted(load)
async function refresh() {
  const owner = scope.value
  if (editor.busy() || !await editor.leave() || owner !== scope.value) return
  editor.clear(); await load()
}
async function edit(role?: ProjectRole, copy = false) {
  const owner = scope.value
  if (editor.busy() || !can('roles:read') || switchingTenant.value || !await editor.leave() || owner !== scope.value) return
  if ((!role || copy) && !can('roles:write')) return
  editor.clear(); selected.value = role && !copy ? role : null
  Object.assign(form, { name: role ? role.name + (copy ? ' 副本' : '') : '', description: role?.description ?? '', base_role: role?.base_role ?? 'viewer', enabled: role?.enabled ?? true, permissions: [...(role?.permissions ?? [])] })
  opened.value = true; editor.dirty.value = false
}
function category() { form.permissions = []; editor.dirty.value = true }
async function save() { if (!opened.value || !can('roles:write') || !can('roles:read') || switchingTenant.value || selected.value?.builtin) return; const role = selected.value; if (await editor.save(role ? `/identity/roles/${role.id}` : '/identity/roles', role ? 'PATCH' : 'POST', { expected_version: role?.version ?? 0, name: form.name, description: form.description || null, base_role: form.base_role, enabled: form.enabled, permissions: [...form.permissions] })) opened.value = false }
</script>
<template>
  <section class="identity-page" v-loading="loading">
    <div class="page-heading"><div><h1>角色与权限</h1><p>按租户分配角色；角色权限变更会在后续请求中立即核验。</p></div><el-button v-if="can('roles:write')" type="primary" :disabled="editor.busy()" @click="edit()">新建角色</el-button></div>
    <p v-if="!can('roles:read')">当前会话无角色读取权限。</p>
    <el-button v-if="can('roles:read')" :disabled="editor.busy() || switchingTenant" @click="refresh">刷新角色列表</el-button>
    <ApiErrorNotice :error="error"><el-button @click="refresh">重新读取</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
    <div class="identity-cards"><article v-for="role in data?.items" :key="role.id" class="identity-card"><h2>{{ role.name }}</h2><p>{{ role.description || '未填写说明' }}</p><p>{{ baseRoleLabels[role.base_role] }} · {{ role.permissions.length }} 项权限 · {{ role.assigned_members }} 位成员</p><el-tag :type="role.enabled ? 'success' : 'info'">{{ role.enabled ? '启用' : '停用' }}</el-tag> <el-tag v-if="role.builtin" type="info">内置角色</el-tag><div class="identity-actions"><el-button @click="edit(role)">{{ can('roles:write') && !role.builtin ? '查看 / 编辑' : '查看权限' }}</el-button><el-button v-if="can('roles:write')" @click="edit(role, true)">复制为新角色</el-button></div></article></div>
    <form v-form-feedback v-if="opened" class="identity-card identity-form" @submit.prevent="save" @input="editor.dirty.value = true" @change="editor.dirty.value = true">
      <h2>{{ selected ? selected.name : '新建自定义角色' }}</h2><p v-if="selected?.builtin">内置角色保留基线，可复制后修改权限。</p>
      <fieldset :disabled="editor.busy() || !can('roles:write') || selected?.builtin"><label>角色名称<input v-model="form.name" required maxlength="100" /></label><label>说明<textarea v-model="form.description" maxlength="1000" /></label><label>基础业务类别<select v-model="form.base_role" :disabled="!!selected" @change="category"><option v-for="(label, id) in baseRoleLabels" :key="id" :value="id">{{ label }}</option></select></label><label class="check"><input v-model="form.enabled" type="checkbox" />启用角色</label>
      <div v-for="dimension in data?.permission_catalog.dimensions" :key="dimension.id"><h3>{{ dimension.label }}</h3><div class="permission-grid"><label v-for="entry in available.filter(x => x.dimension_ids.includes(dimension.id))" :key="entry.id" class="check"><input v-model="form.permissions" type="checkbox" :value="entry.id" /><span>{{ entry.label }}<small>{{ entry.id }}</small></span></label></div></div>
      <el-button v-if="can('roles:write') && !selected?.builtin" native-type="submit" type="primary" :loading="command.state === 'sending'">保存角色</el-button></fieldset>
    </form>
  </section>
</template>
