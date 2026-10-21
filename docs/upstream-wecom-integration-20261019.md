> 当前谱系：用户 V39 为邮件；审批候选现为 V40，组合 API 0.60。本文及旧证据保留历史 V38/V39 审批记录，不代表当前 V40 已应用。见 [当前合入说明](upstream-email-approval-20261019.md)。

# 用户企业微信基线与审批合入（2026-10-19）

## 当前谱系

- 基于上游 `e739027375d1e47d003285898a88123203d16c16`，保留用户企业微信、LAN 更新回滚与监控改动。
- 舍弃 dot D11 `WeComMessageClient` / `WeComHttpsTransport` / `ChannelSubmissionResult` 及其测试，不包含冗余供应商适配器。用户企业微信业务实现保持不变；公共健康检查、契约生成与显式迁移入口只做增量组合。
- D09 纯计算季度报告核心来自 `6d75e02`；D10 审批核心来自 `10f9ebc`，完整审批增量来自 `308129cf..111a654`。保留此前 FK 感知漂移测试修正。
- 上游 V1–V38 SQL 全部逐字节比较不变。V38 企业微信 SHA256：`32dc38c2dab0ad385f79e9e7c886c456c06664f8b740c496a8af7bf2d9e9c0fb`。
- 原审批 V38 仅曾在已删除的独立临时测试库应用。旧分支、旧 SQL 及 `docs/evidence/d10-*` 等历史证据保持原字节；不是当前谱系的执行证明。
- 仅本新谱系将相同审批 SQL 登记为 `V39__immutable_change_notice_approval_events.sql`，SHA256 仍为 `a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756`。CLI 只允许完整 V38 后显式申请 V39；未 repair 历史、未访问原服务器、API/Worker 不自动迁移。
- 组合 API 版本为 `0.59.0`，同时保留上游企业微信路径/类型与新增审批路径/类型。双方原独立分支都曾标为 0.58，因此不把旧 0.58 证据误作组合版本验证。
- D09 存储后续候选保留 V40；本提交不包含 V40，不声称报告持久化完成。

## 验证边界

源码/契约/隔离单元测试，以及新鲜 PostgreSQL 和实际 API HTTP 验证均已完成。UI 实际证据另记；没有企业微信实际发送或扫码登录，没有生产批准、原服务器部署或完整业务验收声明。

- 上游 V1–V38 字节与旧审批历史证据字节：一致。
- Python 迁移入口/bootstrap：34 项通过。
- Node 契约及审批契约：140 项通过。
- Java 全 reactor：3196 项，2262 执行通过、934 环境门控跳过，0 失败/错误。默认 Mockito 自附加先失败；使用现有显式 javaagent 后全 reactor 通过。
- Web：164 文件、2250 测试通过，typecheck、生产构建及发布/安全策略静态检查通过。
- PowerShell：本机无 pwsh，未运行；迁移入口清单另由 Python 校验。
- 汇总：[本谱系隔离验证](evidence/upstream-wecom-integration-20261019.json)。数据库/HTTP 等真实验证不包含在本汇总内。

## 新鲜数据库与实际 HTTP 验证

- 固定测试快照 `7d687ba1e4ce46dc26c9ff29b28d3aaf146e5f3e`；组合 API jar SHA256 `88e5138ffba1bfd85dbb2f26e5e51326548bf05f8236f399a71ca0ff8dd4d1e1`。后续合并仅文档，不重新构建或改动测试中的代码。
- 全新 dot 云端 PostgreSQL 17.9 / ICU 76.1 库显式迁移 V1–V39；源文件和编译资源均为 39 个唯一连续编号，全部上游 V1–V38 原字节一致。企业微信 V38 应用 1 次、重复 0 次、只读 0 次。
- 审批 V39 于 `2026-10-19T08:16:59.566643Z` 应用 1 次、重复 0 次、只读 0 次，SQL SHA256 保持上述值。92 张旧表指纹不变，配置表仅增加预期 `change_notice.approver_policy=null,pending` 登记项；新增审批事件表。
- 21 项真实 PostgreSQL 审批测试全部通过，0 失败、0 错误、0 跳过；43 项真实 API HTTP 检查全部通过，覆盖本地登录、schema readiness、双人审批、策略发布、自审拒绝、幂等/CAS、跨租户 404、历史分页及执行标志保持 false。
- 企业微信 schema verifier 通过；所有 WeCom 开关为 false，没有加载供应商凭据或实际发送/扫码。可选存储/发布组件未配置，readiness 如实返回 `production_ready=false`。
- 仅使用回环端点及合成数据。API、PostgreSQL 均已关闭，临时库和凭据已删除。旧审批 V38 数据库已删除且从未共享/部署；本次是全新谱系建库，不是对既有数据库重编号。
- [新鲜服务验证汇总](evidence/upstream-wecom-integration-20261019/summary.json)、[逐项 HTTP 结果](evidence/upstream-wecom-integration-20261019/approval-api-http-evidence.json)、[21 项 PostgreSQL 结果](evidence/upstream-wecom-integration-20261019/approval-pg-tests.json)；同目录含可复现无凭据脚本与 SHA256SUMS。此前历史证据保持不变。
