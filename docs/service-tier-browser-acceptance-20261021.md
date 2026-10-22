# 服务档位与 SLA 浏览器验收规格

基线 main 138f53c165465c7b692083451cf5189fb26c9d6c。按[前端验收标准](frontend-acceptance-standard.md)补可执行Playwright规格，不将测试发现、DOM或Vite检查描述为浏览器运行通过。

## 新增11项

- 320 / 390 / 768 / 1440 px四种视口：原生键盘分页及焦点、60条明细末页可达、页面不横向溢出（表格自身允许滚动）、axe WCAG 2/2.1/2.2 AA标签检查及截图输出。
- 台账GET取消状态焦点和原读取恢复；工单SLA取消后保留新输入、重试固定原工单。
- 非法任务回执不显示旧明细，原GET恢复后重新展示。
- 未知档位写入保留原body/key、阻断离开、不再确认即丢掉原请求；恢复仍核对原回执，金额0不改成null。
- 真实Element Plus放弃确认框的焦点、Tab、Escape与原始金额文本保留。
- 撤权中止私有SLA展示，账号版本变化清空旧费用与原因。

## 安全与数据边界

qa/service-tiers.fixture.html和.fixture.ts仅DEV入口；全部API由测试拦截并限制方法及路径，身份与正文是合成值，无真实账号、客户费用或后台任务操作。QA数据的60个任务/来源用于分页覆盖，不声称是真实业务历史。新增2项unit校验保证这份合成载荷通过生产读取器。

生产构建仍从正式入口生成，检查产物不含QA入口/标记。测试发现曾因一个多余括号失败，修正后重新发现并通过类型检查；原失败保留，不把它改写成浏览器运行失败或通过。

## 当前证据等级

- 总计97项 / 11文件：DISCOVERED_NOT_EXECUTED。
- 实际Chromium、键盘/截图/axe：NOT_RUN，沿用已记录环境阻塞；没有改变安全设置或绕过此前拒绝。
- 正式合同128项：仍全部NOT_RUN。
- [验证记录](evidence/service-tier-browser-acceptance-20261021/validation.json)、[本地HTTP记录](evidence/service-tier-browser-acceptance-20261021/http-smoke.json)、[生产隔离检查](evidence/service-tier-browser-acceptance-20261021/production-isolation.json)。

环境允许后可在现有Playwright配置下执行service-tiers.pw.ts并保留真实截图/trace/axe输出。届时任何失败必须修正后重跑，不以当前规格数量宣称通过。
