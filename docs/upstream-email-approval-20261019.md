# 用户邮件基线与独立审批合入（2026-10-19）

- 基线：`b72c3cff2f030812926f4dee9944262a47205d7d`。保留用户企业微信、邮件、监控与部署实现；仅公共迁移入口、健康检查与契约生成进行增量组合。
- 用户 V1–V39 SQL 逐字节不变；V39 邮件 SHA256 `3d1b4cd557b6c367b2a36e9f49a2154d8a925a8afe249c103cd59373f32a6dea`。
- 本功能是 D10 审批完整链路，带入此前已验证 D09 纯计算核心；不包含 D09 报告持久化、API 或 V41 候选。
- 历史审批 SQL 曾在 dot 可丢弃隔离库以 V38、后续 V39 验证。旧分支及 `docs/evidence` 所有历史证据保持原字节；这些不是本新谱系 V40 的已执行证明。
- 本新谱系仅添加 `V40__immutable_change_notice_approval_events.sql`，原始 SQL 字节不变，SHA256 `a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756`。完整邮件 V39 后才可显式申请审批 V40；本次新鲜隔离验证已证明重复及只读均 0 迁移。不执行 Flyway repair，不操作原服务器。
- D09 报告存储下一独立功能预留 V41；原候选另行移植，不在本功能混入。V40/V41 的协调预留不代表用户已确认暂停其本地迁移编号安排。
- 上游邮件 API 0.59 与旧审批 API 0.59 合并为 API 0.60；生成器同时保留企业微信、邮件、审批路径和 schema。
- 用户最新要求每个功能及时提交并汇报。当前功能独立提交和测试，不等待报告模块批量发布；远程发布由主线程处理。

## 验证边界

纯测试、类型检查、构建、byte/hash 检查及新鲜真实 PostgreSQL/HTTP 验证均已完成。禁用邮件和企业微信外部发送与登录；不使用生产凭据、不声明送达或生产批准。

Python 迁移入口/bootstrap 34 项通过；组合契约 142 项通过；Web 2254 项通过；typecheck、生产构建、发布和安全策略静态校验通过。Java 全 reactor 3229 项，2289 执行通过、940 环境门控跳过，0 失败/错误；另有 21 项真实 PostgreSQL 审批测试和 43 项实际 HTTP 检查全部通过。全部上游 API 路径、企业微信/邮件 schema 保持不变；既有 ReadinessResult 仅增量增加审批就绪结构。历史证据不变；新结果使用新的独立目录。

代码测试汇总：[本谱系结果](evidence/upstream-email-approval-code-tests-20261019.json)。

## 新鲜 V40 迁移与实际服务验证

- 固定源代码/编译快照 `512f2bce21d238e11769a21ae80c1034a4b92115`，API jar SHA256 `699c5423958d9de6ce0d0c564ba2650ddb9b1b2c1bfe4d587ee56bcaaa3c43c3`。
- 上游 V1–V39 原字节完整匹配；源文件与编译资源一致，V1–V40 恰为 40 个唯一连续编号。全新 PostgreSQL 17.9 / ICU 76.1 库先应用上游 V1–V39；Email V39 重复/只读各 0 迁移。
- 审批 V40 于 `2026-10-19T10:34:23.114985Z` 显式应用 1 次、重复 0 次、只读 0 次。94 张旧表指纹保持一致，配置表仅增加预期 `change_notice.approver_policy=null,pending`；新增审批事件表。
- 21 项真实 PostgreSQL 审批测试：0 失败、0 错误、0 跳过。43 项实际 API HTTP 检查通过，涵盖本地登录、审批 schema readiness、策略发布、双人审批、自审拒绝、CAS/幂等、跨租户 404、历史分页和执行标志 false。
- 使用仅回环服务与合成数据；Email/WeCom 全部禁用，启动环境仅允许基本工具变量和临时运行库配置，不继承供应商配置/秘密。可选存储/发布组件未配置，readiness 如实报告 `production_ready=false`。
- API、PG 均停止，临时库和凭据已删除。历史 V38/V39 审批库早已删除、从未共享部署；旧证据保留，不对既有数据库重编号。
- [验证汇总](evidence/upstream-email-approval-20261019/summary.json)、[PG 21 项结果](evidence/upstream-email-approval-20261019/approval-pg-tests.json)、[HTTP 43 项结果](evidence/upstream-email-approval-20261019/approval-api-http-evidence.json)。同目录含哈希、迁移/清理证据与仅审批复现脚本；不含报告迁移或报告测试。
