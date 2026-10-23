# UI03 全局搜索弹窗语义修复

已定位历史可访问性问题：当前锁定版本 Element Plus 生成原生 header/footer；直接 teleport 到 body 时，这些节点可被解释为第二个页面级 banner/contentinfo。不是重复视觉导航，也不是业务 API 问题。

保持样式和组件库不变，把整个搜索弹窗 teleport 到 body 下的原生 section 内，关闭内部重复 body teleport。这样弹窗 header/footer 属于自身 section，而不是页面地标。原有 role=dialog、名称、焦点约束、关闭、快捷键、租户授权及被页面写入门禁拦截后的搜索结果保留逻辑不变；不修改第三方源码、不在运行时篡改 DOM/ARIA、不隐藏可见内容。

用真实 Vue Router、Element Plus 和 axe-core 的 jsdom 测试先将同一弹窗 DOM 移回旧 body 结构，确实复现 landmark-no-duplicate-banner；恢复 section 后五条针对性规则无违规，并核对焦点和关闭。全部66项搜索定向测试、类型检查和构建通过；另从最新 origin/main `73b2b83b` 单独检出验证这两处代码，66项及构建通过，不依赖未推送的大契约功能。

这只是 UI03 的一个子项。当前没有实际浏览器复验，不将 jsdom/axe 结果写成浏览器或完整 WCAG/合同验收。M7仍受Q01门禁，近期事件、刷新及筛选继承其他清单保持独立核销。没有 API、迁移、运行配置、业务数据或部署变更。
