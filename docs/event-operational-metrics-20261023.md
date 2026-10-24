# UI02 / R03：当前问题与事件确认时长

## 已实现的链路

- `GET /api/v1/metrics/monitoring-events` 使用当前登录成员，要求内部 padmin/msp 及 dashboard:read、tickets:read；返回前再次核对完整成员关系和会话。客户端不得覆盖租户、身份、观察时间。
- PostgreSQL 读取器在单个只读 REPEATABLE READ 事务内完成授权、数据库观察时间、严重级别计数、完整有界 MTTA 人群和游标页。不添加迁移，不自动执行 DDL。
- 当前问题是 `monitor_state=PROBLEM AND suppression_state=NONE`，不是铃铛的 `PROBLEM AND handling_state=NEW`。五个级别的计数包含整个符合条件的人群，不从前五条或当前页推算。
- MTTA 采用原始问题发生时间在 `[from,to)` 内的事件，包含各抑制状态，按记录的 `first_ack_at - problem_at` 计算。未确认单独计数；应有却缺失的确认时间、倒序或观察时间之后的确认使该人群均值未知，不剔除坏样本再显示成功。
- 无已确认样本返回 null/横线，真实零时长可以为零。超过 10,000 个事件的完整核算预算保留人群总数，但样本数、均值、分位数、摘要均为 null，绝不把局部页当完整人群。
- 共享时长数学使用 BigInteger/BigDecimal，提供算术均值、偶数样本精确中位数、nearest-rank P95。显式经营计算与 SLA 的既有秒 DTO 使用兼容适配器，避免 long 累加溢出；既有有效输入的显示舍入保持。
- 首页当前问题分级及近24小时 MTTA 绑定新读取器，失败、取消、未知、无权限分开。其余无实际来源的指标仍未知。首页范围选择只改变原趋势窗口，MTTA 卡始终近24小时；下钻显式携带窗口与当前问题/事件视图。
- 隐藏详情路由 `/event-metrics` 保留现有菜单结构，支持 24 小时/7 天/30 天、当前问题/范围内事件、50条游标分页、第一页、取消、刷新及原事件详情跳转。当前用户/租户/权限/账号版本变化清空旧数据并丢弃迟到结果。
- 公共 OpenAPI 从生成器新增，不替换旧接口；离线 DEMO 目录和 golden 仅更新引用及第47个页面声明，不初始化账号或业务数据。

## 口径和未实现范围

数据库观察时间不是设备采样时间。`collection_freshness_verified`、`clock_accuracy_verified`、`frozen_report` 固定为 false。源摘要绑定 MTTA 输入和窗口，属于可复核输入指纹，不是签字或审计证明。分页是每次重新授权的实时快照，不能宣称跨请求冻结。

原 `/dashboard` 的 V1 可用性 DTO 保留未接入值，新增独立事件来源不改变旧契约含义。原 SLA 报告仍存在以工单 openedAt/acceptedAt 提供延迟来源以及服务百分比平均的独立缺口，本批只复用数学，不声称四个消费端已经统一实际来源，也不改写历史冻结月报。后续应版本化接入事件时间及有效分母/区间。

V50 通知执行日志的 provider_receipt_verified 仍被结构固定为 false，不能由受理/尝试日志推导真实送达率。通知送达指标保持未知。服务可用率、健康、时间线、趋势和资源来源、经营成本/FTE与其他报表仍继续补齐。

## 验证边界

Java 数学、控制器和 JDBC 协议测试，Vue/ElementPlus jsdom 页面交互、类型、构建和契约检查分别记录在 `docs/evidence/dot-continuation-20261023/ui02-event-metrics.json`。JDBC doubles 不执行 PostgreSQL SQL；jsdom 不等于真实浏览器。

指定服务器的私有连接配置和主机信任仍不可用，实际 PG/OIDC/监控数据联调 NOT_RUN；浏览器验证 NOT_RUN。未创建替代数据库、写实际租户或执行部署。128项合同覆盖检查不是正式验收。

最终本地回归：Java 4,914 项发现 / 3,796 执行通过 / 1,118 条件跳过；Web 329文件 / 5,304通过；Node 250通过；离线 DEMO 18通过；类型及构建通过（保留既有chunk体积提示）。首次回归中的旧单次权限读取断言、独立新接口的旧transport桩、路由异步等待与目录数量已定位后修正，未移除权限/完整人群断言。生成契约体积上传阻塞仍按已有记录保留，本批先逐功能提交到真实本地Git；未声称已合入远端main。
