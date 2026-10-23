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

## 用户停止开发后的收尾记录（2026-10-23，最新状态）

用户最新要求结束现有开发、整理并提交进度。报告线已停止新增功能，不开始 actionable 接口或其他后续工作；本节供集成会话汇入唯一总剩余文档。本线未改总清单JSON。

### 已提交与实际验证

| 增量 | 功能提交 | main状态 | 证据与边界 |
| --- | --- | --- | --- |
| 原子季度捕获后端 | `056abff7` | 已在main（合并`cdcd45c3`） | 82项聚焦Java、7项真实PG；冻结源/报告/审计/幂等同事务。仍为DRAFT。 |
| V1非终态工单聚合 | `e862af37` | 已在main（合并`a4853f0b`） | 128项当时合并Java回归、4项真实PG；V1语义包含RESOLVED，不能当作M1-6最终待办口径。 |
| R01原子捕获页面与恢复 | `cbc6d874` | 已在main（合并`d86a913f`） | 105项前端回归、类型/构建、DEMO实际API响应丢失后同键恢复、正文/HTML摘要、1440/1024桌面axe0。PDF实际503缺字体配置。 |
| R03 / UI01待办SLA V2 | `d58ea011` | 已在main（合并`b5b9d765`） | 69项Java、201项契约、4项真实PG（含读取中并发接单）、DEMO三角色候选HTTP/匿名401；合并最新主干后再通过同组回归与打包。保留主干DEMO追加器。 |

主要证据：`docs/evidence/report-capture-ui-20261023/live-browser.json`、`docs/evidence/dashboard-sla-20261023/validation.json`、`postgresql.json`、`http.json`。真实组件、浏览器、纯单元分别登记；正式合同TC及客户验收仍NOT_RUN。合并后的代码已保留其他会话的资产、备份和监控复发实现；没有自动部署。

### 必须优先补齐的已发现问题

1. **M1-6口径已由合同原文确定。** 原文要求“未关闭且未解决的前5条”，当前V1/V2是非终态集合，包含RESOLVED。后续须新增兼容的 actionable 读取，在同一数据库快照中排除 CLOSED/CANCELLED/RESOLVED；计数、前五条和超时统计必须同人群。不能前端过滤已有五条，应测试RESOLVED占据最新位置时下一条未解决工单补位、两谱系、真实0与未知。建议路径 `/dashboard/pending-tickets/actionable` **尚未实现**；完整只读分页也未实现。此项无需客户补充。
2. **读取结束后的成员复核未实现。** V2已有开始前及数据库快照内授权，尚缺返回响应前重新读取当前Membership并比较角色/能力/version的门禁，以及读取期间撤权/换角色测试。该场景不能记为通过。此项无需客户补充；停止指令到达时尚未修改controller。
3. V42真实完整流转及监控t2消费、超过100张待办的全量异步汇总仍缺。当前明确返回未知/技术预算原因，不能当作全量完整统计。

### R01–R03真实剩余范围

| ID / 合同项 | 可独立继续的实现 | 客户/组件依赖与不能混淆的边界 |
| --- | --- | --- |
| R01：M9-1、M9-2 | 统一八指标/跨业务同版本来源；月报任务及冻结快照；源变更不改历史；所有数字来源引用与下钻 | 实际采样覆盖、未明确统计/结算口径只阻塞对应字段；未知保持null，不妨碍通用捕获和版本流程开发。 |
| R01：M9-7、M9-9 | 在已有巡检模板/执行单/季度草稿上继续核销证据绑定、审批/发布与正式同源输出；H5/PDF完整一致性、权限和恢复 | 当前测试站PDF字体/摘要未配置（真实503）；正式模板/文案/重生成未决值保持null。草稿下载不等于正式发布。 |
| R02：M8-9、M9-3、M9-4、M9-5 | 老板版五区块一页、详版六章、实际HTML/PDF渲染与文件保存；报告中心、服务经理审核/退回/发布；不可变新修订；首读唯一、超过7天未读跟进、补发原快照及交付意图 | 需要新增持久化结构、受控渲染/字体及相应存储验证；未知审批人/接收人/品牌配置可先实现通用流程，不能以字段未填为由停止代码。实际外发仍需独立授权。 |
| R02：M9-6 | 体检清单维护、Excel导入/预览、五维评分框架、风险/影响/套餐/费用展示与客户访谈来源 | Q23评分/成本等值未确认时分数/费用为null，不造雷达图值，不主动扫描。 |
| R02：M9-8 | 年度仅汇总12份月报payload，阶段门禁与版本一致性 | 合同允许二期，按Q11阶段确认单列；不自动开启年度交付。 |
| R03：M10-1、2、3、12 | 统一可用率、MTTA、监控MTTR、工单解决时长、SLA达成率、远程处置率等源映射/数学与版本；供首页/SLA/月报复用 | 无覆盖或无样本保持null；远程执行事实依M7/Q01，不为计算比例擅自实施M7。已确认的监控t2停钟/P2规则不重新列为未知。 |
| R03：M10-4、5、10、13 | 月费/成本/工时/FTE的来源、生效期、版本维护与冻结；毛利/人员面板、时长中位数、内部授权 | Q09/Q23及未决人员时长口径需要明确；不读越权团队工时，不把实填/默认工时混同，不造成本/FTE。现有显式试算不是真实经营聚合。 |
| R03：M10-6、9、11 | 真实运营计量、时间窗口、CSV/XLSX导出、月度设备清点与T+1跳档提示 | 实测NVPS/覆盖率分母及相关来源缺失应单列；不能用容量估算替代实测、不能取消硬配额。 |
| R03：M10-7 | 复核已有容量计算、场景、运行参数导入并核销其真正已实现项；补剩余调用方/来源和验收 | 已有能力应复用；Q21/Q25及实测上限未确认不当采购规格。此项不能因其他经营范围未完就称全无实现。 |

A01–A05、M01–M05、N01–N02、B01–B02、P01–P03及UI前端的最终剩余由各负责线/集成会话汇总，本线不替其宣称完成。

### 未完成代码的安全保留与恢复

R02纯领域实现未进入可发布提交，已保存为本检出的命名stash：

- 名称：`R02_STOPPED_UNFINISHED_20261023_report_publication_core`
- 精确stash提交：`19bad6b758e9c3ba5c3e9380359dd9cb73dffdf7`
- 文件：`server/domain/src/main/java/cn/mop/domain/reporting/ReportPublication.java`、`server/api/src/test/java/cn/mop/api/ReportPublicationTest.java`。
- 内容仅为同源产物绑定、版本/CAS、审核发布、修订、首读及未读/交付意图的领域约束。**没有仓储、迁移、实际渲染器、API或页面接线。** 较早草稿13项纯单元曾通过；最后的字体一致性与年度再核验改动未重跑，不将这个stash称为已验证或可发布。
- 没有取得报告迁移锁，因此未分配、编写或应用任何报告新迁移；未来需先核对最新已应用谱系，再在指定服务器的独立测试命名空间验证，不能直接迁移运行中的mop_test。

恢复时先进入本检出、核对工作树，再执行（保留stash原件）：

```powershell
git stash apply 19bad6b758e9c3ba5c3e9380359dd9cb73dffdf7
```

此前两条协调stash只是历史备份，R02本次恢复以上述命名stash为准。该stash仅在本机检出保存，未作为未测代码推送GitHub。

### 资源终态

本线候选API18215、Web15215、临时SSH转发和浏览器已关闭；终检无这两个端口监听，也无本检出的Java/Node/Python候选进程。本线不持有environment/integration/migration锁，未删除其他会话的锁。忽略目录中的构建、测试日志及临时依赖缓存保留作为本地证据/缓存。停止后只提交本收尾文档到报告分支，不再申请main集成锁；由集成会话统一收束。
