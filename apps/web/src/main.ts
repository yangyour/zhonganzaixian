import { createApp } from 'vue'
import {
  ElAlert, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElConfigProvider,
  ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElForm, ElFormItem,
  ElInput, ElLoading, ElMenu, ElMenuItem, ElOption, ElSelect, ElSkeleton,
  ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import { api } from './api/client'
import { forgetSession } from './stores/session'
import { router } from './router'

api.onUnauthorized = () => { forgetSession(); void router.replace({ name: 'login' }) }
const app = createApp(App)
for (const component of [
  ElAlert, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElConfigProvider,
  ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElForm, ElFormItem,
  ElInput, ElMenu, ElMenuItem, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
]) app.use(component)
app.directive('loading', ElLoading.directive)
app.use(router).mount('#app')
