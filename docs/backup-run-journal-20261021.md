# 备份运行与尝试追加日志（2026-10-21）

状态：完整迁移、31项仓储断言和7项结构检查已在隔离 PostgreSQL 17.11 验证通过。该增量不是生产备份执行能力。

## 已实现的存储链路

V47 新增 mop_backup_run_event。每个 run 有 QUEUE/START/MARK_UNKNOWN/SUCCEED/FAIL 事件、原命令身份与摘要、版本、数据库时间、尝试编号及规范化快照。tenant/run/version 主键和 tenant/run/command 唯一键，原命令相同仅返回原回执，不重新执行，不以当前状态冒充原回执。当前授权在读取及重放之前检查。

同租户设备资产行锁把事务串行化；非排队命令使用 expectedVersion CAS。同一 START 绑定当时最新且未过期的设备租约 owner/request/fence/eventVersion。每个租约 request 只可启动一次，重试必须用独立租约请求，不能借旧申请的重放获得新的执行资格。MARK_UNKNOWN 和完成事件保持本次尝试原始租约绑定；完成记录本身不释放设备、不授予执行权。

数据库精确到毫秒的 transaction_timestamp 是整个命令的观察时间。等待锁期间如果产生较新的事实，旧事务可能被保守拒绝，需新事务重新核查；不修正或伪造数据库时间。纯领域规则保持初始加两次重试、已明确失败后十分钟间隔、未知结果禁止重新开始、终局不可重开。没有自动把网络失败变成备份失败。

快照同时核对 SHA256、run/tenant/asset/version/queued_at/attempt_count 头部；Java 解码器检查完整状态结构、规范字节以及与事件状态/动作/观察时间的一致性。数据库约束不是数字签名，也不独立验证完整对象内容或执行事实。数据库所有者可管理结构，运行角色只授予 SELECT/INSERT，不宣称防数据库管理员篡改。

## 必须配置而未替代的依赖

仓储构造器必须提供 Authorizer、StartVerifier、CompletionVerifier，没有默认允许实现、Spring 自动装配、HTTP 端点或后台 Worker。

- Authorizer 在同一事务锁定当前身份/范围/撤权事实直到提交。
- StartVerifier 从可信记录验证当前批准计划、设备授权、报备与执行前置条件，必要撤销/策略锁保持到提交。不能在此回调执行 SSH 或远端命令。
- CompletionVerifier 核验已经记录且绑定同 run/attempt 的结果；成功还须真正验证加密对象、版本元数据、租户、设备、内容与持久化时间。一个 sha256 引用不是证明。
- 释放设备继续使用 V46 的独立停止证据核查。过期仍不得自动接管。
- Q22“连续失败次”的口径未确认，automaticTicketAllowed=false；没有新增P3、调度、对象归档、KMS、真实设备指纹或录屏能力。

技术时限 lock_timeout=5s、statement_timeout=10s、JDBC queryTimeout=12s 只保护数据库资源，不能作为客户业务SLA。连接/提交异常返回可核查的通用错误，不暴露 SQL/凭据，不假定写入未成功。

## 迁移与验证证据

- 首次候选目录检查使用 BEGIN/ROLLBACK，V46/V47候选DDL未提交；不称实际应用。
- 完整 Flyway V1–V47 已应用；V47只新增一张表，既有业务表指纹不变，仅迁移历史新增。重复显式应用与 --check 不改变107张表指纹。
- V1–V46全部保持原字节。V47 从首次实际应用后冻结，SHA256为 5ca80f0715369d45b3a7606fb411e962576a15d9f49b8921a70a2c1d0af12f0c。后续DDL只能V48+。SQL开头历史“Candidate”注释保留，不因状态更新重写已应用字节。
- 7项目录/回滚篡改检查包括会话时区/日期格式恢复、缺失约束、关闭触发器、额外UPDATE权限、缺失唯一索引和SECURITY DEFINER，全部通过，数据指纹不变。
- JDBC检查覆盖原请求重放、重复run拒绝、授权先于重放、跨租户/设备、当前租约、重复租约、未知结果、核查失败回滚、真实并发CAS、错误成功引用、终局、运行角色不可变日志。十分钟边界已使用真实数据库时钟等待通过，第二次尝试绑定新租约；旧完成请求重放不覆盖新尝试。不回填历史、不改系统时间。该实际检查验证第一与第二次尝试，第三次终局由既有纯状态测试覆盖，未冒充再次等待十分钟的数据库验证。临时数据库、进程和凭据已清理。
- 最终 Maven verify 3960项，其中2944执行通过、1016按环境跳过；新增11项单元。两个新PostgreSQL测试在普通Maven中按显式环境门禁跳过，对应独立JDBC驱动结果另列，不能重复计算为默认测试通过。
- Python123项、Node契约173项通过；API0.76未增加路由。Python早期清单不完整失败及PS映射重复键开发失误已修正并新增防重复回归；没有隐藏失败。PowerShell真实运行/Windows服务未执行，仅源码策略和清单检查。

## 复验与边界

显式迁移模式 --apply-backup-run-journal，仅允许完整V46的专用mop_test；服务启动不自动迁移。Python与PowerShell清单都固定V1–V47，拒绝资源集或字节不一致；Windows入口要求新的备份校验类不早于源码。

PostgreSQL仓储/目录测试的显式门禁为 MOP_BACKUP_RUN_TEST_ENVIRONMENT=dot-backup-run-disposable；必须使用自己创建的临时数据库、合成数据和合成适配器。MOP_RUN_REAL_RETRY_WAIT=true 才执行实际十分钟等待；不设置时打印 NOT_RUN，不能冒充该边界通过。此次驱动代码按同一临时服务生命周期运行并清理，环境专用路径不是生产启动配置。

本增量没有前端路由，不新增浏览器通过项。设备、真实授权/对象/停止协议、调度Worker、保留清理、恢复演练和正式验收仍待；53项浏览器规格与128项正式TC仍NOT_RUN。
