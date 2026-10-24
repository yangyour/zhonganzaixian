# N01 未派发通知请求取消

通知路由页新增“取消未派发请求”。具备相应权限的操作人选择当前业务来源请求、填写取消原因并明确确认后，服务端同时追加请求CANCELLED修订、已有未开始路由的CANCELLED修订和原审计链记录。原请求、路由及站内消息保留；此操作停止后续内部路由规划，不撤回已登记或已发送的供应商请求。

## 项目与运行范围

项目为众安在线MOP；独立目录 `E:\work\wxw9\zhonganzaixian\.worktrees\remaining-gaps-20261024`，规范origin `https://github.com/yangyour/zhonganzaixian.git`，组织路径 `yangyour/zhonganzaixian`。当前分支 `codex/remaining-gaps-20261024`，上游 `origin/codex/remaining-gaps-20261024`；开始时HEAD与fetch后的main均为 `af261cbe`。期间远端新增 `dd51b1c4` 的schema文本保真和精确目录守卫修复，按正常合并复用，不重复开发监控线功能。

复用V51控制日志及V50投递日志，没有新增迁移或改写V1–V55 SQL。开始时另一独立结构核验持有migration.lock，本功能不申请该锁、不占用新V号。指定LAN PostgreSQL17.9的活动V55用于新增且已核验的SYNTHETIC命名空间业务验证；没有安装替代组件、写实际客户虚构数据、启用供应商、执行设备操作或部署。

## 实际API

新增 `POST /api/v1/notification-routing/requests/{id}/cancel`，JSON严格为 `expected_version`（0–99999整数）、`reason`（明确非空白、最多1000个Java字符）。要求标准会话、CSRF头、单个规范UUID Idempotency-Key，以及当前租户 `configurations:write`、`ticket_notes:read_internal`、`audit:read`。沿用原 `MOP_NOTIFICATION_ROUTING_ENABLED` 开关，不因构造服务或代码合入自动启用。

成功返回原 `NotificationControlEntry`，kind=REQUEST、原命令ID、version=expected+1；state保留原message/intent/receiver/phase，新增取消原因、操作人、服务端毫秒时刻和取消路由数，external_dispatch_enabled=false。客户端不得指定租户、操作人、路由、取消时刻、供应商状态或审核事实。拒绝非法/重复/额外字段及查询覆盖，跨租户对象404；已有投递根或开始处理的路由返回409 `NOTIFICATION_CANCELLATION_BLOCKED`。未知结果必须保留原path/body/key，不能根据当前状态GET推断原命令是否成功。

## 事务、并发和审计

取消只接受WAITING_CONFIGURATION或ROUTED请求。锁定请求根，核对当前版本与原命令；对同命令的锁等待后再次读取已提交回执，使并发的相同命令收敛到同一个原回执。不同命令竞争相同版本时只允许一个成功。同命令修改原因或预期版本拒绝，重放不追加路由或审计。

请求与其最多6类渠道路由按服务端确定的身份关联，逐条核对message、intent、receiver、phase、channel和明确attempt=0；路由必须仍为QUEUED或WAITING_CONFIGURATION。任何一条不合格均拒绝整个命令，写入前完成全部校验。取消后已有规划入口沿用CANCELLED门禁，不重新创建或激活路由。

取消与V50新投递登记共用tenant/intent/receiver/phase事务 advisory lock。等待方必须使用READ_COMMITTED，以便锁取得后读取胜者已提交的结果。若投递登记先完成，取消读到V50根后拒绝；若取消先完成，投递登记完整核验当前V51控制记录后拒绝。两者不能同时成功；无V51的历史V50独立库保留原逻辑，存在V51时必须通过冻结结构/ACL守卫。该互斥不依赖浏览器布尔值或未提交的状态。

审计复用原 `PostgresPlatformRepository` 哈希链与租户顺序锁，借用控制日志的同一Connection，不单独提交。成功记录 `notification.request.cancel`、对象ID、原因、当前操作人与trace；审计写入失败则请求、所有路由与审计一起回滚，借用结束清理线程事务引用。原始日志、历史修订和站内消息没有覆盖或删除。

路由启用后的就绪检查同时核对V50/V51冻结结构、审计SELECT和INSERT权限，以及READ_COMMITTED事务隔离。缺少依赖返回未就绪；禁用的候选继续不要求启用这些组件。配置策略、正式接收矩阵、客户批准和供应商发送许可没有自动补值。

## 页面、恢复与验证

沿用现有桌面页面和弹窗，取消原因不预填，确认前不写入；已取消请求不再显示重新规划/取消按钮。未提交原因阻止离开，明确“放弃取消”才清除。未知写入在当前弹窗及重载后的原命令入口可核对或按同请求重试，原命令回执必须匹配对象、版本、状态和准确原因。账号/权限/租户变化清除旧界面并丢弃迟到结果。持久命令键使用稳定账号/租户/权限范围，页面读取另带context generation，解决重载后generation变化导致原命令丢失的问题。

分层计数、命令、源文件摘要、失败和复测见[验证证据](evidence/notification-request-cancellation-20261024/validation.json)。

- 指定PG的8项实际场景覆盖请求/两渠道/审计原子保存和精确重放、已有V50根拒绝、取消后投递拒绝、取消与登记并发、两个不同取消命令竞争、两个相同命令收敛、审计INSERT失败的完整回滚，以及跨租户/陈旧授权；新主体停用，其他租户投递日志摘要、实际租户保护摘要和迁移历史保持。来源是明确隔离控制夹具，不冒充真实客户消息或供应商回执。
- Java全量、MVC/CSRF/严格输入、旧通知与投递仓储回归、Web全量及契约检查分别记录。真实组件测试未执行的条件跳过另列。首次MVC测试误把CSRF放入查询参数被严格参数检查拒绝，已改用应用实际使用的header；Mockito重新设桩与新增就绪依赖的旧替身也已修正，没有放宽保护。
- Edge1440/1024、响应丢失重载及已有投递登记拒绝共4场景使用明确合成身份与拦截API。覆盖先填原因再确认、键盘、取消后状态、同原命令恢复、拒绝后保留原因、限定弹窗axe和截图人工查看。首次采样发现新弹窗标签/按钮颜色及开启过渡期对比度，调整新弹窗局部颜色，并在真实过渡结束后检查，最终通过。不是实际API浏览器正向写入、供应商撤回或全站无障碍验收。

本功能核销N01的未派发请求取消，人工死信处置、受控重试、可信供应商认证/查询、持续派发与正式合同验收仍未完成。当前代码提交不等于远程发布，M7、手机、客户正式值与原组件门禁保持。
