# 历史分支整合与本地验证（2026-10-21）

## 范围与来源

- main 起点：cca35c19c768cc82e053389fc756a6659730d672。
- 合入来源：feat/d07-ticket-lifecycle 的 4b126e98d4c4237193a058d68bc4d3cbcc9ef92a。
- 该来源包含 dot/d09-inspection-report-20261019、dot/d10-change-approval-20261019、dot/d11-notification-channel-20261019、dot/parallel-integration-20261019 的全部已查明提交；其他已列举分支已经在 main 历史内。
- 三方比对基点 ee34e6b05a5569e7c445347d5fed240dae544d3b，包含 573 个单边变更和 22 个双边同路径变更。不是覆盖 main，也不是只复制客户文件后宣称整个分支合并。
- 保留 642afcaa 对 Cloud validation 及专用 Maven pin 测试的删除，不恢复该工作流。合并提交标记 [skip ci]；本地验证不冒充云端检查通过。

## 冲突处理

1. 保留现有七态 TicketLifecycle；历史六态改名 WorkflowLifecycle，调用方与测试同步改名。两者不可静默转换状态。
2. 历史工单 SLA 读入口改为 /api/v1/tickets/{id}/workflow/sla，避免与现有 /api/v1/tickets/{ticketId}/sla 冲突；历史 Web reader、契约和测试同步调整。既有七态生命周期及参数替换预览/脏数据保护保留。
3. 合并内部事务作用域、权限目录及按实际表存在性计算的权限上限核验；没有给真实用户分配新角色。
4. 保留 V1–V41 已执行谱系原始 SQL 字节，逐文件 Git blob SHA 核验一致。原 main 未执行的 V33–V36 候选原件及其 pin 存档在 docs/migration-lineages/revision-candidate/，不被 Flyway 扫描。
5. 在已冻结谱系之后登记 V42 生命周期修订、V43 自动关闭、V44 SLA 策略绑定、V45 提醒候选。V43/V44 保留原 SQL 内容，V42 添加跨模型归属保护，V45 的 SLA 意图表/函数使用独立 mop_sla_notification_intent 名称，避免覆盖历史通知表。新资源/函数 SHA、迁移选择器、前置版本、精确文件集合及部署脚本均同步。
6. V42 归属表以 tenant_id + ticket_id 唯一键决定工单模型；回填历史 workflow 工单，触发器阻止两个模型同时占有同一登记凭证，数据库专用 SQLSTATE P7401 对外转换为不含 SQL 细节的 409 WORKFLOW_MODEL_CONFLICT。没有自动转换既有工单或修复 Flyway history。
7. OpenAPI 0.66.0 重新生成；合并两边追踪证据而不重复需求编号，128 项正式验收仍全部 NOT_RUN。

## 必须保留的运行边界

V42–V45 本轮没有在真实 PostgreSQL 中应用。SQL/PLpgSQL 语法解析、单元测试和编译不能证明并发归属保护、数据库 ACL 或迁移执行成功。未启动数据迁移、设备采集、邮件、企业微信或其他外部发送。

若任何环境实际运行过原 main 的 revision 谱系 V33–V36，应停止升级并另做明确的数据迁移方案；本次 CLI 会拒绝其与规范谱系不匹配的历史，不允许 repair、覆盖旧 checksum 或将版本号直接更改为 V42–V45。已应用 workflow V1–V41 的环境也必须逐项显式验证新候选，不能启动应用时自动执行。

## 本地验证

详见 docs/evidence/branch-integration-20261021.json。完整 Java 构建/测试、Web 单测、契约、静态发布门禁及 Python 工具检查分别留证；跳过测试不算验收。新增编译后控制器路由唯一性检查，覆盖变量名不同但 URL 结构相同的冲突。

前端 preview 在同一进程网络环境启动后返回 HTTP 200（854 字节 HTML，存在 app 根节点），随后终止；这是静态服务验证，不是浏览器操作或 API 联调。跨命令网络上下文连接失败也已记录，不将它当成应用失败或已完成浏览器验证。

API 实际启动在缺少 SPRING_DATASOURCE_URL 时 fail-closed 退出 1；数据源三项配置均缺失，当前环境也未发现 postgres/initdb/docker 可执行文件。数据库及对应真实集成测试按用户要求跳过并记录。未提供替代内存仓储冒充数据库。PowerShell 不可用，其运行验证跳过，Python 已检查迁移清单与边界声明。

## 客户资料下一步

两份原始文件已保存且不改写。CMDB 工作簿实际列出 9 个 L1、16 个 L2；末尾文字称 15 个 L2，与实际条目不一致（fw_adc 标明监控清单未单列）。后续按逐行来源建立候选目录，并把监控映射歧义显式配置化，不静默删掉第 16 项或伪造映射。联系人截图仅是输入资料，不自动创建账号、授予角色或发送消息。

## 后续：真实 PostgreSQL 17.11 验证

已在自有临时回环库完成 V1–V45 首次应用，以及 V45 重复和只读检查（均执行 0 个迁移）。105 张表在重复与只读前后指纹一致；V42 只修改既有权限上限表和 Flyway 历史，V43–V45 的既有表变化仅为 Flyway 历史。所有实际数据变化明细见 [本次证据](evidence/integration-pg-20261021/attempt-1/first-apply-freeze.json)。原始库从空库初始化，没有客户数据，不能据此证明有数据升级或业务并发行为。临时数据库/凭据已清理。V42–V45 已从候选转为该隔离环境中执行过的冻结 SQL，后续 DDL 必须新增 V46+；以上“未执行”段落保留为合并时历史。
