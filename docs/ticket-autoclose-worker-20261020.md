# 客户确认超时自动关单 Worker（2026-10-20）

## 实现与数据边界

V34 候选新增持久自动关单任务队列。解决方案提交在同一数据库事务中生成绑定租户、工单、解决版本、方案摘要和固定确认截止时间的任务。原始 V1–V33 SQL 字节不改；迁移只创建/回填队列，不关单、不启用 Worker。

独立 Worker 显式使用 `--ticket-autoclose` 启动，不需要照片存储，不注册 API、不使用 @Scheduled。只认 remote_test、回环 mop_test 数据库和受限 mop_test_runtime 角色。两个功能开关、连接和运行参数都须显式提供，示例见 `deploy/examples/ticket-autoclose-worker.env.example`，默认关闭、秘密为空。

- claim 使用 SKIP LOCKED、递增 fencing token 和有限租期；过期后才能重领。
- complete 先取工单锁再锁队列，核对 owner/token/尝试数和租期，重新读取当前工单与不可变方案内容。
- 客户确认或重开先完成时，旧任务记 SUPERSEDED，不覆盖最新状态。
- 数据库额外触发器要求 WORKER 关单携带当前到期租约，应用与数据库双重校验截止时间及方案绑定。
- CLOSED/TIMEOUT_AUTO 追加版本、终态队列更新与哈希链审计在同一事务内完成。审计主体为“系统工单任务”，不冒充客户或工程师。
- SQL/依赖失败回滚；租期到期后恢复，超过显式次数进入 BLOCKED_ATTEMPTS。版本上限同样终止并记录 VERSION_LIMIT_REACHED。
- 缺失或超出技术表示范围的固定确认期限记录 BLOCKED_CONFIG，不能事后偷偷修改开单快照来解锁；仍可走已授权的客户流程。

## 没有声称完成的部分

自动关单不等于客户确认、通知已送达、SLA 实时考核或生产批准。本步骤不发送外部通知、不处理其他任务。通知队列与发送适配器、客户提醒及升级链仍需后续接线。

V34 未在真实 PostgreSQL 上执行，租约竞争、崩溃恢复、真实事务与登录浏览器验证保持 NOT_RUN。单元/Mock 检查和 SQL 解析不能替代数据库验收；正式128项状态不变。

## 验证记录

完整 Maven verify 通过：2143项执行、897项环境跳过，0失败/0错误。新增14项迁移/租约边界/Worker配置测试（部分使用Mock），49项Python检查通过，API契约生成和128项追踪一致性检查通过。V34的19条SQL语句及3个PL/pgSQL函数解析通过。

实际尝试启动独立Worker：缺少显式SPRING_PROFILES_ACTIVE配置时以CONFIG_PENDING退出（exit=1），未连接数据库。真实工作循环因缺连接配置及V34未执行而跳过，不能当作Worker运行验收。

独立 CLI、Windows/Linux 迁移选择及资源摘要同步覆盖 V34，仍须显式 apply，不由 API/Worker 自动迁移。
