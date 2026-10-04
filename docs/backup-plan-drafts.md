# M6-1 每日备份计划草稿核心

## 来源与范围

M6-1/T00R078、02 M6-D01允许先开发计划模型。合同默认每日02:00，失败重试2次、间隔10分钟；config-baseline把三者标为configurable_default，不是不可调整的固定约束。business.timezone=Asia/Shanghai是平台固定约束。

BackupPlanDraft目前只表达每日计划提案：租户UUID、名称、显式目标资产UUID列表、严格ASCII HH:mm、可空credentialRef UUID、requestedEnabled和可空备注。没有持久化/API/页面/调度或SSH调用。此阶段不保存密码、主机连接串、私钥或厂商命令，也不创建、核验、配置凭据。

名称1—100 Unicode码点且非全空白/控制字符；备注可null/空/空白，保留原文与换行，上限4000码点，拒绝孤立代理字符及其他控制字符。目标必须明确非空、不重复、不含null、有序且防御复制；1000项是技术保护而非业务配额，超过拒绝、不截断。不把格式正确的目标UUID当作资产存在/当前租户授权证明，未来仓储必须在保护事务内核验全部对象，跨租户404。

时间不暗中补默认：CONTRACT_DEFAULT_TIME仅给将来表单提供显式建议，null/缺值拒绝。每日频率是本切片范围，不声称支持合同所有可配置频率。proposedRetryCount=2、proposedRetryIntervalMinutes=10仅合同默认提案，状态CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE；以后已确认的覆盖值不能被这两个方法静默覆盖。

## 永不激活的草稿

requestedEnabled保留使用者的true/false意向，但enabled=false、effective=false、state=DRAFT、deviceExecutionAllowed=false、nextExecutionAt=null。提供credentialRef也只得到NOT_VERIFIED_BY_THIS_MODEL，没有引用时NOT_SUPPLIED。目标验证同样未完成。没有“创建草稿即排队”的副作用；不能用这个纯对象作为设备执行授权。

实际计划发布/启用仍须明确设备访问授权、合法凭据、固定SSH主机信任、厂商只读命令、真实存储与密钥门禁，以及事务幂等、版本/CAS、审计、多实例租约和设备执行锁。创建正式计划的202+operation契约没有被本切片实现，不能返回假operation；新设备自动纳入/退出默认模板也未实现。

## 显式日期预览

previewDate要求调用者给出具体日期，不调用系统时钟、不推算下一次执行、不定义错过时点补跑策略。正常日期只返回当地日期时间对应UTC候选，标UNPUBLISHED_CALENDAR_CANDIDATE，createsRun/executionAuthorized始终false，missedRunPolicy=null。

使用JDK ZoneRules检查当地时间。历史上海夏令时缺口返回LOCAL_TIME_GAP，重叠返回LOCAL_TIME_OVERLAP，两者candidateInstant=null，不自行选择早/晚偏移或平移时间。合法日期和UTC输出均限定0001—9999年，UTC下溢拒绝。业务固定时区不依赖JVM默认时区。候选不是调度承诺，不证明设备在那个时点执行过或会执行。

## 测试与未完成边界

18纯测试覆盖意向与生效隔离、空值/精确时间、默认提案、目标边界/复制、凭据引用不授权、Unicode/错误不回显、午夜/年界/闰日、上海历史夏令时缺口与重叠、UTC下溢、重复只读预览，以及一天1440个分钟向量回转。实际构建/回归结果见[证据](evidence/niko-backup-plan-draft-core-20261004.json)。

本切片0新迁移、0计划业务写入、0HTTP、0SSH、0存储/凭据配置。V1—V23冻结、API0.38不变且固定在线包未重启。后续先补受保护的未生效草稿仓储和页面；设备/存储/生产批准阻塞不妨碍这些代码，但不以单元对象或引用伪造真实备份。M6-1与128项正式验收仍NOT_RUN。

## V24显式结构（2026-10-04）

在niko既有真实PG17.11上独立执行V24，未修改原指定服务器。三表为mop_backup_plan_draft头、不可变revision历史和有序target设备引用。目标使用tenant+asset复合FK；保存目标不授予SSH。凭据字段仅可空UUID引用，没有实际秘密、凭据验证/配置。每版保留明确HH:mm、requested_enabled、备注和归档动作；固定enabled/effective=false、DRAFT/UNVERIFIED和Asia/Shanghai。

运行账号只能SELECT/INSERT，头部仅允许UPDATE(version,updated_at)，不得改写/删除历史、改绑定或TRUNCATE。头部版本从1开始，每次只加1；当前版本FK和完整目标验证延迟至事务提交。revision带创建xid8，target必须在同一事务插入且position不能超过声明数量，防止历史追加或提前SET CONSTRAINTS后补额外项。完整性校验同时检查连续版本、操作者归属、时间单调、目标数/序号。ARCHIVE/RESTORE必须原样保留名称/时间/凭据引用/启用意向/备注/目标顺序；归档后不能绕过RESTORE直接UPDATE。恢复草稿仍不启用设备备份。

四个函数均为invoker、固定search_path=pg_catalog、无PUBLIC执行权；独立核验器检查列/可空、复合FK、CHECK数量、权限、拥有者、触发器模式和函数体SHA。V24已执行后字节冻结，SHA256=3b28fe6679d787faafdf0d5ea175712ec622b5017c103650cfa42ff12c73816a；后续结构变更只能新增迁移。

显式命令新增--apply-backup-plan-drafts，默认--check只读；重复本模式、旧V23模式和只读检查均0迁移。执行前后50张既有业务表完整行指纹等值，未排除列。API/Worker未自动迁移、未重启固定在线API；新备份业务仓储/HTTP尚未接入，不把结构校验当作保存端到端完成。

19真实运行账号PG结构用例与31迁移策略用例聚焦通过。跨租户引用、缺失目标、重复/超界目标、跳版、未来版本、历史不可写、启用伪造、归档变更、后续事务追加和早校验追加均拒绝；Unicode/null/空串及原历史保留通过。真实PG没有fixture替代。测试中显式提交的合成草稿保留，其余隔离事务回滚；每次运行保留一条，均未启用。详细完整回归与保留计数见[结构证据](evidence/niko-backup-plan-schema-20261004.json)。

本机无pwsh；Windows入口的源检查通过不等于原生PowerShell运行，等待该提交CI原生Ubuntu pwsh策略检查，仍非Windows数据库联调。

## 受保护草稿仓储（2026-10-04）

BackupPlanDraftRepository增加创建、明确expectedVersion修改、归档、恢复、当前详情、稳定created_at/id分页和不可变version倒序历史。Postgres实现只面向当前租户有效padmin，重新核验成员/用户active及permissionVersion；保护写入锁住成员，缓存收据重放前仍先核验权限。草稿不是正式计划，也不扩大岗位矩阵或设备权限。

创建/修改/归档/恢复必须使用平台现有idempotentAuthorized事务；未保护调用拒绝，事务内另开独立读也拒绝。草稿tenant和每个目标均须属于当前租户且真实存在；按设备ID排序锁定目标，任何缺失或跨租户对象404，不先插入头部。可空credentialRef仅保留明确UUID提案、始终未核验，不连接秘密系统、不使用凭据。修改/生命周期动作锁住头并核对expectedVersion，原记录保留；归档不能修改内容，恢复原样保留并保持未启用。

头/历史/目标/成功审计/幂等收据同一保护事务原子提交。原键原body只回原收据，归档后的原创建重放仍返回原创建结果，不重新创建或伪装最新状态。新键同旧expectedVersion只能一个成功；已知错误不能提交部分记录。读操作在单个repeatable-read只读快照内授权，校验字段/固定未生效标记/目标数量顺序和领域内容，不追加审计或收据。读到伪造enabled=true或不一致数据失败关闭503；这不是第三方签名或备份对象真伪验证。

21新增真实PG仓储测试覆盖创建/更新/历史/归档恢复、当前权限/过期版本、跨租户和缺目标、CAS冲突、原重放/变body、调用者回滚、独立只读、两连接同键/不同键竞争。审计故障实际执行SELECT 1/0、目标故障在同一真实连接改为跨租户FK，两者五表全部回滚；额外实际查询后受控篡改enabled读取返回503而数据库不变。首轮测试的审计计数误用扁平resource_id列，SQL报不存在；改读真实payload JSON后全部通过，未改业务代码或删除失败轮合成数据。自有合成草稿在测试结束后归档保留，未启用、无SSH/存储/凭据配置。

聚焦58项通过（21仓储+19结构+18纯核心），完整回归及只读合成数据核对见[仓储证据](evidence/niko-backup-plan-repository-20261004.json)。V24原字节保持，0新迁移，HTTP/页面尚未接入；API0.38及在线固定包未改。前结构提交ead1113的CI37220663039成功，Ubuntu原生pwsh真实执行103策略检查/0数据库操作；这不代表Windows数据库联调。

## API0.39：独立草稿入口

新增GET/POST /api/v1/backup-plan-drafts、GET/PATCH /{id}、GET /{id}/history、POST /{id}/archive与/restore。两项backup_plan_drafts:read/write仅当前padmin，能力目录明确归类并保留未知能力拒绝。正式/backups/plans、/backups/runs的202+operation流程仍未实现；这些同步200只表示草稿事务完成，不代表已启用、同步设备或实际备份。

创建需expected_version=0及所有明确字段：name、target_asset_ids、daily_local_time、credential_ref（可null）、requested_enabled、note（可null）。修改需当前正版本；归档/恢复只接收expected_version，恢复不允许顺便改提案。拒绝未知/重复JSON字段、尾随JSON、租户/生效/执行/秘密字段覆盖和全部写query。原键参与当前成员复核，body按对象键排序哈希，保留目标顺序、文字、null/空和版本差异；CSRF由真实安全链路校验。

原始请求262144字节/4000 token/深度4技术边界，超字节/token返回413，不截断。列表与历史仅10/15/20页长、成对created_at/id或明确before_version；摘要不含目标数组/备注，详情和历史返回当版完整提案。目标唯一按规范化UUID判断；模板默认时间/重试值不冒充已经生效的配置。凭据UUID即使保存，也固定NOT_VERIFIED_BY_THIS_MODEL；无引用NOT_SUPPLIED。所有返回enabled/effective/device_execution_allowed=false、next_execution_at=null。

/health/ready实际检查V24结构/权限，缺失、null或异常503；liveness独立。schema_access_ready不等于凭据、存储或调度可用，scheduler_running=null。新增5路径9schema，89旧路径及198旧非ReadinessResult schema逐对象等值；API版本0.38→0.39。没有旧路径默默改义或额外启动调度。

18控制器+1ready新增；聚焦50通过，完整1975报告1962执行含642真实PG、13历史跳过/6MinIO排除；103契约/1263Web（76文件）/类型构建通过。首轮聚焦暴露两项能力目录旧列表期待和一次测试重复读取同一流；更新精确期待并使用新请求后通过，未放宽未知能力策略或服务器输入校验。

真实PG17.11/Keycloak26.2.4/API0.39完成80HTTP：40×200、18×400、1×401、9×403、5×404、5×409、2×413。核验创建/原回执、15版本历史分页、归档原样恢复、并发同键只建一次/不同键CAS只一个成功、角色/CSRF、跨租户对象和目标404及限额。保留2个自有合成草稿并全部归档，18历史/36目标/18成功计划审计（含2创建），待处理原请求0；没有永久删除、SSH、存储或凭据配置。控制文件已完成，不重跑原场景。

正常部署重启前后53张业务表全部原行指纹等值，在新OIDC登录前完成；这不是故障中断/灾备验证。在线固定JAR SHA256=c144ecc0c62311f3f0a3cef4333381dacc540e97bb077369f1ce5bd62af41ec9，0新迁移。新页面/原请求恢复客户端尚待；真实浏览器B03、设备SSH/Q22、OSS/KMS/Q28及正式发布仍未解除。见[API证据](evidence/niko-backup-plan-api-20261004.json)和[HTTP证据](evidence/niko-backup-plan-http-20261004.json)。
