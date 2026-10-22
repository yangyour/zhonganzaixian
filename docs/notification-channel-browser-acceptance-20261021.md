# 通知发送与回执：浏览器验收规格

基线 main d58e9981e7bbc025dc3b09a426cfd2038e88ebaf。为[通知发送恢复](notification-channel-recovery-20261021.md)补可执行浏览器矩阵，遵循[前端验收标准](frontend-acceptance-standard.md)。

## 新增12项规格

- 320、390、768、1440 CSS像素各1项：键盘Enter打开确认框、焦点处于框内、Tab保持框内、Escape取消后返回原发送按钮、页面无意外横向溢出、WCAG2/2.1/2.2 AA标签axe检查和截图。
- 邮件/企微各1项：未知结果阻止父刷新/筛选/标记/路由离开，原key与原载荷恢复，通道受理不标为已送达；恢复后页面导航可继续。
- 邮件受理后GET取消与焦点恢复，重试只有原GET，不发第二次POST。
- 错误GET账号范围拒绝，按原回执ID重读。
- 错误首次POST来源回执保持未知，只以原key恢复。
- 账号版本替换拒绝旧POST后续回读。
- 撤权清除旧通知与发送控件。
- 父页标记已读在途时禁用两个新发送入口，防止随后卸载未确认请求。

开发入口qa/notification-channels.fixture.html，脚本qa/browser/notification-channels.pw.ts。使用真实Vue Router、ElementPlus和产品父子组件；所有API均被Playwright拦截为合成响应，不访问真实账号或供应商。入口有DEV门禁，生产构建不包含该合成身份入口或其标记。

生产组件同时补显式group语义用于可访问名称，并为主回执状态提供稳定定位，避免取消/重试的第二个状态通知与结果节点混淆。已有组件测试补group断言，未把该语义改动视为axe已经通过。

## 执行状态

npm run test:browser -- --list 可发现86项/10文件（此前74项/9文件，加本批12项）。只执行发现、类型检查、单元/DOM回归、生产构建和Vite HTTP模块检查；未启动真实浏览器，本批12项及总86项均NOT_RUN，axe/截图没有生成，128项正式TC未改标。

现有浏览器环境限制未通过新路径绕过；不因脚本存在或HTTP200宣称键盘、模态焦点、回流、读屏或兼容性已验收。环境恢复后应执行全部脚本并按实际结果修复失败，再记录浏览器版本、截图及报告。

[发现输出](evidence/notification-channel-browser-acceptance-20261021/discovery.log)、[验证记录](evidence/notification-channel-browser-acceptance-20261021/validation.json)、[本地HTTP记录](evidence/notification-channel-browser-acceptance-20261021/http-smoke.json)。
