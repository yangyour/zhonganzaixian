# N01 通知结果不明、失败与死信待核对清单

通知路由页的投递日志新增“待核对（结果不明／失败／死信）”及三种单独状态筛选。该清单核验当前授权资源池中的完整有界日志头，再计算匹配数量、游标和当前页，能够找到普通日志第一页之外的旧失败记录。可继续使用已有详情、不可变历史与原命令核对，支持原页重试、取消和撤权清理。

## 仓库与范围

众安在线MOP；独立目录 `E:\work\wxw9\zhonganzaixian\.worktrees\remaining-gaps-20261024`，规范origin `https://github.com/yangyour/zhonganzaixian.git`，组织路径 `yangyour/zhonganzaixian`，分支 `codex/remaining-gaps-20261024`／上游 `origin/codex/remaining-gaps-20261024`。fetch去重后基线为已完成阅读看板的 `1654838c`，与 `origin/main` 一致。复用已冻结V50日志，V1–V55不修改，无迁移、部署、供应商访问或新运行开关。

## 实际接口与完整性

新增 `GET /api/v1/notification-deliveries/attention`，可选 `status=ATTENTION|UNKNOWN|FAILED|DEAD_LETTER`（默认ATTENTION）、resource_pool_id、成对before/before_id、page_size（默认10、最多20）。旧 `/notification-deliveries` 列表语义不变。

沿用当前padmin、`ticket_notes:read_internal`、`audit:read`、已有显式读取开关及服务端租户→资源池白名单。运行连接必须是既有受限 `mop_test_runtime`，验证V50结构/ACL/触发器及完整日志链；数据库RR事务内在读取前后核验成员/权限版本及资源池范围。跨租户或未授权池统一404，未配置不冒充零记录。响应no-store，不提供POST重试或发送操作。

同一REPEATABLE READ事务完整枚举所选池的根，按首次数据库入账时间和delivery UUID倒序；逐根核验规范字节、摘要、版本连续性及状态机重放。之后才按真实当前头筛选、计数与分页。UNKNOWN与FAILED分别保留；当前头已成为DELIVERED时，即使历史包含FAILED/DEAD_LETTER，也不再列入待核对清单，原历史仍可查。

技术保护为最多1000个根、全部根合计24002条日志修订；单条链继续沿用12001行限制。根或总修订超预算返回422 `DELIVERY_ATTENTION_SCAN_LIMIT`，要求缩小资源池范围；损坏/超长单链返回已有503。不能截断扫描后给出“完整匹配数”，减少page_size也不能绕过完整人群预算。这些是读取保护，不是客户重试预算、发送限额或容量验收。

返回 `DeliveryAttentionPage`，在原列表字段上增加status_filter、scanned_roots、population_scope=`COMPLETE_BOUNDED_VERIFIED_JOURNAL_HEADS`、root_scan_limit=1000、revision_scan_limit=24002。total_count为完整匹配数，scanned_roots为本次实际扫描根数；不是供应商异常总数。前端复核筛选、人群标记、预算、记录状态、租户/池、顺序和游标后才显示，筛选变化清空旧结果并从第一页重新显式读取，失败保持原筛选/池/游标用于重试。

V50的provider_receipt_verified=false、automatic_send_enabled=false、provider_lookup_invoked=false和verified_delivered_count=null保持。DELIVERED仍只是尚未认证的日志声明，查询不到或仍UNKNOWN不授权重发。人工死信处置写入、可信原请求查询、受控重试与供应商认证仍需独立实现及验证。

## 验证

详细命令、计数、失败复测、文件摘要和截图见[验证证据](evidence/delivery-attention-20261024/validation.json)。

- Java/MVC/JDBC检查覆盖严格参数、当前权限、确切路由优先级、只读方法、状态错配、完整回放、1001根拒绝、包含不匹配链的总修订预算和故障回滚。超长单链的首轮测试错误期待总预算错误，已按实际单链保护修正；没有放宽保护。
- 指定LAN PostgreSQL17.9／活动V55，新建并核验两处独立SYNTHETIC租户和无登录凭据主体，3项真实事务全部执行通过：普通第一页均为QUEUED时仍查到旧UNKNOWN/FAILED/DEAD_LETTER并完整分页；迟到DELIVERED声明改变当前清单但不覆盖历史；未授权池、跨租户对象和缺成员拒绝。回执是明确隔离声明，不代表实际供应商。其他租户日志摘要、指定实际租户保护摘要和迁移历史不变，测试主体停用，连接/隧道与自有环境锁释放，记录保留。
- Edge1440/1024使用合成身份和拦截API，验证显式筛选、页外恢复、相同游标失败重试、切换筛选后清空、撤权和限定区域axe，最终截图逐一查看，无页面横向溢出。不是实际API浏览器、供应商联调或全站验收。

本功能核销N01只读待核对队列，不将人工处置、重试/死信写工作流、真实认证回执、持续派发或正式合同验收标完成；N01仍为IN_PROGRESS。既有读开关/资源池配置需明确提供，代码合入不自动启用或部署。
