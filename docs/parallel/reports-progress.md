# 报告与经营数据开发进度

## 归属与执行范围（2026-10-23）

- 项目：众安在线 MOP；报告、首页与经营聚合后端。SLA 基础规则归监控会话。
- 独立检出：`E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\reports`。
- 规范 origin：`https://github.com/yangyour/zhonganzaixian.git`，组织 `yangyour/zhonganzaixian`。
- 分支：`codex/launch-reports-20261023`；开始时上游 `origin/main`、HEAD 与最新 main 均为 `05ec77ba9fbbca99e2b1e4db739c2c0aafcb645d`。
- 依据：本检出 AGENTS、并行协调文档及两份 launch-work 清单；原主工作区与前端视觉文件只读。
- 18215/15215 在开始时未监听；本功能无需启动 API/Web。本地只启动到既有服务器 PG 的临时回环 SSH 转发，验证后关闭。

## 已完成增量：季度报告原子来源捕获

新增 `POST /api/v1/inspection-quarterly-reports/capture`，将明确执行单修订和预期摘要的校验、不可变来源集合、季度 DRAFT、两条审计及幂等收据放入同一个已有 PostgreSQL 事务。复用 V23/V41，未新增或修改迁移。详情与 PDF 草稿沿用同一已冻结文档及既有摘要复核。

完整接口、运行命令与边界见 [原子捕获说明](../report-capture-20261023.md)。代码已实现且真实 PG 切片已验证；仍是 DRAFT，没有审批、正式发布、客户签字或真实设备体检结论。

验证：82 项聚焦 Java 单元/隔离测试、7 项指定 LAN PostgreSQL 17.9 集成、196 项契约测试通过，生成契约一致。新建两处独立 `SYNTHETIC_REPORT_CAPTURE_*` 租户，仅新建无本地口令/外部身份绑定的测试主体；结束停用该主体及其成员关系，保留不可变测试证据。实际业务租户七张相关表指纹和 V50 迁移历史不变。新增接口真实 TCP HTTP/浏览器及正式合同 TC 未执行。

首轮 Maven 参数拆分、Mockito 重设异常桩和契约测试 URN 格式问题均已修正；失败未登记为通过。最终 [Java](../evidence/report-capture-20261023/java-tests.json)、[PG](../evidence/report-capture-20261023/postgresql.json)、[组件](../evidence/report-capture-20261023/components.json) 证据分别留存。

## 后续范围

- 推进已有真实业务表的首页事实聚合，未知健康率、SLA 正式统计、成本及人效口径继续为 null。
- 统一跨业务指标快照、月报/体检模板、审批发布、正式网页/PDF 同源交付和阅读记录尚未完成；不能把本次原子季度草稿标为整条报告交付链 DONE。
- 正式评分、审核人/接收人、成本人员来源及客户报告样式/重生成规则保持待确认；通用代码继续配置化推进。

## 第二个增量：首页非终态工单聚合

新增 `GET /api/v1/dashboard/pending-tickets`。同一只读快照统一读取 V33/V42 当前状态并给出已知非终态、终态、未配置数量与最近五条；未配置登记存在时完整待办数为 null。客户角色沿用本人报障可见性。未知 SLA/健康率保持 null，没有接管前端视觉。详见 [接口与验收边界](../dashboard-pending-tickets-20261023.md)。

4 项新隔离命名空间真实 PG 通过，覆盖混合状态、客户/租户隔离及查询期间并发插入的快照一致性；新增测试主体均停用，实际客户十表/迁移历史不变。最终 128 项首页/季度报告/PDF 聚焦 Java、197 项契约通过。V42 状态分类已单元验证，本次 PG 未构造 V42 业务流转，TCP HTTP/浏览器/正式 TC 未执行。

第一项功能提交 `056abff7` 已经顺序合入 main `cdcd45c3`，合并后额外 PDF 9 项回归通过；本分支同步吸收同期其他五条线的提交。第二项按相同锁流程独立提交集成，具体提交以 Git 历史为准。

## 2026-10-23 接续清单（R01–R03）

最新归属复核：独立 reports 检出与 origin 保持规范；从干净的 `a4853f0b` 正常快进至 `origin/main 9cdccdc8`。已读最新 AGENTS、MD/JSON 清单，`py -3 tools/check_implementation_gaps.py` 返回128条、25组、0遗漏，completion_verified=false。总清单由集成会话维护，本线不改状态JSON。

- **R01 进行中，原子捕获页面子项已验证**：新增季度页原子来源方式，复用执行单选择/版本摘要核对；相同命令恢复后单独回读正文。105项前端测试、类型/构建校验通过；真实DEMO浏览器已验证一次服务端提交后响应丢失、GET不能确认成功、同path/body/key重放与原报告回读；HTML包含同一冻结摘要。最终1440/1024桌面报告区axe0违规，无页面横向溢出。PDF在当前测试站503 REPORT_PDF_UNCONFIGURED，字体或摘要配置未就绪，保留明确门禁，不伪称PDF交付通过。跨业务同版本来源及正式批准/发布仍待补。见 [R01页面与恢复证据](../report-capture-ui-20261023.md)。
- **R02 OPEN**：月报/体检、正式报告中心、审核发布、阅读与交付意图尚待实现；年度二期条件单列。未知评分、重生成和品牌配置保持null。
- **R03 进行中**：在既有V1聚合基础上补独立SLA增强读取；成本/FTE、经营时间维度/导出仍未核销，不能把首页切片当整个R03完成。

### UI01 依赖：待办SLA增强接口子项（已验证，首页接线由集成会话完成）

保留严格V1 `GET /dashboard/pending-tickets`。新增独立 `GET /dashboard/pending-tickets/sla`，`projection_version=MOP_PENDING_TICKETS_SLA_V2`；相同只读REPEATABLE_READ事务内读取权限、当前流程版本、前五条与SLA。逐行提供 `priority`、`frozen_service_tier`、`sla_metric_version`、`sla_version`、`overdue`、`response_clock/recovery_clock`；时钟含状态、breached、缺失原因、deadline_at/completed_at、服务时段剩余毫秒及remaining_basis。前端不自行计时或判定超时。

复用已有V33 `TicketSlaClockEvaluation` 与V42 `SlaClockState`，不更改SLA基础规则。缺配置、停用V42消费者、未确认重开规则及尚未连接的V42监控t2消费保持unknown。全量已超时计数 `overdue_sla` 仅在全部可见登记可判断时返回；另给 `known_overdue/unknown_sla/evaluated_pending/unevaluated_pending/evaluation_limit`。单次最多100张非终态工单是明确技术预算，超出时返回部分证据与null总计，不静默把前五条当总数。集成会话负责DashboardPage、DashboardWorkflowsPanel与客户端，本线不修改。

69项Java、201项契约、4项指定PG隔离检查及既有DEMO三角色的真实候选HTTP对照通过，匿名读取401。候选绑定 `http://127.0.0.1:18215`，默认验证后关闭；可用 `py -3.11 tools/verify-dashboard-sla-candidate.py --output .local/新的验证目录 --hold` 在持有environment.lock期间保留供首页联调，Ctrl+C清理。前端代理指向该本地候选，不使用测试站未部署的V2接口；不发送凭据。字段、命令及未验范围详见 [SLA增强读取](../dashboard-pending-ticket-sla-20261023.md)。V42完整真实流转、其监控t2连接、超100全量异步汇总和前端V2接线未由本线宣称完成。

R01页面功能提交 `cbc6d874` 已以 `d86a913f` 正常合入main，合并后105项回归与类型检查通过；已保留集成会话的UI01.1和最新DEMO契约生成器。
