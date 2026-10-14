import { createApp, nextTick } from 'vue'
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
import { rejectUnauthorizedSession, session, revalidateSessionAfterRestore } from './stores/session'
import { router } from './router'
import { formFeedback } from './directives/formFeedback'
import { resourceLoadFailed, runtimeFailed } from './stores/navigation'
import { createPageResumeGuard } from './utils/pageResumeGuard'
import { resumePhase, resumeSession } from './stores/pageResume'
import { loginDestination } from './utils/loginDestination'
import { isNavigationFailure, NavigationFailureType } from 'vue-router'

api.onUnauthorized = () => {
  rejectUnauthorizedSession(router.currentRoute.value.path)
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

const pageResume = createPageResumeGuard({
  hasSession: () => Boolean(session.value),
  mask: value => document.documentElement.classList.toggle('mop-resume-paused', value),
  changed: value => { resumePhase.value = value },
  verify: async () => {
    try {
      await revalidateSessionAfterRestore()
      if (session.value) {
        const destination = loginDestination(session.value, router.currentRoute.value.path, router.getRoutes())
        if (destination !== router.currentRoute.value.path) {
          const result = await router.replace(destination)
          if (isNavigationFailure(result) && !isNavigationFailure(result, NavigationFailureType.duplicated)) throw Error('RESTORE_ROUTE_UNCONFIRMED')
        }
      }
    } finally { await nextTick() }
  },
})
resumeSession.retry = () => pageResume.resume()
window.addEventListener('pagehide', event => { if (event.persisted) pageResume.suspend() })
window.addEventListener('pageshow', event => { if (event.persisted) void pageResume.resume() })
