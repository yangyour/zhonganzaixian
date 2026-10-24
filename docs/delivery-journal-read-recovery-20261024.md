# N01 投递日志、不可变历史与原命令只读核对

V50回执核心与单次派发核心原先没有面向用户的读取装配。本批新增受限DataSource读取器、明确资源池读取范围、实际GET API、通知路由页紧凑子区，以及完整页/历史/原命令核对测试。不是新增发送器、供应商查询器或人工死信处置动作。

## 边界与调用链

- 当前padmin且同时具有ticket_notes:read_internal、audit:read；服务端私有MOP_DELIVERY_JOURNAL_READ_POOLS按租户列出允许池，未配置不是“零记录”。另有MOP_DELIVERY_JOURNAL_READ_ENABLED，默认false，不依赖V51路由启用而自动开启。
- 数据库实际current_user与session_user均须mop_test_runtime，复核V50冻结结构、ACL、不可变触发器与函数体；不接受高权限或换身份连接。当前成员/版本/能力使用share锁，并在返回前重查完整成员及账号边界。
- 列表仅当前租户、明确允许池和version=0根，完整count与游标页在同一REPEATABLE READ事务。每页默认10、最多20；逐条复用V50完整链/原命令/摘要验证，不重复发明松散解码器。页内完整日志预算24,002条，超过则整页422，不能返回截断成功，可减少page_size。
- 不以当前页筛选状态冒充完整死信队列，因此本批不提供status过滤。total_count是当前允许池的V50本地根数，不是所有渠道、供应商或最终送达消息数。
- 详情复核完整链后以不可变version倒序分页，最多100条，包含QUEUE根与START/RECEIPT/DEAD_LETTER记录。分开保留声明的occurred_at/observed_at与数据库recorded_at；当前头可能在下次请求变化，不宣称跨请求冻结。
- 原命令GET同时返回原应用版本、原请求ID和当前头状态，严格按当前投递/租户授权。未找到本地命令返回404，不证明供应商未收到，不能改用新requestId重发。
- 不返回原二进制、供应商payload、收件地址、token、原命令actor权限或trace正文。只给不透明引用、状态、时间及完整性摘要。

V50固定provider_receipt_verified=false、automatic_send_enabled=false不变。即使recorded_status=DELIVERED，页面仍显示“送达声明，尚未认证”，verified_delivered_count保持null。ACCEPTED只是受理记录；UNKNOWN结果不明不能被重新发起。原命令核对固定provider_lookup_invoked=false、fresh_send_authorized=false，是本地只读核对而非供应商确认。

## API与页面

- GET /api/v1/notification-deliveries：可选resource_pool_id、成对before/before_id、page_size。
- GET /api/v1/notification-deliveries/{id}：before_version、page_size；当前头加不可变命令页。
- GET /api/v1/notification-deliveries/{id}/commands/{commandId}：无query，核对精确原命令，不提供POST重试/发送入口。

通知路由页新增“投递日志与原命令核对”子区，不改菜单/主题。手动读取、取消、原读取重试、池范围、每页数量、完整分页、历史与原命令跳转；上下文/账号/权限变化立即清空并丢弃迟到响应。不存在本地记录、配置缺失、读取失败分开；不把它们显示为供应商未收到。未创建DEMO数据或账号。

## 验证和剩余

见docs/evidence/dot-continuation-20261023/n01-journal-read.json。初次独立MVC测试缺少SecurityConfiguration所需ObjectMapper Bean，已在隔离test profile补齐，未关闭安全链或改变权限断言。JDBC/MVC/jsdom为代码级验证，真实PG/OIDC/供应商/浏览器尚未联调；没有迁移、部署、外发或实际客户写入。

N01仍需：V51路由到可信V50派发装配、实际供应商适配/签名与原请求查询、受控重试及人工死信处置、经过批准的调度与配额。V50的未认证约束不能被当前API改写为真实送达证明，后续可信证明须另行协调迁移/领域方案。正式验收不核销，不能将本批只读核对称为通知闭环已完成。
