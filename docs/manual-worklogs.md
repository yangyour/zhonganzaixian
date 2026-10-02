# 实填工时记录的实施边界

来源：M5-D10、TC-M5-21、TC-M10-13及Q23。Q23明确允许先实现实际工时记录，同时禁止缺工时自动按0、使用演示成本/默认值形成经营结论。此模块不修改工单状态、SLA截止或客户确认规则。

## 已实现的纯输入核心

`ManualWorklog`只接受明确REMOTE/ONSITE/INSPECTION/COMMUTE类型、显式起止Instant和可为null的备注。结束不得早于开始；明确相同起止是操作者实际填入的0，与未填写起止不同。缺失时间拒绝，不悄悄采用4小时或其他默认值。

外部时间必须含秒及显式Z/offset，最多毫秒精度；规范化后UTC范围0001..9999以匹配协议和存储表示。更细精度拒绝而非静默截断。elapsedMillis仅物理经过时间，跨午夜、月界和夏令时offset不按墙钟差误算；没有按15分钟计费取整、工作日历/SLA折算或0–24小时演示限制。49小时等明确输入可以计算。备注保留原文，2000 Unicode字符上限仅技术防护，拒绝控制字符/不完整Unicode；允许换行/制表，空白不是自动说明。

6项隔离回归覆盖4类类型、49小时、明确0/缺值、反向区间、两个offset/DST场景、毫秒跨月、非法日期/缺offset/精度、协议范围和Unicode。根456=443执行通过（含既有84真实PG）+13fixture跳过、6MinIO排除。这里的84项是其他既有PG回归，不是新工时持久化证明。

## 下一步持久化与权限边界

工时V8已在niko独立执行1、重复0、只读check0，原V1–V7未改；仓储与API已实现并完成真实PG/HTTP回归，页面仍未接线，不能标工时填报完成。接续拟保存工单关联、本人执行者、实填来源及不可变修订；新结构独立迁移，V1–V7不改字节。修改必须本人、同租户、CAS、幂等和审计，不给系统管理员自动代改工时或推定Q09服务经理岗位。

默认工时分钟、适用范围、人力单价、交通成本、分摊和毛利均继续null/CONFIG_PENDING；本核心不输出成本/毛利，不把人工填写等同于外部考勤核验。缺工时的默认来源应作为独立确认配置/计算来源处理，不能用虚构起止填入实填记录。完整M5-21/M10-13及页面/毛利验收仍未执行。

## V8实际结构验证

新增mop_worklog与不可变mop_worklog_revision；同租户工单/成员复合FK、修订actor必须是固定owner、指向最新修订的延迟FK。实填source只能MANUAL_INTERVAL，4类类型及时间顺序/协议范围有DB约束；运行账号只有头记录version/updated_at列更新权，没有改owner/parent/历史或删除权限。没有增加默认分钟、成本、计费字段。

9项迁移策略、6项真实PG约束和20项Python守卫通过；根463=450执行（含90真实PG）+13fixture跳过，6MinIO排除。首次约束测试有一个INSERT占位符数量错误，修正测试后全量通过；已执行V8未改。所有约束测试数据事务回滚。详见[独立证据](evidence/niko-worklog-schema-20261002.json)。

## 2026-10-02 本人实填工时持久化

WorklogRepository及PostgresWorklogRepository已接入既有幂等事务，限定当前启用msp成员本人：父工单同租户、创建检查父版本、修改检查工时版本、固定owner/parent、不可变修订和同事务审计。本人列表/历史使用有界keyset；外人记录和错误父范围404，权限版本失效401，管理员没有自动代改权。物理经过时间不改变工单、SLA、默认工时或成本。

12项新增真实PG仓储回归通过，覆盖持久回读、49小时/显式0、两类重放、CAS、本人权限、其他owner隐藏、下游失败回滚、两个独立连接并发、分页及0001..9999毫秒边界。首次并发测试未获得预期409，定位为联表FOR UPDATE等待后旧修订快照与新头版本不匹配；改为先锁稳定头记录、再单独读取当前修订，完整回归通过。其他owner场景为显式标记的历史合成记录，未提升viewer角色或宣称viewer经API创建成功。

根475=462执行通过（含102真实PG）+13历史fixture跳过，6MinIO排除，0失败错误；断库503/无受保护事务拒写守卫同时通过。API仍V0.10，尚无公开工时端点/页面；真实OSS、浏览器及完整M5-21/M10-13验收不在本切片内。V1–V8和6个原私有配置均未修改。见[仓储证据](evidence/niko-worklog-repository-20261002.json)。上一95dca54的CI37026678180已回读success。

## 2026-10-02 本人工时API V0.11

新增同租户msp本人工时list/create/get/PATCH/history；仅该角色增加worklogs:read_own/write_own，padmin/reviewer/customer未获团队查看或代改。创建CAS父登记版本、修改CAS工时版本；每次重新授权，CSRF/幂等/不可变历史/审计，GET不写入，跨租户或错误父范围404。严格重复键/未知字段/有界32KiB请求与有界keyset查询。工时schema及列权限加入readiness，不自动迁移；liveness独立。

响应明确MANUAL_INTERVAL/SELF_REPORTED，elapsed_millis是实填起止物理差；默认分钟、人工成本、交通成本、毛利继续null/CONFIG_PENDING。无24小时上限、计费取整、默认4h、管理员代填或工单/SLA变更。

8项controller+1项readiness新增隔离测试、23项契约、Web207及类型/构建通过。最终根485=472执行通过（含102真实PG）+13fixture跳过，6MinIO排除；包含另外独立提交的解析器关闭竞态第9项测试。75项真实PG/OIDC HTTP/Ajv检查通过：49h/显式0、两类精确重放、409冲突、同租户本人权限、跨租户/错误父404、并发200/409、12条分页及父登记完全不变；另25项6个既有身份当前能力回归通过。首轮HTTP父登记误传asset_id:null导致400，修正测试为省略可选字段后全轮通过，未改报障契约。

公开接口已接线，页面仍待实施；M5-21/M10-13及全部128项仍非DONE。原服务器、OSS、浏览器阻塞保留，V1–V8和原私有配置不动。工时仓储提交ecdc95d的CI曾因既有解析器关闭竞态失败，已独立修复422f118并确认CI37030716992成功，详见[说明](parser-shutdown-race.md)。见[工时HTTP](evidence/niko-worklog-api-http-20261002.json)、[当前角色HTTP](evidence/niko-worklog-role-http-20261002.json)、[本地回归](evidence/dot-worklog-api-20261002.json)。

### 当前端点与分页

- GET/POST /api/v1/tickets/{ticketId}/worklogs
- GET/PATCH /api/v1/tickets/{ticketId}/worklogs/{id}
- GET /api/v1/tickets/{ticketId}/worklogs/{id}/history

创建字段为expected_ticket_version/kind/started_at/ended_at/note，修改将版本字段改为expected_version。note必须显式null或原始非空白文本；不得提供owner/tenant/ticket/default/cost。日期含秒及Z/offset，最多毫秒精度。page_size选项10/15/25/50/100，默认15；列表以created_at/id倒序，下一页成对传next_before_created_at/next_before_id为before_created_at/before_id，历史传next_before_version为before_version。has_more=false时边界为null，不自动回第一页。边界只裁剪已授权的本人范围；不是权限令牌，新写入需刷新第一页查看。
