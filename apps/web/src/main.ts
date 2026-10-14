import { createApp } from 'vue'
import {
  ElAlert, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElCheckbox, ElCheckboxGroup, ElConfigProvider,
  ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElForm, ElFormItem,
  ElEmpty, ElInput, ElLoading, ElMenu, ElMenuItem, ElOption, ElSelect, ElSkeleton,
  ElStep, ElSteps, ElTag, ElTabs, ElTabPane,
} from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import { api } from './api/client'
import { forgetSession, session } from './stores/session'
import { router } from './router'
import { formFeedback } from './directives/formFeedback'
import { resourceLoadFailed, runtimeFailed } from './stores/navigation'

api.onUnauthorized = () => {
  forgetSession(session.value ? { reason: 'expired', path: router.currentRoute.value.path } : undefined)
  void router.replace({ name: 'login' }).catch(() => { /* Router recovery UI reports any resource failure. */ })
}
const app = createApp(App)
// Never reload on chunk failure: an editor may hold an unresolved original request.
window.addEventListener('vite:preloadError', () => { resourceLoadFailed.value = true })
app.config.errorHandler = () => { runtimeFailed.value = true }
for (const component of [
  ElAlert, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElCheckbox, ElCheckboxGroup, ElConfigProvider,
  ElDescriptions, ElDescriptionsItem, ElDialog, ElDrawer, ElForm, ElFormItem,
  ElEmpty, ElInput, ElMenu, ElMenuItem, ElOption, ElSelect, ElSkeleton, ElStep, ElSteps, ElTag, ElTabs, ElTabPane,
]) app.use(component)
app.directive('loading', ElLoading.directive)
app.directive('form-feedback', formFeedback)
app.use(router).mount('#app')
