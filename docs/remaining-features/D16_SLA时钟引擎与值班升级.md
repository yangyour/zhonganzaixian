# D16 SLA时钟引擎与值班升级 · 开发文档

> 本文件由 Notion 页面导出（2026-10-18），内容以 Notion 原页面为准：https://app.notion.com/p/24204a0c069b4797af6a378bbbf863c6

> [!IMPORTANT]
> 目标：建设服务端 SLA 时钟引擎（档位快照、工作日历、暂停 / 恢复、50/80/100% 提醒、超时升级）以及 SLA 报表、补偿计算、值班看板与升级链。覆盖合同 M2-10、M5-8、M5-9、M5-11～M5-13、M5-18～M5-20。<br>对应仓库 `doc/development/01` 的 D08「SLA 与指标」；阻塞项 B10。D07 工单状态机依赖本页的时钟接口，两页需同批交付。

## 一、代码现状（commit cca471d）

| 能力 | 已有实现 | 现状 |
|---|---|---|
| 工作日历 | V12 `mop_working_calendar_draft` / `mop_working_calendar_revision`、`WorkingCalendarController`（`/api/v1/working-calendars`，草稿 CRUD + 归档 + history） | 草稿未发布，不参与计时 |
| 计时算法 | `domain/time/WorkingTime`、`IntervalSets`（区间并集 / 差集）、`BusinessDates` | 纯计算，可直接作为引擎核心 |
| 达标对照 | `domain/sla/SlaComplianceScenario`  ＋ `POST /api/v1/sla/compliance-scenarios` | 显式样本计算，不读真实工单 |
| 客户确认窗口 | `domain/ticket/CustomerConfirmationWindow`（24 / 48 小时、提前 12 小时提醒） | 纯计算 |
| 工单 SLA 字段 | V4 `mop_ticket.priority`、`response_due_at`、`recovery_due_at` | CHECK 约束强制为 NULL（见 D07） |
| 档位 | `mop_tenant` 只有 `id, name, created_at` | 无档位、无 `sla_tier_snapshot`、无档位变更记录 |
| 值班 / 升级链 / 提醒 | 无 | **缺失** |

配置键（`contracts/config-baseline.json`，单位分钟，禁止硬编码）：
- `sla.p1_response_min={基础30, 标准10, 进阶10}`、`sla.p1_recover_min={240, 120, 90}`、`sla.p2_response_min={60, 30, 20}`、`sla.p2_recover_min={480, 240, 180}`、`sla.p3_response_min={240, 120, 60}`、`sla.advanced_p3_recover_min=240`
- `sla.tick_interval_sec=30`、`sla.reminder_progress=[0.5, 0.8, 1.0]`、`sla.compensation_ratio={0.1, 0.2, 0.3}`、`sla.tier_availability_target={99.5, 99.9, 99.99}`、`ticket.fallback_sla_hours=4`
- `oncall.handoff_local_time="18:00"`、`oncall.unanswered_escalate_count=2`
- 待确认（null）：`sla.tenant_work_calendar`、`sla.basic_standard_p3_next_day`（Q08，基础 / 标准档 P3 恢复「次日」口径）、`sla.compensation_formula`、`sla.rounding`（Q26）、`sla.reopen_and_mttr_policy`（Q27）、`oncall.roster_and_escalation_chain`、`oncall.unanswered_retry_interval_sec`（Q15）、`availability.unknown_policy` / `min_samples`（Q05）

## 二、时钟引擎设计

```plain text
工单创建（D07）→ 写入 sla_tier_snapshot（档位 + 配置发布版本 + 日历修订号）
  → 计算 response_due_at / recovery_due_at
       基础档：仅工作时间（WorkingTime + 租户日历）
       标准 / 进阶档：7×24
       P3「次日」：按 sla.basic_standard_p3_next_day 发布值计算，未发布则不产出正式截止时间
  → sla-worker 每 sla.tick_interval_sec 扫描在途时钟
       进度达 50% / 80% / 100% → 写提醒记录（工单 + 节点唯一）→ mop_notification_intent（D11）
       100% → 触发升级链下一步 + 标记违规
  → 状态事件：待客户确认 = 暂停恢复时钟并停止响应提醒；重开 = 按 Q27 策略续算
```
关键规则：
- 档位快照只在开单时写入，后续档位变更不追溯（M2-10 / M5-20）。
- 转派、改派不重置 `response_due_at` / `recovery_due_at`（M5-14）。
- 引擎在服务端运行，前端只展示倒计时，不参与判定。
- 计算结果带口径版本号，报表页脚显示版本（M5-9）。

## 三、数据库迁移（V33 起按合入顺序分配）

| 新表 | 关键字段 | 合同 |
|---|---|---|
| `mop_service_tier` | tenant_id、tier（基础 / 标准 / 进阶）、availability_target、contract_device_count、billing_cycle、monthly_fee、effective_from、version | M2-10 |
| `mop_service_tier_change` | tenant_id、from_tier、to_tier、effective_mode（立即 / 次月 1 日）、reason、approved_by、changed_at（只追加） | M2-10 |
| `mop_ticket_sla_clock` | ticket_id、tenant_id、tier_snapshot、config_release_ref、calendar_revision_ref、response_due_at、recovery_due_at、paused_at、paused_total_sec、responded_at、recovered_at、breach_response、breach_recovery | M5-8 / M5-20 |
| `mop_ticket_sla_reminder` | ticket_id、clock（RESPONSE / RECOVERY）、progress（0.5 / 0.8 / 1.0）、sent_at、targets；唯一键 (ticket_id, clock, progress) | M5-18 去重 |
| `mop_sla_monthly_result` | tenant_id、month、applicable_count、met_count、rate、compensation_ratio、compensation_amount、formula_version、frozen | M5-19 |
| `mop_oncall_roster`、`mop_oncall_handoff` | 值班人、范围（被指派租户）、班次；接班人、备注、未确认 / 处理中 / 待复核计数 | M5-12 / M5-13 |
| `mop_escalation_policy`、`mop_escalation_step_run` | 档位、步骤序号、触发条件（未确认 / 未解决）、等待时长、目标角色；执行记录、电话未接次数、短信告知记录 | M5-13 |

说明：V4 的 `mop_ticket` CHECK 约束不可修改，正式时钟放在新表，工单展示由读取模型联查；如需在工单主表落字段，应新建正式工单表（见 D07 核查补充）。

## 四、后端实现

- 领域：`SlaClockCalculator`（截止时间计算，复用 `WorkingTime`）、`SlaClockState`（暂停 / 续算）、`ReminderPlanner`（进度节点与去重）、`EscalationPlanner`、`AvailabilityCalculator`（可用率 = 1 − 不可用并集 ÷ 有效服务时间，未知区间单列）、`CompensationCalculator`（公式与舍入由 Q26 配置）。
- sla-worker：独立进程，PG 租约轮询，周期取 `sla.tick_interval_sec`；不使用 `@Scheduled`。
- API：
	- `GET /api/v1/tickets/{id}/sla`（截止时间、进度、暂停区间、判定依据）
	- `GET /api/v1/sla/reports?period=`（6 张 KPI 卡 + 服务树 11 列，含 P50 / P95、样本量、未恢复数）
	- `GET /api/v1/sla/reports/{serviceId}/details`（逐级下钻到区间与工单）
	- `POST /api/v1/sla/report-exports`（PDF / CSV，202 + operation，复用导出任务中心与 900 秒签名地址）
	- `GET|POST /api/v1/service-tiers`、`POST /api/v1/service-tiers/{tenantId}/changes`（审批后生效，写审计）
	- `GET /api/v1/oncall/board`（KPI + 队列 + 升级倒计时）、`POST /api/v1/oncall/handoffs`
	- `GET|PATCH /api/v1/escalation-policies`
- 工作日历：把 V12 草稿接入运行配置发布流程（`/api/v1/runtime-configurations/{key}/releases`），发布后才参与计时。

## 五、前端实现（apps/web）

- 工单列表 SLA 列：倒计时进度条，超时红字（`TicketsPage.vue` + `src/utils/ticketReader.ts` / `ticket.ts`）。
- `SlaReportsPage.vue`：6 卡 + 服务树，页脚显示口径版本；扩展现有 `SlaScenariosPage.vue` 的计算展示。
- `OnCallBoardPage.vue`：30 秒自动刷新、升级倒计时、交接班弹窗（自动汇总三项计数）。
- `ServiceTiersPage.vue`：档位与变更记录（只读历史）。

## 六、实施步骤

1. 档位与变更记录迁移，租户详情接入档位。
2. 时钟计算纯函数 + 20 个边界用例（23:50 开单、节假日开单、跨月、跨夜、暂停后续算、档位变更后在途不变）。
3. 工单时钟表与 D07 状态事件对接（开单写快照、待客户确认暂停、重开续算）。
4. sla-worker：到期扫描、三级提醒去重、升级链执行，通知意图写 `mop_notification_intent`（D11）。
5. 可用率 / MTTA / MTTR 计算服务与 SLA 报表、下钻、导出。
6. 月度达成率与补偿（公式待 Q26），结果写入月报快照（D12）。
7. 值班看板、交接班、升级链配置页。

## 七、验收检查项（合同原文）

- [ ] M2-10：变更档位后随机抽 3 张在途工单，SLA 截止时间不变；变更记录可查且不可修改
- [ ] M5-8：同一优先级下不同档位租户截止时间不同；修改档位不影响历史工单
- [ ] M5-9：含未知区间的场景，可用率不把未知区间计入可用；下钻明细的区间解释可读
- [ ] M5-11：导出任务实时显示进度百分比；失败任务一键重试；PDF 排版与模板逐项一致
- [ ] M5-12：倒计时归零时自动触发下一级升级；交接班记录可查且计数与队列一致
- [ ] M5-13：修改升级链配置后行为随之变化；电话 2 次未接听触发升级且有短信记录
- [ ] M5-18：静置至 50% 与 80% 时提醒按对象准确送达；同一节点不重复提醒
- [ ] M5-19：10 张工单（含 3 张超时）达成率与手工计算一致；补偿金额与合同口径一致；可逐单下钻判定依据
- [ ] M5-20：跨工作日、节假日、夜间抽样 20 个用例全部正确；修改档位后在途工单截止时间不变

## 八、阻塞与待确认

- B10：日历草稿、区间数学和显式对照不是正式时钟 / 可用率 / 补偿。
- Q05 未知区间、Q08 日历与「次日」、Q15 升级链与值班表、Q26 补偿公式与舍入、Q27 重开与 MTTR 口径。未确认前：流程与配置项完整实现，对应计算返回「待配置」而不是套用默认值。
