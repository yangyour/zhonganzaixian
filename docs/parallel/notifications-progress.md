# 通知派发与回执开发进度

2026-10-24本地最新接续：N01新增完整有界日志头重放后筛选的UNKNOWN/FAILED/DEAD_LETTER待核对列表与调用页面，复用既有详情和原命令核对。3项指定PG17.9/V55、严格参数/预算/权限和桌面交互分别留证；供应商认证、人工处置及受控重试仍未完成。见[实现与边界](../delivery-attention-20261024.md)，下方停止和旧WIP仅为历史。

## 本轮停止与归档（2026-10-23）

按用户“结束现有开发”及集成会话最终指示停止；本轮候选整体为 **STOPPED_WIP_STASHED_NOT_RELEASED**。只提交收尾记录与脱敏证据，不提交或合入未通过完整检查的业务候选，不再自动继续。

- 代码基线：`b122896b84fc18fc09a3410f66e08676b22cbbd9`，保留 `581f4a6b` DEMO 契约修正。分支 `codex/launch-notifications-20261023`；本轮没有业务代码提交到 main，也没有部署。
- 命名 stash：`mop-notifications-stop-20261023-n01-v51-unverified`；精确 SHA：`bbe6e5d11225e6f6615e2152abe805f48a0a10fb`。包含原索引、未跟踪文件在内的33个代码/测试/契约文件，涵盖 V51 SQL、守卫/迁移注册、API、路由服务与Worker、Web页面/偏好入口、恢复客户端及测试；完整候选未丢失。
- 恢复目录：`E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\notifications`。先核对该目录/分支和最新迁移边界，再执行 `git stash apply bbe6e5d11225e6f6615e2152abe805f48a0a10fb`；使用 apply 保留备份。此 stash 位于本检出本地 Git，未推送为可发布业务提交。
- **V51 已在指定 PG17.9 的独立结构克隆库执行并冻结**，源码在上述 stash 的 `server/persistence/src/main/resources/db/migration/V51__notification_routing_control.sql`；SHA256 为 `51f26c2d0d74903a10ea04525335fff8abfa8eecf1e048e1a623a535e609d37b`，已对 stash 内字节复核一致，后续不得复用编号或改写 SQL。线上 `mop_test` 仍 V50，未应用 V51，隔离库均已清理。
- 已有通过证据：61项定向 Java/迁移/HTTP检查、6项指定PG隔离流程、12项原命令客户端测试、194项契约检查、9个已记录浏览器合成断言；Web生产构建及静态策略检查通过。浏览器与供应商/真实HTTP联调分开记录。
- 完整检查未通过：Java `ProfilePolicyTest` 3项失败（集成会话已指出新MVC测试配置隔离/mapper重复问题，本线未继续修复）；Web全量5038通过、2项 `inspectionSourceCollections.test.ts` 失败（1000来源测试超时、取消时摘要调用计数3/6）。未继续定位或复跑，未标 N01 VERIFIED。结果见 [closeout.json](../evidence/notification-routing-control-20261023/closeout.json)。
- 资源终态：15213 Web预览已停止，18213 API未启动；已关闭本会话保留的Playwright浏览器/上下文；验证工具已关闭SSH隧道。自己持有的 `migration.lock` 已核对归属后释放，未持有 environment.lock，未申请主干 integration.lock，未动他人资源。

| ID | 留给总剩余文档的差异 |
| --- | --- |
| N01 | stash 已含内部规则/偏好发布、既有Email/WeCom绑定引用、持久来源发现/重核和规划调度候选；需先解决上述验证门禁，再接 V50 原命令派发、认证回执、受控重试和人工死信 API/页面。供应商资料、真实送达及外发授权只阻塞对应步骤，通用代码仍是可独立开发剩余项。 |
| N02 | 既有门户/扫码、设备、报障与工单入口尚未完成逐条桌面核销；报告入口依赖报告线。UI03/M1-8铃铛和App.vue由集成线负责，本线未重复修改。 |
| G02 | 继续 DEFERRED_MOBILE：手机触控、窄屏、移动确认/重开及门户专项未实施或验收。 |

清单自查曾通过128条覆盖、无未分配项，`completion_verified=false`；本线未改写总JSON。以下为此前已经提交的历史进度，不代表本轮 stash 已发布。

## 仓库归属核对（2026-10-23）

- 项目：众安在线 MOP；模块：通知后台消费、路由与回执恢复。
- 独立目录：`E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\notifications`。
- origin：`https://github.com/yangyour/zhonganzaixian.git`；组织：`yangyour/zhonganzaixian`。
- 功能分支：`codex/launch-notifications-20261023`；开始时上游：`origin/main`。
- 初始 HEAD 与 fetch 后 main：`05ec77ba`。依据为本检出 Git 配置、AGENTS 首节和 `docs/parallel-development-20261023.md`，未操作原工作区。

## 功能一：工单站内通知后台消费者

定向 reactor 回归：28 项执行通过，2 项旧 PostgreSQL 写测试按环境门禁跳过，0 失败/错误；明细见 [tests.json](../evidence/notification-relay-20261023/tests.json)。首次 PowerShell 未引用带点 Maven 参数导致命令解析失败，修正参数引用后正常构建；该次未执行测试，不计为测试失败或通过。

代码已接入 Worker 的独立 `--notification-relay` 模式。显式租户白名单、有界批次、公平轮询和故障续处理复用 V34 的 outbox→intent→message 原子事务；重启或提交响应丢失后仍按原 outbox 去重。接收人有效性查询持有成员与用户共享锁至事务结束，避免撤权穿过本次消息写入。

无新增迁移、HTTP 写端点或外发适配器。外部渠道继续 `NOT_CONFIGURED`；未启动常驻 Worker、部署或发送外部消息。配置与实际边界见 [后台消费者运行说明](../notification-relay-worker-20261023.md)。

验证：11 项新增轮询/配置单元测试已通过；通知相关回归结果另见证据记录。真实指定环境只读检查确认 PostgreSQL 17.9、V50、outbox 0 行、V50 journal 0 行、运行账号具有来源与日志读取能力。没有核验可用于本轮写测试的独立租户及身份；旧集成测试固定指向已切真实数据的租户，未启用。不能把只读盘点和隔离单元测试作为真实投递联调或正式 TC。

## 功能一提交记录

功能一提交 `2b32f957`，正常合并主干后组件登记提交 `6c47dc03`；功能分支已吸收主干，集成锁已释放。合并后定向回归再次通过；保留生产检查会话同期改动，无强推或部署。

## 功能二：V50 原命令恢复与单次派发消费

新增仓储原转移命令恢复接口及绑定真实 PG 仓储的 `DeliveryDispatch`。首次明确 Started 提交才允许调用适配器；已有命令、提交结果不明、网络结果不明只核对原 request。失败/死信进入人工处理状态，重复观察回放原命令和原版本，ACCEPTED 不冒充 DELIVERED。禁用时无数据库或供应商调用。

93 项定向测试执行通过，2 项 PG 环境测试跳过；其中新增 13 项消费者故障/重放与 4 项仓储授权/恢复故障测试。V1–V50 无变更，无迁移；实际供应商、后台持续调度、可信签名和真实 PG 写并发未因此完成。详见 [内部契约与验证](../notification-dispatch-recovery-20261023.md)。

## 收尾复核与集成

收尾复核新增“未知命令不能借用另一命令成功结果”的保护：已有尝试但查不到所请求的原命令时返回 409；原命令重放仍返回当前状态。新增 1 项测试，最新联合回归执行 111 项、4 项旧环境测试跳过，无失败/错误。

功能二代码提交 `f69fa0dd`，合并 `main` 为 `1e38de8b`；合并后联合回归执行 110 项、4 项旧环境测试跳过，无失败。随后补充指定 PG17.9/V50 的 6 项真实并发/恢复测试全部通过，独立合成租户保留 15 行日志，其他租户回执日志仍 0 行；外发 0、迁移 0。首轮测试授权器权限假设错误及修正后成功记录均保留，详见功能二文档的指定 PostgreSQL 补充节。环境锁已释放，未部署。

真实 PG 验证提交 `64d02b08`、命令关联修正 `7a079737`、打包工件检查 `c8470079`。最终在集成锁内以最新主干 `edc7b4ab` 合并，测试代码头 `356f2d85` 再次通过 111 项执行／4 项旧环境跳过，0 失败/错误；见 [最终集成证据](../evidence/notification-dispatch-20261023/final-integration.json)。没有改写 V1–V50、触碰原工作区前端、强推或自动部署。真实 PostgreSQL 6 项与本地 111 项独立记录，不将模拟供应商计为外部联调。

## 仍需继续的本线范围

路由发布/绑定的事务核验、持久路由队列发现与调度、具体供应商可信回执、受控失败重试与人工处置 API/页面仍未完成；客户正式矩阵、接收人、回执协议、额度及自动外发授权继续独立登记。128 项合同状态不因本次局部功能变更而标 DONE。
