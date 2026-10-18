# D18 首页KPI、租户纳管与经营指标 · 开发文档

> 本文件由 Notion 页面导出（2026-10-18），内容以 Notion 原页面为准：https://app.notion.com/p/8bf8b9782cca4fd6933041e50af39acc

> [!IMPORTANT]
> 目标：补齐三块尚无独立开发文档、但合同列为一期的能力：A. 运营总览首页真实数据（M1）；B. 租户创建 / 配额 / 隔离 / 档位、五步资产接入、凭据托管、退役迁移（M2-2～M2-5、M2-11、M2-13、M3-4、M3-8、M3-11）；C. 经营指标（M10-1～M10-6、M10-9～M10-13；M10-7 / M10-8 见 D06）。<br>对应仓库 `doc/development/01` 的 D05「租户与资产」、D12「报告与经营」、D13「运营与治理」；阻塞项 B05 / B07 / B15。

## 一、代码现状（commit cca471d）

| 能力 | 已有实现 | 现状 |
|---|---|---|
| 首页 | `DashboardController`（`GET /api/v1/dashboard`）、`domain/dashboard/DashboardAvailabilitySnapshot`（6 个 KpiId、6 个 PanelId、TrendWindow 24h / 7d / 30d）、`DashboardPage.vue`、`utils/dashboardWorkspace.ts`（30 秒轮询 + 手动刷新） | 固定版式已实现（M1-11 待验）；KPI 与面板数值全部为 null + `unavailableReason` |
| 租户切换 | `POST /api/v1/session/tenant`（会话属性 `mop.tenant_id`） | 已实现；客户角色是否可切换为「口径待确认」（M1-7） |
| 全局搜索 | `GlobalSearchController`、`utils/globalSearchWorkspace.ts` | 仅资产 + 本人报障；事件、脚本、通知铃铛缺失（M1-8） |
| 租户 | `mop_tenant` 仅 `id, name, created_at`；`TenantInventoryController`（`GET /api/v1/tenants/inventory`，12 列只读） | 无新建租户、配额、隔离模式、档位接口 |
| 身份与权限 | V27 / V28 项目身份与角色、`ProjectIdentityController`、`CapabilityCatalog`（能力门控） | 已实现；OIDC 默认关闭（Q17） |
| 资产接入 | `PlatformController`：`POST /api/v1/assets/{id}/onboarding` | 固定返回 422 `CONFIG_PENDING` |
| 配置发布 | `POST /api/v1/configurations/{key}/publish` | 固定拒绝；运行配置走 V32 `/api/v1/runtime-configurations/{key}/releases` |
| 凭据 / 退役迁移 | 无（连接秘密只在 Git 忽略的本地配置中） | **缺失** |
| 经营指标 | `domain/operations/ExplicitOperationalMetrics`  ＋ `POST /api/v1/operations/explicit-comparisons`、`OperationalComparisonPage.vue`；V8 工时 | 只做显式样本计算，不读真实数据 |

配置键：`dashboard.freshness_max_sec=300`、`monitor.page_refresh_sec=30`、`asset.unknown_detect_max_sec=60`（Q06）、`quota.warning_ratio=0.75`、`quota.critical_ratio=0.9`、`tenant.worker_concurrency=2`、`tenant.name_unique=true`、`asset.name_unique_in_tenant=true`、`credential.old_key_grace_hours=24`（Q28）、`events.default_retention_years=3`；待确认（null）：`tenant.plan` / `isolation` / `pool` / `contract_device_quota` / `item_quota` / `nvps_quota`（Q16）、`finance.labor_rate` / `system_allocation` / `traffic_cost` / `fte_rule`（Q23）、`asset.columns` / `import_steps` / `ip_scope`（Q31）。

## 二、A. 运营总览首页（M1）

首页不另建统计口径，全部读取各模块的正式读取模型（M10-12「唯一口径」）：

| 卡片 / 面板 | 数据来源 | 依赖 |
|---|---|---|
| 资产在线率（「未知」单独计数） | D05 采集新鲜度 + `asset.unknown_detect_max_sec` | D05 |
| 当前问题（5 级、排除已抑制） | D15 `mop_monitoring_event` | D15 |
| 本月服务可用率（无样本显示「未知」） | D16 `AvailabilityCalculator` | D16 / Q05 |
| 待处理工单（含已超 SLA） | D07 正式工单 + D16 时钟 | D07 / D16 |
| MTTA（近 24 小时） | D15 `first_ack_at − problem_at` | D15 |
| 通知送达率（区分受理与送达，\< 95% 黄色） | D11 送达记录（仅 delivered 计入） | D11 |
| 告警趋势 + 降噪率 | D15 `noise-funnel` | D15 |
| 服务健康 / 故障时间线（未知区间斜纹） | D16 区间计算 | D16 |
| 最近事件 7 条、待处理工单 5 条、资源 Top5 | D15 / D07 / D05 | — |

实施：在 `DashboardAvailabilitySnapshot` 逐项替换 `unavailableReason` 为真实读取器；顶栏新鲜度取「最近一次真实采样时间」而不是数据库观察时间（现状差距 M1-9）；下钻链接带当前租户上下文；`tenant_switch` 写审计。

## 三、B. 租户、配额、隔离与资产接入

### 3.1 数据库（V33 起）

| 新表 | 关键字段 | 合同 |
|---|---|---|
| `mop_tenant_profile` | tenant_id、plan、isolation_mode（SHARED / DEDICATED / INDEPENDENT）、retention_policy_ref、identity_provider、itsm_mode、pool_id、version | M2-2 / M2-4 |
| `mop_tenant_quota`、`mop_tenant_quota_reservation` | 资产 / Item / NVPS 配额、Worker 并发上限；预留与释放记录（导入、接入原子占额） | M2-3 / M2-11 |
| `mop_tenant_provisioning` | operation_id、步骤（主机组 `tenant/{uuid}/{site}`、受限身份、首个管理员邀请）、状态、失败原因、重试 | M2-2 |
| `mop_asset_onboarding` | asset_id、step（1 登记 → 2 分配采集 → 3 凭据 → 4 模板 → 5 首值验收）、阻断原因、首值时间 | M3-4 |
| `mop_credential` | tenant_id、site_id、kind（TLS_PSK / TLS_CERT / SNMPV3）、密文、key_version、rotated_at、old_valid_until | M3-8 |
| `mop_asset_lifecycle_change` | asset_id、action（RETIRE / MIGRATE）、operation_id、保留映射、目标资源池、双采抑制窗口 | M3-11 |
| `mop_tenant_retention_job` | tenant_id、阶段（停采停告警 → 保留映射 → 到期清理）、执行记录 | M2-13 |

服务档位（M2-10）放在 D16 的 `mop_service_tier`，此处只引用。

### 3.2 接口

- `POST /api/v1/tenants`（202 + operation，异步准备主机组与受限身份；名称唯一）、`GET /api/v1/operations/{id}` 回读
- `GET|PATCH /api/v1/tenants/{id}/quota`（只读项不可改；超限硬校验文案「已达契约设备数上限（N台），请先扩容或联系服务经理」）
- `POST /api/v1/assets/{id}/onboarding`：把现有 422 改为真实五步编排，逐步返回阻断原因
- `POST /api/v1/credentials`、`POST /api/v1/credentials/{id}/rotate`（界面只能轮换、不可查看明文；旧值 `credential.old_key_grace_hours` 后失效）
- `POST /api/v1/assets/{id}/retire`、`POST /api/v1/assets/{id}/migrate`（202 + operation）
- 隔离验证：补充覆盖监控、缓存、任务、导出对象的跨租户 404 测试；是否启用 RLS 取决于 Q02（当前未使用 RLS）

### 3.3 前端

- `TenantInventoryPage.vue` 增加新建租户抽屉、配额进度条（按 `quota.warning_ratio` / `critical_ratio` 变色）、档位与变更记录入口。
- `AssetsPage.vue` 增加五步接入向导、凭据轮换、退役 / 迁移向导。

## 四、C. 经营指标（M10）

- 新建 `OperationalMetricsService`：八项指标（M10-12）唯一计算入口，首页、月报、经营页、导出都调用它；计算结果按月冻结（与 D12 月报快照共用）。
- 毛利（M10-4）：月费 −（工时 × 人力单价 + 系统分摊 + 交通成本），单价与分摊为配置项并提供维护界面；改价不重算已冻结月份。
- 人员服务能力（M10-5）、单人可管客户数（M10-10）、远程处置率（M10-3，依赖 D13）、跳档提示（M10-11，月度清点后 T+1 生成服务经理待办）。
- 运营计量看板（M10-6）：活跃租户、资产与 Item 数、实际 NVPS、保留量、首次响应时长、服务覆盖率。
- 时间维度与导出（M10-9）：1h / 6h / 24h / 7d / 30d 取点数配置化，导出走 900 秒签名地址。

## 五、实施步骤

1. 租户档案 / 配额 / 开通编排迁移与 API（含原子占额，供 D08 资产导入复用）。
2. 凭据托管与轮换（D05、D17 的前置）。
3. 五步接入编排替换 422；退役与迁移。
4. 首页逐卡接入真实读取器（随 D05 / D07 / D11 / D15 / D16 交付逐项点亮）。
5. 经营指标统一服务、月度冻结与经营页面。

## 六、验收检查项（合同原文摘录）

- [ ] M1-1：断开一台设备采集，1 分钟内「未知」+1 且「正常」−1；6 张卡片与列表页统计一致；下钻筛选正确
- [ ] M1-2 / M1-5：降噪率按（原始 − 最终通知）÷ 原始计算；被抑制事件挂「已抑制」，归并显示「×N」
- [ ] M1-4：制造 30 分钟采集中断，时间线出现斜纹未知区间
- [ ] M1-7：客户管理员登录后无租户切换控件（或按新口径验收）；切换后请求只返回该租户数据
- [ ] M2-2 / M2-3：新建租户 202 后可回读进度；修改配额后保存并在列表进度条生效
- [ ] M2-11：现有 + 新增 ＞ 契约设备数时提交被拦截且按钮置灰
- [ ] M3-4：五步向导逐步校验，首值回读后才算接入完成
- [ ] M3-8：界面无明文查看入口；轮换后旧密钥 24 小时后失效
- [ ] M3-11：退役后停止新告警且历史可查；迁移过程不产生重复告警
- [ ] M10-4：毛利与手算一致；修改人力单价后历史月份数据不变
- [ ] M10-11：构造超限租户，T+1 日生成服务经理待办并通知

## 七、阻塞与待确认

- B05（首页依赖事件链）、B07（租户 / 配额 / 隔离 / 五步纳管 / 凭据 / 退役未齐）、B15（经营真实数据源）。
- Q02 数据库与隔离（是否 RLS）、Q16 档位 / 配额 / 隔离 / 资源池、Q17 身份、Q23 财务参数、Q31 资产字段。
