# N01 人工死信处置与原命令恢复

由本dot继续用户明确授权的剩余开发，fetch基线为报告工作列表已合入的main `62d05216`。使用既有检出和 `dot/gap-continuation-20261024-1153` 分支，逐功能提交推送。没有新会话、部署、迁移、供应商调用或真实设备操作。

## 业务行为

通知路由页的投递日志详情，在当前状态为FAILED且当前成员满足权限时提供“将失败记录转为死信”。明确填写原因并二次确认后，仅追加本地FAILED声明的人工DEAD_LETTER处置。UNKNOWN、QUEUED、ACCEPTED、DELIVERED及已处置状态均不能直接转死信，版本变化409。原历史、迟到回执和后续真实送达声明按既有不可变V50规则保留；不会伪造认证回执、撤回消息、启动新请求或授权重发。

`POST /api/v1/notification-deliveries/{id}/dead-letter`仅接收expected_version（0–11999整数）与非空reason（最多1000 Java字符）。必须有当前会话、CSRF头和单个规范UUID Idempotency-Key；拒绝租户/字段覆盖、重复JSON/幂等头和查询参数。当前padmin还须具备configurations:write、ticket_notes:read_internal、audit:read。

新增 `MOP_DELIVERY_DEAD_LETTER_ENABLED=false` 与独立 `MOP_DELIVERY_DEAD_LETTER_POOLS` 私有租户→资源池名单。既有读取开关也必须开启，有效写范围为独立写名单与既有读取名单交集，读权限不自动升级为写权限。默认不启用、空名单不视为无数据成功。无新增角色授权或凭据。无秘密示例在 `config/delivery-dead-letter.env.example`，实际配置不入Git。

## 原子性、权限与回执

复用PostgresPlatformRepository的原命令事务与idempotency表，平台记录按当前actor/tenant/route/key及包含投递/版本/原因的摘要绑定。每次新写和重放都先做当前账号/成员/权限、独立资源池与V50结构守卫。跨租户或未授权池隐藏为404；配置缺失503。

V50仓储新增仅允许DeadLettered的包内借用入口，要求调用方已开启READ_COMMITTED可写事务，禁止在自动提交/只读/RR事务写入。入口不commit、rollback或新建连接；保留原授权回调、完整日志重放、根行锁、版本/原命令冲突与前后置复核。领域状态机只接受FAILED。日志追加、原因审计和原响应持久化在同一事务，审计失败由外层整体回滚，不能出现无审计的成功处置。既有独立QUEUE/START/RECEIPT入口保持。

返回DeliveryDeadLetterReceipt包含明确tenant/actor/delivery/pool/command、applied_version、原因及applied_at，recorded_status_at_application=DEAD_LETTER，receipt_scope=ORIGINAL_COMMAND_NOT_CURRENT_HEAD。applied_at为服务端处置观察时刻，不冒充供应商事件时间或数据库行记录时间。provider_receipt_verified、automatic_send_enabled和fresh_send_authorized均false。同一命令重放返回原应用回执，不把后来日志头包装成原操作结果。同键不同原因/版本409；后续状态应另读已有日志详情。

控制器返回前再次核验完整身份；若操作后撤权或回执身份/字段/时刻不符，返回503 DELIVERY_DISPOSITION_RESULT_UNCONFIRMED而不泄漏回执，不把可能已提交的操作谎报为确定失败。客户端必须保留同一原命令，不能换键重复。正常前置鉴权失败仍按401/403处理。

## 交互与安全恢复

确认弹窗绑定精确投递ID和版本，原因及勾选确认缺一不可；选择或版本改变会撤销旧确认。共享durableCommand生命周期先保存命令再发送，同键原请求在响应丢失、超时、错误成功回执和刷新后恢复。成功回执逐字段核验身份、资源池、版本、原因和不外发标记，失败不展示假成功。

等待可以在弹窗内停止，立即保留并恢复原命令，焦点回到核对按钮；结果未知时也能关闭弹窗而不丢弃原请求，页面保留待核对提示。新增提交保持锁定，导航/切租户遵循既有未确认操作门禁。撤权、账号/角色/模式变化清除内存展示并丢弃迟到结果。已确认处置后独立读取最新历史失败，不撤销原命令已成功记录的事实。

现有DeliveryDispatch对FAILED/DEAD_LETTER仍返回MANUAL_REVIEW，不把此处置当重试预算或发送授权。既有只读列表/详情/原命令GET保持只读，本功能是单独显式POST。

## 验证与范围

最终数字与源码摘要见 `docs/evidence/delivery-dead-letter-disposition-20261024/validation.json`。覆盖关闭开关/缺名单、读写范围交集、资源级重放、幂等摘要、借用事务所有权、审计错误向外层传播、CSRF、原键恢复、响应错配/额外字段、操作后撤权、UNKNOWN保护及弹窗取消/焦点。协议双替身不冒充真实PG事务。

扩展既有显式隔离的PostgresDeliveryAttentionIntegrationTest，增加实际V50+审计+idempotency同事务、同命令并发收敛和审计故障回滚。该测试仍要求已有受控隧道、受限身份和新SYNTHETIC命名空间核验；当前dot没有这些配置，新增3项实际PG检查NOT_RUN，不重跑原实际租户或旧fixture。

浏览器规格在1440/1024使用开发专用合成入口、拦截全部API，覆盖确认、响应丢失原键恢复、撤权与axe。脚本发现不等于执行；本环境前一切片Chromium因socket权限无法启动，受控提升也在执行器初始化失败，因此本切片浏览器执行NOT_RUN。没有绕过权限或把jsdom计为浏览器通过。

首次编译发现Java局部var复合声明语法错误已拆分；匿名HTTP测试最初缺CSRF，因此先被403拦截，已提供有效CSRF专门验证匿名401，未改服务安全行为。前端测试双替身的Promise<unknown>与泛型请求签名不匹配，收窄为实际只需要的unknown返回接口，运行逻辑不变。上述失败与复测分别保留。

V1–V55冻结SQL无变化、0迁移、0部署、0供应商调用。本切片核销N01人工本地失败处置，不核销供应商认证、原供应商请求查询、受控重试/重派发、正式发送或客户验收。

## 最终代码验证

Java5468发现/4294执行通过/1174条件跳过，Web349文件5633项通过，Node契约285项、离线DEMO18项通过；类型/构建/静态策略与契约生成一致性通过。专项60项后又在全量中新增验证4个异常回执场景；数目不重复相加。浏览器发现2项，不计执行；指定PG新增3项仍跳过。
