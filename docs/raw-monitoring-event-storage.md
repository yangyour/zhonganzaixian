# 原始监控事件不可变存储

2026-10-07，承接[纯信封](raw-monitoring-event-envelope.md)，补充 V29 与内部 PostgreSQL 仓储。仅为 M4-D05 的存储基础；没有公开接收接口、来源签名认证、监控池/对象映射解析、inbox 领取、告警归并、工单或通知。

## 存储与重复投递

唯一键为 pool_id + source_event_id + source_phase，不把租户加入来源唯一键。原始 bytea 保留二进制、空白及最大 1MiB 边界；源事件 ID 的数据库限制与 Java 的 256 UTF-16 单元一致。发生时间和接收时间各保存 epoch-second 与 nano，包含负 epoch、完整 Java Instant 边界及来源时钟超前，不降为毫秒时间戳。

数据库生成原字节 SHA-256 与完整内容指纹，指纹与领域对象使用同一版本化、长度前缀算法。内容指纹不包含接收时间。仓储使用 READ COMMITTED，先 INSERT ON CONFLICT DO NOTHING，再用独立语句读取已提交赢家；唯一约束处理并发，事务提交后才返回结果。相同内容返回原 ID 和首次接收时间；同键异内容/发生时间/绑定返回 CONFLICT_REQUIRES_REVIEW，不覆盖原事实，也不返回冲突对象的租户、资产、记录 ID 或时间。

冲突枚举不是持久隔离队列。本切片没有保存冲突输入供人工处置，也不把 SAME_CONTENT 当公开 Webhook 成功回执。调用入口将来仍须先认证来源、验证当前映射和授权；组合资产外键只证明资产所属租户。表内 source_authorization_status 固定 UNVERIFIED、storage_status 固定 STORED_ONLY，不据此推断已认证、已处理或监控正常。

运行角色只有 SELECT/INSERT；无 UPDATE/DELETE/TRUNCATE 或 PUBLIC 授权。没有执行未获批准的保留期清理。连接、事务、提交与关闭失败统一返回脱敏的未确认错误；包括 suppressed exception 的驱动详情也不外泄。提交结果未知可重读/重复提交相同事件进行安全核对，不把异常当作确定未落库。

## 迁移与验证

V1–V28 字节不变，V29 在 2026-10-07 的 niko 独立 PostgreSQL 17.11 测试库显式应用。第一次 SQL 提交成功后，结构检查因生成列表达式中的 PostgreSQL 类型转换表示差异失败；记录为“迁移已应用、验证待修复”，没有改动已应用 SQL。修复 verifier 后只读及重复 apply 均执行 0 次迁移并通过。

结构 verifier 检查完整列集合/类型/null、组合外键与全局来源唯一键、运行 ACL、普通持久表 relpersistence=p、无额外触发器/RLS、完整 CHECK 与生成列表达式的 PostgreSQL 17 catalog 摘要，以及指纹函数源体/owner/search_path/执行权限。负向事务 DDL 测试覆盖 CHECK(true) 偷换、生成式追加内容、改为 UNLOGGED，全部回滚恢复。

16 项真实 PG 测试通过：字节/纳秒/SQL-Java 指纹；新仓储重读与首次接收保留；内容、时间及跨租户绑定冲突；8 路相同内容并发及异内容竞争；来源键分离；极端时间；组合外键；直接 SQL 非法输入与运行角色写限制；UTF-16/1MiB 边界；提交前失败回滚及提交后结果未知的重试；三种结构漂移拒绝。4 项隔离安全测试覆盖失联、回执完整性和关闭异常脱敏，迁移策略测试含 V29 独立显式模式。

66 张既有业务表的完整行指纹在迁移/测试后不变。15 条已提交合成原始事件在真实 PostgreSQL 正常重启前后数量和完整行摘要相同；重启后独立只读 CLI 0 迁移。这是 graceful restart，不是 WAL/主机掉电/事务中途数据库崩溃验收。

实际回归与限制见[证据](evidence/niko-raw-monitoring-event-storage-20261007.json)。原指定服务器没有操作，API/Worker 启动仍不自动迁移。Q06/Q29 不补默认值；真实监控接入、隔离队列、完整 M4-8/M4-14 与 128 项正式验收继续保留未完成状态。

## 后续安全加固（2026-10-07）

配合独立隔离存储切片，原始仓储也显式设置事务 synchronous_commit=on，SELECT ONLY 限定基表；schema 校验扩展到全部默认值、继承/重写、表/列/函数权限类型与转授权及 fsync/full_page_writes。新增真实 PG 探针从 off 会话开始验证提交时 on、提交后恢复 off，7类目录/授权漂移及 PG17 MAINTAIN、列REFERENCES拒绝。当前原始仓储26项真实PG通过；未修改V29 SQL。最终完整回归另见[隔离存储证据](evidence/niko-monitoring-quarantine-storage-20261007.json)。
