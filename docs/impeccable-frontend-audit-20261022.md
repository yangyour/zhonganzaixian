# Impeccable 前端全面评审（2026-10-22）

本轮为评审，不修改前端实现，不部署。用户约束：**不要改动菜单样式**。顶部模块菜单、左侧菜单和移动菜单的布局、配色、图标、选中态、间距与展开方式均保留；整改建议限定在业务内容区、表单和非菜单弹层。

结论：现有界面具有一致的蓝色运维管理系统基础，实际读取、未知状态、列表恢复和部分键盘交互已有良好实现。优先修复内容区的可访问性和窄屏信息呈现，无需重新设计整站。按 Impeccable audit 五维评分为 **13/20**，属于 Acceptable（仍有实质改进需求）；这不是 WCAG 合规认证或业务验收通过声明。

## 仓库、工具与证据

- 项目：MOP 运维托管平台；模块：`apps/web` 全站前端。
- 本地目录：`E:\work\wxw9\zhonganzaixian\mop-platform`。
- origin：`https://github.com/yangyour/zhonganzaixian.git`；完整组织路径：`yangyour/zhonganzaixian`。
- 分支/上游：`main` / `origin/main`；本轮源码基线：`36b91a12e8a3275c4dfa9b1ce5ce7721cca65542`。
- 核实依据：实际 Git 配置、`AGENTS.md`、`docs/compact-ui-release-20261022.md`。基线与已发布 `123b5f5` 的 `apps/web` 无差异。
- 使用已有本地 `pbakaus/impeccable` skill 源本：`../tmp/impeccable-ui/.local/impeccable/skill/SKILL.src.md`，加载 `reference/audit.md` 和 `reference/operate.md`。执行一次 `context --target apps/web/src/App.vue`，一次 `detect --json apps/web/src`。采用 audit 流程，不冒称运行了独立双评审者 critique。
- 实际浏览器：Microsoft Edge / Chromium `155.0.4283.62`，通过 Playwright 打开 `http://mop.all-online.com.cn/`，读取指定测试环境真实 API；没有替换业务响应为 fixture。
- 身份资料仅从 Git 忽略的私有配置读取。原历史测试管理员配置登录返回 401，之后使用已核验的现有测试身份成功登录；没有重置账号或修改权限。
- 本地现有构建清单：`c5652ec31b1726e7816ce858fb6e2a572e086d9e980c5547fb811805f967bac9`，与发布记录一致。此轮未重新构建或发布。
- 类型检查：`cd apps/web && npm run typecheck` 通过。
- 原始浏览器 JSON、交互记录、截图和 detector 结果：Git 忽略的 `.local/impeccable-audit-20261022/`。截图含测试环境业务内容，仅本机留存；不随报告提交原始数据或会话资料。

## 覆盖与边界

尝试 42 个业务路由的 1440×1000 与 390×844 视口。41 个路由实际进入，`/my-worklogs` 需要 MSP 角色，当前管理员两次均按授权规则跳转资产页，不能计作工时页浏览器通过。

资产、总览、报障、事件中心、用户管理、阈值诊断六页另检查 320 与 768 CSS 像素；登录页检查 1440/768/390/320。去掉资产初始重复扫描及两次权限跳转后，**98 个有效页面/视口组合**，其中 94 个业务组合、4 个登录组合。这是覆盖数量，不是 98 项无缺陷测试通过。

全站基线扫描记录 0 整页横向溢出、0 未捕获页面脚本异常、0 非登录业务写请求。全站初始状态均做 DOM/axe 检查并保存截图；人工视觉复核重点包括登录、资产列表/详情、总览、网络监控、事件、报障、用户管理、备份草稿、值班看板、通知偏好、季度报告、运行配置、监控接入和阈值诊断。没有逐个提交所有业务表单。

| 业务组 | 已进入并扫描的路由 |
| --- | --- |
| 账号与身份 | `/account`、`/identity/users`、`/identity/roles`、`/identity/settings`、`/wecom`、`/email` |
| 资源与监控 | `/dashboard`、`/assets`、`/monitoring-onboarding`、`/network-monitoring`、`/monitoring-events`、`/threshold-diagnostics`、`/observation-window-diagnostics` |
| 工单与服务 | `/manual-incidents`、`/tickets`、`/my-tasks`、`/ticket-workflows`、`/ticket-notifications`、`/my-notification-preferences`、`/my-export-messages`、`/asset-export-jobs`、`/sla-scenarios`、`/service-tiers`、`/oncall-board`、`/sla-reports` |
| 配置、导入、客户 | `/runtime-configurations`、`/configurations`、`/contact-imports`、`/contact-import-preview`、`/customer-contacts` |
| 备份与巡检 | `/inspection-templates`、`/backup-plan-drafts`、`/change-notice-drafts`、`/inspection-quarterly-reports`、`/inspection-source-collections`、`/inspection-worksheets` |
| 经营与系统 | `/operational-comparison`、`/capacity-estimates`、`/capability-catalog`、`/tenant-inventory`、`/audit` |

实际交互复核：资产表格箭头按钮和键盘右箭头均移动横向滚动位置；空关键词结果显示恢复入口，重置后恢复 15 行；扩展资料抽屉 Esc 关闭后焦点返回“扩展资料”；点击全局搜索并 Esc 关闭后焦点返回搜索按钮；创建资产草稿弹窗打开/关闭，未保存。

真实故障注入、所有角色、读屏软件、实体触屏、原生浏览器缩放、Firefox/WebKit/360、所有编辑器保存及第三方送达未验收。320/768 为视口模拟，不等同于原生缩放或真机。两次浏览器工具会话超时，逐页结果已逐次落盘；末次值班表格的补充几何测量未完成，不据此编造宽高数值。现存截图足以支持下述换行问题。结束核查未发现残留的 Playwright Edge 主进程。

## 五维评分

| 维度 | 评分 | 依据 |
| --- | ---: | --- |
| 可访问性 | 2/4 | 标签、语义区域和部分焦点恢复已有实现；仍有低对比度、名称不一致、滚动区焦点和目标尺寸问题 |
| 性能 | 3/4 | 路由按需加载、构建资源在现有预算内；全量组件 CSS 和历史样式仍有优化空间，未运行受控性能基准 |
| 设计变量与主题一致性 | 2/4 | 已有 mop/ws/ctl 变量层，但原型样式与页面硬编码持续覆盖；不因未承诺的深色模式而扣分 |
| 响应式 | 3/4 | 受检视口没有整页横向溢出；值班表格的极窄列和资产页首屏操作密度影响手机使用 |
| 实现一致性 | 3/4 | 未知与未接入数据保持真实，恢复入口可用；样式规则分散，少数通用组件能力未一致复用 |
| **合计** | **13/20** | **保留现有产品方向，优先修复业务区缺陷** |

实现一致性判断：产品专属性基本成立。不是把通用卡片模板包装成运维系统；实际监控、台账、草稿、未接入原因和权限页面有明确业务语义。整体可访问性尚未达到可宣称 AA 的状态。

## 优先问题

去重后列出 **9 项：P0 0 项、P1 4 项、P2 4 项、P3 1 项**。重复页面节点不重复计成缺陷数。P1 按 audit 规则包含已确认的 WCAG A/AA 问题；未操作所有业务流程，P0 为“本轮未发现”，不是保证不存在。

### F01 · P1：业务文字与控件状态对比度不足

类别：Accessibility / Theming。位置：`apps/web/src/styles/functional-prototype.css:45–49,95–96`、`apps/web/src/styles/controls.css:13`、`apps/web/src/styles/adminPrototype.css:19,23`。

实际 axe 检出且通过渲染颜色核对：

| 实际元素 | 前景 / 背景 | 比值 | 影响 |
| --- | --- | ---: | --- |
| 资产状态“全部”、列表切换选中项 | `#fff` / `#2878f5` | 4.12:1 | 12px 文字未达到普通文本 4.5:1 |
| 资产其他状态筛选文字 | `#6f8196` / `#fff` | 3.99:1 | 可用筛选项看起来过淡 |
| “蜂巢”切换文字 | `#8a97a9` / `#fff` | 2.96:1 | 可点击项容易误认为禁用 |
| 类型选择的提示/当前显示 | `#7d8da1` / `#fff` | 3.38:1 | 13px 控件文字可读性不足 |
| 用户管理账号、角色补充说明 | `#8996a8` / `#fff` | 3.00:1 | 11px 小字进一步放大阅读困难 |
| 用户管理“管理账号”操作 | `#2878e8` / `#fff` | 4.24:1 | 13px 操作文字低于目标 |

全站原始扫描的 41 个业务路由均有对比度节点，其中含重复壳层问题。排除品牌副标题、租户标题和侧栏栏目标题四组共享选择器后，仍有 **33 个路由存在内容区对比度候选**；不把全部候选当作逐条人工认证。以上六类是本轮确认示例。

标准：WCAG 1.4.3。建议：在内容区统一正文、次要正文、提示和选中状态 token；不要通过减少字号解决密度问题。修复限定 `#main-content` 和非菜单弹层，不全局替换菜单用蓝色。使用 `impeccable colorize`，再复测对比度。

### F02 · P1：业务链接可见名称与可访问名称不一致

类别：Accessibility。位置：`apps/web/src/components/DashboardReportsPanel.vue:39`、`apps/web/src/components/LiveMonitoringPanel.vue:65`、`apps/web/src/pages/OnCallBoardPage.vue:220,282`。

总览报障链接的可见内容是报障标题，而 aria-label 将其替换成“查看登记 + 登记号”；语音用户按屏幕上的标题发命令时，辅助技术得到的是另一段名称。监控“查看历史”及值班工单链接也被规则检出，名称中插入或替换了可见词组。

标准：WCAG 2.5.3。建议：保持完整可见文案作为可访问名称的起始部分，附加编号或设备名放在后面，或用 `aria-describedby` 补充上下文。例：“查看历史，设备名称”；报障标题链接保留标题。使用 `impeccable harden` / `impeccable clarify`。

共享搜索按钮的桌面告警涉及 aria-hidden 快捷键文字与名称展开方式；已确认点击、Ctrl+K、Esc 可用，未把该候选算成 41 个独立命名缺陷，更不据此改菜单样式。

### F03 · P1：两个导出页的横向表格缺少键盘入口

类别：Accessibility / Responsive。位置：`apps/web/src/pages/ExportInboxPage.vue:43`、`apps/web/src/pages/AssetExportJobsPage.vue` 中 `.proto-table-wrap`。

在 390px 的空结果状态，导出消息表格容器实际可见宽 368px、内容宽 640px，`tabIndex=-1`，没有 role，也没有可聚焦子控件。鼠标能滚动，键盘无法进入区域查看右侧列。台账导出页同类容器也被 `scrollable-region-focusable` 检出。

标准：WCAG 2.1.1。建议：采用已有命名、可聚焦的滚动区域模式，添加 `tabindex="0"`、区域名称、焦点轮廓及适用的方向键处理；空表可直接显示完整空态，不留无入口的横向表头。使用 `impeccable harden`。

### F04 · P1：阈值诊断的 JSON 示例展开目标过小

类别：Accessibility / Responsive。位置：`apps/web/src/pages/ThresholdDiagnosticsPage.vue:53`。

390px 下 `summary` 的点击区域为 340×20.4px，相邻安全点击直径仅 20.4px。1440/768/320px 也检出同一问题。不是仅以产品建议的 44px 判失败，而是低于该节点实际适用的 24px 最低门槛及间距条件。

标准：WCAG 2.5.8。建议：为内容区该展开行补足高度和垂直留白，优先符合项目 44px 触控目标；保留原生 details/summary 语义。使用 `impeccable adapt`。

### F05 · P2：值班看板手机表格将完整 UUID 拆成高耸窄列

类别：Responsive / Information hierarchy。位置：`apps/web/src/pages/OnCallBoardPage.vue:220,468–470`，相关 `.proto-table` 通用样式。

`oncall-board-390.png` 显示“查看并办理”及 36 字符工单 UUID 在很窄的第一列反复换行，一条记录占据很大高度；首屏只能看到约两条记录的局部。整页没有横向溢出不代表表格可读。现有 `overflow-wrap:anywhere` 和仅 44px 的链接最小宽度使此问题更明显。

建议：为任务标识列及二维表格设置合理最小宽度，利用已有独立横向滚动区；或在手机使用“登记号/标题 + 状态/倒计时”的记录摘要，完整 UUID 通过可聚焦详情或复制入口保留。不能直接截断且不提供完整值。使用 `impeccable adapt`。

### F06 · P2：资产手机首屏被多层操作区挤占

类别：Responsive / Visual hierarchy。位置：`apps/web/src/pages/AssetsPage.vue:227–233`。

390×844 截图中表头从约 y=710px 开始，第一条记录接近视口底部。搜索、状态、类型、站点、查询/重置/刷新、高级检索、列表/蜂巢、导入/导出/创建和滚动按钮同时展开，占用了多数首屏空间。

建议：保持搜索与主要任务直接可见，把低频筛选和次要操作做成业务工作区内的渐进展开，显示已应用筛选数量并保留一键清空。仅调整内容区，不改顶部/侧栏/移动菜单。使用 `impeccable distill` / `impeccable layout`。

### F07 · P2：通知偏好空态重复六次，主要入口后置

类别：Implementation Integrity / Visual hierarchy。位置：`apps/web/src/pages/OwnNotificationPreferencesPage.vue` 的六通道卡片与当前偏好区。

`my-notification-preferences-1440.png` 中六张卡片重复展示“无草稿 / 尚无请求草稿 / 待通知策略启用后生效”，而用户当前能执行的“开始新建本人请求草稿”位于其后。没有额外信息的相同空态占了两排；手机单列时阅读成本进一步增加。

建议：在尚无请求草稿时，将一次清晰空态与创建入口置前，六通道作为紧凑状态列表或创建后的编辑区。仍明确“草稿”和“生效”差别，不把缺失偏好补成默认成功状态。使用 `impeccable onboard` / `impeccable distill`。

### F08 · P2：设计变量已建立，但旧样式仍绕过变量系统

类别：Theming / Implementation Integrity。位置：`apps/web/src/App.vue:11–29`、`styles/experience.css`、`styles/functional-prototype.css`、`styles/workspace.css`、`styles/controls.css`、`styles/adminPrototype.css`。

当前存在 mop、ws、ctl 多套变量层，同时原型和页面样式仍直接写 `#2878f5`、`#8996a8` 等值。新统一控件层无法覆盖高优先级的旧选择器，导致“看上去统一，实际颜色仍不同”；F01 已提供可见结果，而不是单纯因硬编码存在就判错。

建议：先建立内容区 token 的唯一归属，分组件删除实际被替代的重复声明；不要继续叠加更长选择器或批量全局替换。导航 token 与选择器冻结，复验每个业务控件和弹层。使用 `impeccable extract`，按小范围迁移。

### F09 · P3：减少动态效果使用全局极短时长覆盖

类别：Accessibility / Motion。位置：`apps/web/src/styles/experience.css:193–194`。

`prefers-reduced-motion: reduce` 下所有元素动画和过渡被统一设为 `.01ms`。已有文字状态反馈降低了风险，本轮没有复现数据或焦点丢失；因此只列为低优先级实现建议，不宣称 WCAG 违规。

建议：对进度、展开、焦点和加载分别定义减少动态效果的表现，保留状态可感知性；非必要位移动效直接取消。使用 `impeccable animate` / `impeccable harden`。

## 性能与 detector 的正确解读

现有构建的主 JS 为 506,640 bytes（单文件 gzip 179,064），主 CSS 为 463,065 bytes（gzip 65,935），资产页面块为 278,953 bytes（gzip 87,354）。构建清单的完整 bootstrap JS/CSS gzip 分别为 180,575 / 66,323 bytes，低于项目预算 262,144 / 98,304 bytes。单文件测量与完整 bootstrap 口径不同，不能混用。

`main.ts` 引入完整 Element Plus 样式，后续可通过 `impeccable optimize` 评估按需样式和废弃 CSS，但当前体积没有超过已定义预算，本报告不把资源大小直接写成性能不合格。没有在受控网络/CPU条件下测量 LCP、INP 或真实用户指标，不给出虚构速度评分。

Impeccable detector 共 13 条提示：10 条侧边色条、2 条圆角边线、1 条 Inter 字体。人工复核后不直接计入 13 个缺陷：

- 错误摘要红边、草稿警告与回执色条有状态用途，不能为了消除工具告警删除语义提示。
- 运维产品采用系统/常规无衬线字体符合 Operate 模式；不建议为“独特”更换字体。
- 内容卡片装饰色条可在后续视觉整理时评估，但优先级低于本轮确认问题。

## 保留并推广的实现

- 蓝白管理界面、现有模块导航和菜单身份一致，遵循用户要求完整保留。
- 登录页四视口没有自动可访问性违规或整页溢出；输入标签、找回说明及登录入口清晰。
- 未知 CPU/内存保持未知，未采集、待接入和草稿没有伪装为正常或已生效。
- `AppTable` 已实现命名滚动区域、箭头入口和键盘滚动，可作为 F03 的复用参照。
- 资产空结果有恢复文案与清空入口，实际恢复非空列表。
- 非菜单资料抽屉和搜索弹窗的已测关闭路径能回到触发按钮。
- 前端按路由加载，类型检查通过；原始工具告警、真实页面、构建预算和未测范围分层记录。

## 建议实施顺序

1. `impeccable harden`：F02、F03；同时以内容区 token 修复 F01，并修正 F04 的目标尺寸。
2. `impeccable adapt`：F05 值班看板；`impeccable distill`：F06 资产首屏、F07 通知偏好空态。
3. `impeccable extract`：F08，逐步统一内容区变量；F09 根据动效需要单独处理。
4. `impeccable polish`：最后只做非菜单内容区细节收尾，再重跑 `impeccable audit` 与相应真实浏览器检查。

本轮不执行上述修改。原始工作区开始时干净；评审中出现的其他任务未跟踪工具文件原样保留，没有纳入本报告改动。没有业务提交、消息发送、设备探测、迁移、服务生命周期变更或发布；没有把 128 项正式合同 TC 改为完成。
