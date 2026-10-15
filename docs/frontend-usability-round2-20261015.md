# 前端交互验收第二轮：导航与网络恢复

日期：2026-10-15。延续[28项验收基线](frontend-acceptance-standard-20261015.md)，本轮按实际问题优化通用导航和网络状态反馈。API、授权、未保存/未确认写入守卫、客户规则不变；同期其他监控页面开发不混入本轮提交或结论。

## 官方依据

- [WAI-ARIA Link Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/link/)：保留带href的原生链接及浏览器行为，Enter可激活。
- [Vue Router 扩展RouterLink](https://router.vuejs.org/guide/advanced/extending-router-link.html)：自定义导航仍保留明确href和路由导航逻辑。
- [WCAG 4.1.3 状态消息](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)：状态变化可被感知，非必要不搬走焦点。
- [MDN navigator.onLine](https://developer.mozilla.org/en-US/docs/Web/API/Navigator/onLine)：浏览器联网判断只是提示，不能证明后端可用，也不应单靠此值禁止用户操作。
- [WAI-ARIA 模态弹窗](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)：取消时合理回焦；完成导航后聚焦新内容，不能被关闭动画拉回旧入口。

## 本轮QA清单

|功能|实现目标|正常与异常验证|视觉状态|
|---|---|---|---|
|顶栏/侧栏/品牌导航|普通点击沿现有路由守卫；Ctrl/Command/Shift点击与中键保留原生行为|普通点击、Enter、Ctrl新标签、中键、已处理事件；权限变化和导航取消仍受保护|1440桌面、390与320手机菜单|
|手机菜单焦点|取消回到入口；成功跳页后焦点落在目标标题|Escape、同页选择、跳页、守卫拒绝、快速关闭/重新打开|菜单开启/取消/导航后的页面|
|网络恢复提示|浏览器离线提示；恢复后说明数据仍需显式读取，可收起；不自动刷新或重发|初始在线、离线、恢复、重复事件、再次离线；已有未保存输入和API错误保留|资产列表与未保存表单，桌面/手机|

浏览器操作仅使用指定测试环境API与已有虚构测试身份，不替换业务响应或写入业务数据。测试中的离线由浏览器网络仿真触发，不冒充物理断网。单元替身只验证控件/路由逻辑。验证在既有独立工作区基线 `be594d4` 上执行，再逐文件核对源摘要；本机回环预览仍使用15175端口。每个功能验证后单独提交推送；本轮未授权为正式合同验收，也不因提交自动部署服务器。

## 导航实施与结果

真实基线确认：Ctrl点击侧栏会错误地跳转当前页面，未创建新标签；手机选择当前页面后菜单不关闭。新增共用链接激活处理，只拦截普通主键/Enter激活；Ctrl、Command、Shift、Alt、中/右键、已有处理、下载和另开目标保留原生行为。保留真实href、现有权限过滤、路由守卫和失败提示；新标签独立执行应用权限校验。

手机点击当前页视作已到达，关闭菜单；真正取消/失败仍保留当前菜单和页面。原有成功导航后聚焦目标标题、Escape回到入口均实测正常，没有重写焦点管理。

41项针对性测试通过（含权限变化、守卫取消、组合键不触发当前页路由）；类型、正式构建及发布门禁通过。首次构建指出测试属性联合类型含undefined，已修正测试类型后重新构建，未忽略类型错误。最终构建 `96656d741ceb6702af8a35564bcce6862b382e6cebde609ec506f7dff798046d`。

实际Edge完成10项检查：侧栏/模块Ctrl新标签、品牌中键新标签、普通Enter导航，以及390/320手机当前页收起、Escape回焦、跳页后标题焦点。菜单边界完整，无页面横向溢出；页面脚本异常0，已观察业务写入0。首次初始化/动画未稳定的检查已等待真实稳定状态重跑，没有使用强制点击。见[机器证据](evidence/frontend-usability-round2-20261015/navigation.json)、[320px菜单](evidence/frontend-usability-round2-20261015/navigation-menu-320.png)。Command/Shift由Windows事件测试验证，未冒充物理Mac操作。

指定组件只读盘点观察到14个项目服务运行，API ready=true、production_ready=false。服务镜像标识与前轮盘点一致，Web启动时间已变化，不能据此声称整个环境生命周期不变；本轮没有重启或部署任何服务。
