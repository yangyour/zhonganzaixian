<script setup lang="ts">
import '../styles/adminPrototype.css'
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
const editor = useIdentityEditor(load, {snapshot:inputSnapshot,preserveUnknown:true}), command = editor.command, message = editor.message
const available = computed(() => data.value?.permission_catalog.entries.filter(x => x.declared_by_role[form.base_role]) ?? [])
const scope = computed(() => JSON.stringify([session.value?.user.id, session.value?.user.role, session.value?.tenant.id, session.value?.permission_version, session.value?.account_version, [...(session.value?.capabilities ?? [])].sort(), contextGeneration.value, switchingTenant.value]))
function inputSnapshot(){return JSON.stringify([scope.value,opened.value,selected.value,form])}
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
  const owner = scope.value, before=inputSnapshot()
  if (editor.busy() || !await editor.leave() || owner !== scope.value || before!==inputSnapshot()) return
  editor.clear(); await load()
}
async function edit(role?: ProjectRole, copy = false) {
  const owner = scope.value, before=inputSnapshot()
  if(role&&!data.value?.items.some(item=>item===role))return
  const target=role?JSON.stringify(role):null
  if (editor.busy() || !can('roles:read') || switchingTenant.value || !await editor.leave() || owner !== scope.value || before!==inputSnapshot()) return
  if(role&&(!data.value?.items.some(item=>item===role)||JSON.stringify(role)!==target))return
  if ((!role || copy) && !can('roles:write')) return
  editor.clear(); selected.value = role && !copy ? role : null
  Object.assign(form, { name: role ? role.name + (copy ? ' 副本' : '') : '', description: role?.description ?? '', base_role: role?.base_role ?? 'viewer', enabled: role?.enabled ?? true, permissions: [...(role?.permissions ?? [])] })
  opened.value = true; editor.dirty.value = false
}
function category() { form.permissions = []; editor.dirty.value = true }
async function save() { if (!opened.value || !can('roles:write') || !can('roles:read') || switchingTenant.value || selected.value?.builtin) return; const role = selected.value; if (await editor.save(role ? `/identity/roles/${role.id}` : '/identity/roles', role ? 'PATCH' : 'POST', { expected_version: role?.version ?? 0, name: form.name, description: form.description || null, base_role: form.base_role, enabled: form.enabled, permissions: [...form.permissions] })) opened.value = false }
async function closeEditor() { const owner = scope.value, before=inputSnapshot(); if (editor.busy() || !await editor.leave() || owner !== scope.value || before!==inputSnapshot()) return; editor.clear(); opened.value = false; selected.value = null }
</script>
<template>
  <section class="identity-page admin-prototype" v-loading="loading">
    <div class="page-heading"><div><h1>角色与权限</h1><p>管理系统内置角色与自定义角色权限配置，权限变更实时同步至关联成员。</p></div><div class="heading-actions"><el-button v-if="can('roles:read')" :disabled="editor.busy() || switchingTenant" @click="refresh">刷新角色列表</el-button><el-button v-if="can('roles:write')" type="primary" :disabled="editor.busy()" @click="edit()">新建角色</el-button></div></div>
    <p v-if="!can('roles:read')" class="admin-empty">当前账号无角色查看权限。</p>
    <ApiErrorNotice :error="error"><el-button @click="refresh">重试</el-button></ApiErrorNotice><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" />
    <div class="admin-stats" v-if="data"><div class="admin-stat"><span>角色总数</span><strong>{{ data.items.length }}</strong><small>当前授权租户角色</small></div><div class="admin-stat"><span>启用角色</span><strong>{{ data.items.filter(r => r.enabled).length }}</strong><small>成员授权可选角色</small></div><div class="admin-stat"><span>内置角色</span><strong>{{ data.items.filter(r => r.builtin).length }}</strong><small>系统预置权限基线</small></div><div class="admin-stat"><span>能力目录</span><strong>{{ data.permission_catalog.entries.length }}</strong><small>按基础角色划分授权范围</small></div></div>
    <section class="admin-card"><div class="admin-card-head"><h2>角色矩阵</h2><span class="muted">{{ session?.tenant.name }} · 按基础业务类别授权</span></div><div class="admin-table-scroll"><table class="admin-table"><thead><tr><th>角色 / 说明</th><th>基础类别</th><th>权限</th><th>成员</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="role in data?.items" :key="role.id"><td><strong>{{ role.name }}</strong><small>{{ role.description || '未填写说明' }}</small></td><td>{{ baseRoleLabels[role.base_role] }}<small v-if="role.builtin">系统内置</small></td><td>{{ role.permissions.length }} 项</td><td>{{ role.assigned_members }} 位</td><td><el-tag :type="role.enabled ? 'success' : 'info'">{{ role.enabled ? '启用' : '停用' }}</el-tag></td><td><div class="admin-row-actions"><el-button @click="edit(role)">{{ can('roles:write') && !role.builtin ? '查看 / 编辑' : '查看权限' }}</el-button><el-button v-if="can('roles:write')" @click="edit(role, true)">复制为新角色</el-button></div></td></tr></tbody></table><p v-if="data && !data.items.length" class="admin-empty">当前租户暂无角色记录。</p></div></section>
    <form v-form-feedback v-if="opened" class="identity-card identity-form admin-editor-panel" @submit.prevent="save" @input="editor.dirty.value = true" @change="editor.dirty.value = true">
      <div class="admin-editor-title"><h2>{{ selected ? selected.name : '新建自定义角色' }}</h2><el-button :disabled="editor.busy()" @click="closeEditor">关闭</el-button></div><IdentityCommandNotice :command="command" :message="message" :readback-failed="editor.readbackFailed.value" @retry="editor.retry" /><p v-if="selected?.builtin">系统内置角色不可直接修改，可通过“复制为新角色”创建自定义角色。</p>
      <fieldset :disabled="editor.busy() || !can('roles:write') || selected?.builtin"><label>角色名称<input v-model="form.name" required maxlength="100" placeholder="请输入角色名称" /></label><label>角色说明<textarea v-model="form.description" maxlength="1000" placeholder="选填，描述该角色的职责范围与适用人员" /></label><label>基础业务类别<select v-model="form.base_role" :disabled="!!selected" @change="category"><option v-for="(label, id) in baseRoleLabels" :key="id" :value="id">{{ label }}</option></select></label><label class="check"><input v-model="form.enabled" type="checkbox" />启用角色</label>
      <div v-for="dimension in data?.permission_catalog.dimensions" :key="dimension.id"><h3>{{ dimension.label }}</h3><div class="permission-grid"><label v-for="entry in available.filter(x => x.dimension_ids.includes(dimension.id))" :key="entry.id" class="check"><input v-model="form.permissions" type="checkbox" :value="entry.id" /><span>{{ entry.label }}<small>{{ entry.id }}</small></span></label></div></div>
      <el-button v-if="can('roles:write') && !selected?.builtin" native-type="submit" type="primary" :loading="command.state === 'sending'">保存角色</el-button></fieldset>
    </form>
  </section>
</template>
<style scoped>
.heading-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.admin-editor-title h2{min-width:0;overflow-wrap:anywhere}.admin-editor-title>.el-button{flex-shrink:0}
@media(max-width:650px){.admin-prototype.identity-page .admin-editor-panel{inset:0;width:100%;height:100dvh;max-height:100dvh;padding:0 16px 20px;z-index:1000}.admin-prototype.identity-page .admin-editor-title{top:0;margin-bottom:16px;padding:14px 0;min-height:72px}.admin-prototype.identity-page .admin-editor-title>.el-button{min-height:44px;min-width:88px;margin-left:0}}
</style>
