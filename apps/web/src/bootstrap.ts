// The static recovery panel remains readable even if the application bundle cannot load.
void import('./main').then(() => document.getElementById('app-startup')?.remove()).catch(() => {
  const title = document.getElementById('startup-title')
  const message = document.getElementById('startup-message')
  if (title) title.textContent = '页面暂时未能打开'
  if (message) message.textContent = '页面资源未能加载。请检查网络后重新加载；如果仍未恢复，请联系项目管理员。'
  document.getElementById('app-startup')?.setAttribute('role', 'alert')
})
