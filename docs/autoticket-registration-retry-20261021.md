# 自动建单登记后台重试与可读回执

基线 main 371bf2c。此 Worker 仅处理本库的监控工单登记，不启动正式生命周期/SLA，不调用外部 ITSM、短信、邮件或企业微信。默认没有启动它，也没有替用户配置运行身份。

## 已实现的运行流程

- 独立入口：Worker JAR 参数 --autoticket-registration-retries。它在 Spring 文件 Worker 启动前分流，不注册照片、导入、通知或其他队列消费者；多余命令参数拒绝。
- 仅允许 remote_test、回环 PostgreSQL 的 mop_test、受限 mop_test_runtime。租户与执行身份必须明确配置；每批读取当前成员，每条命令再次检查当前权限版本及内部操作能力。
- 单批 1–25 条，按持久 next_retry_at 取到期 RETRY_SCHEDULED 记录。执行时锁关联事件行，重新检查版本、数据库时间、事件恢复/处理、当前维护/上游依赖/防抖抑制及是否已有工单。
- 本库登记、历史、操作/outbox、关联、尝试更新、审计和幂等回执在同一事务；竞争失败返回明确 409。幂等身份包含尝试 ID 与版本，payload 使用 SHA-256；不会因换一个进程重复登记。
- 新原始告警信号仍正常记录，但在已计划的退避期限之前不再消耗自动建单重试次数。
- 已恢复/被抑制的到期记录转为 SUPPRESSED_SKIPPED；达到上限转人工核验；已有不一致工单关联不自动覆盖或修复。数据库或授权失败不被计成业务成功，不清空原待办。
- 页面显示数据库存储的最早重试时间（UTC）和最近处理说明，明确执行仍取决于 Worker 和实时条件。SUCCEEDED/MANUAL_CREATED 表示登记保存，不表示正式流程或送达。

这是短事务、无外部 I/O 的本库登记队列。将来对接外部服务必须另有供应商幂等、未知结果/回执与故障补偿协议，不能在本事务内直接加入外呼。

## 配置与启动边界

模板 deploy/examples/autoticket-registration-retry.json 只是待补清单，不由 Worker 自动加载。启用、租户、身份、连接、模式和批次参数均需显式提供；不会从客户联系人截图创建账号或推断执行身份。

必需环境变量：

- SPRING_PROFILES_ACTIVE=remote_test
- MOP_AUTOTICKET_REGISTRATION_RETRY_ENABLED=true
- MOP_AUTOTICKET_RETRY_SCOPE=REGISTRATION_ONLY
- SPRING_DATASOURCE_URL / USERNAME / PASSWORD：使用受控运行连接，密码不写进模板、日志或 Git
- MOP_AUTOTICKET_RETRY_TENANT_ID / ACTOR_ID：现有同租户授权身份引用
- MOP_AUTOTICKET_RETRY_BATCH_SIZE：1–25
- MOP_AUTOTICKET_RETRY_POLL_MILLIS：1000–60000，技术轮询范围，不是客户 SLA 默认值
- MOP_AUTOTICKET_RETRY_MODE：ONCE 或 CONTINUOUS；模式不可省略

运行 java -jar server/worker/target/mop-worker-0.1.0-SNAPSHOT.jar --autoticket-registration-retries。缺失或不合法配置在连接前失败；有效业务身份仍在数据库中实时验证。配置在进程启动时读取，更改环境值不会自动修改已运行进程。此文不是生产启用批准。

## 自动登记详情可用性修复（API 0.69）

联查发现旧自动登记仅写入一个 action 字段，缺少前端所需的历史身份、时间和版本；还写了占位电话。新登记现在写完整 MONITORING_REGISTER 历史，并把未绑定联系方式存为空字符串，不再编造号码。客户手工报障表单的非空联系方式要求不改变。

对于精确匹配旧生产者结构的历史记录（版本1、EVT登记编号、唯一 AUTO_CREATE_FROM_MONITORING_EVENT 动作），读取层使用同一登记行已有 reporter_id/created_at 补齐展示字段，明确标记 LEGACY_MONITORING_REGISTER。只有此精确兼容分支的旧占位电话在读投影中隐藏为未提供；普通客户填写相同数字不受影响。原数据库行和原始历史不改写。

同样仅对精确识别的旧简化 CONFIG_PENDING 登记操作回执补齐可读说明；没有原始完整阻塞项的历史回执保持空清单，并明确“未提供完整阻塞项”，不猜测所有规则已配置。未知或额外损坏的历史结构不静默修复。

前端严格读取新/历史兼容动作，区分监控登记与照片追加；详情及导出显示登记来源，缺失联系方式显示“未提供”并隐藏无意义的显示按钮。没有放宽租户/报障人、时间、版本、附件或正式状态校验。

## 验证分层

第一生命周期已验证 12 项 PG 回归及实际 ONCE Worker 三次进程运行（处理、再次运行、未授权身份拒绝），此时尚未修复历史详情；其证据单独保留，不冒充后续版本。

第二生命周期对最终代码重跑，并读取真实 API 的新监控登记、旧生产者登记、普通客户控制记录和操作回执。实际 HTTP 返回交给生产 TypeScript reader 运行；它是协议联验，不是浏览器视觉验收。最终数量与失败/清理结果见 evidence/autoticket-registration-retry-20261021/validation.json。

测试只使用全新可丢弃回环库、合成账号与合成工单。为了在有限时间检查到期逻辑，个别测试显式推进本条合成记录的 next_retry_at 并增加版本，不修改系统或数据库时钟；另有独立用例验证未来期限不能被提前消费。未测试连续运行压测或进程在提交瞬间 SIGKILL，不冒充完整故障恢复验收。

V1–V45 SQL 不变，无启动迁移，外部发送为0；真实浏览器/axe、正式工作流激活、外部对接和完整合同验收仍未完成。
