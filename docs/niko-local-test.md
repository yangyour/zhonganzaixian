# niko 独立真实测试环境

## 授权与边界

2026-10-02用户明确允许在niko部署必要组件，并单独批准本机回环的PostgreSQL/Keycloak/MinIO专用测试账号和随机凭据；其后指定阿里云OSS作为存储方向。本文件记录新环境，不代表原指定服务器网络恢复，不沿用旧服务器的通过记录。`remote_test`仍是应用的真实组件适配器profile名称，环境证据必须另标`niko-local-test`；没有启用`local_fixture`。

2026-10-03新实例恢复只带tracked HEAD源码，不恢复Git历史中的私有配置或旧临时数据库；原服务器服务/网络/端口不变。新凭据、会话、数据库和日志只在Git忽略的`.local/niko-test/`，私有目录0700、凭据文件0600。任何新环境凭据均不得提交Git；代码不包含实际访问密钥。

## 实际组件

- PostgreSQL 17.11，Debian 13官方签名包仓库下载并解包到工作区，非系统服务安装；原服务器17.9结果不能合并。仅127.0.0.1:25432，SCRAM认证，migrator/runtime分离，运行身份无超级用户/建库/建角色/复制/bypassRLS权限
- Keycloak 26.2.4，Maven Central官方quarkus发行包，发行SHA1校验并另记SHA256；独立PG库mop_keycloak，只127.0.0.1:28110，管理接口也显式绑定回环。应用client关闭password grant和service account，启用S256 PKCE，6个合成测试身份
- API只127.0.0.1:8080；只注入runtime凭据，不注入migrator凭据；API/Worker启动不执行迁移
- MinIO（旧实例历史，新实例未建立）按官方RELEASE.2025-09-07T16-13-09Z源码、已校验官方Go1.24.6编译成功，不是官方预编译包。启动前网络接口枚举因netlink权限EPERM失败，获准提升执行后同样失败，尚未运行对象存储或IAM测试。没有修改系统限制、补丁绕过或用内存存储宣称成功。后续存储按用户选定OSS另行实现/验证

## 同一会话运行

该云执行环境中不同命令会话的回环不可互通，Unix-domain socket也不可用。PostgreSQL使用受密码保护的TCP回环；组件与QA在同一受控会话启动。`tools/niko-test/run-stack.sh`只启动已准备好的PG、Keycloak和API，进入同一命名空间的QA shell；退出时按本次直接子进程归属检查，先停API/Keycloak，再正常停止PG，不按端口或进程名停止其他服务。

前置：仓库根目录、JDK17/Maven已构建工程；相邻`mop-toolchain/components/pg`为解包PG17目录，`keycloak-26.2.4`为官方发行目录；工具链和第三方二进制不进Git。首次准备不是`run-stack`的隐含动作：

1. 经账号/凭据创建批准后，当前PG/Keycloak范围显式运行`python tools/niko-test/prepare-private.py --create-approved-database-identity-credentials`；不会创建存储密钥。旧完整flag仅用于另行明确获准的全组件准备，已有配置拒绝再生成、扩展或轮换
2. 用该私有目录的pg-init-password显式initdb，`-U mop_niko_admin --auth=scram-sha-256 --encoding=UTF8 --locale=C`；启动受限回环PG后运行`bootstrap-postgres.py`
3. 启动Keycloak后运行`bootstrap-keycloak.py`，只操作带niko项目归属标记的realm；绑定过的用户消失或ID改变时拒绝同名继承
4. 从已构建API jar提取BOOT-INF/lib到`.local/niko-test/java-libs`供独立CLI使用；显式`run-migration.py --initialize-new-niko-database`只接受空的新niko库，应用冻结V1–V4；再分别`--apply-photos`、`--apply-asset-imports`、`--apply-asset-dependencies`、`--apply-worklogs`、`--apply-asset-metadata`、`--apply-internal-notes`、`--apply-ticket-comments`、`--apply-working-calendars`、`--apply-manual-incidents`、`--apply-manual-recoveries`、`--apply-manual-warranty`、`--apply-capacity-scenarios`、`--apply-customer-contacts`，最后`--check`。manifest核对全部V1–V17源码和编译资源；不是修改旧迁移或重跑原服务器
5. `bind-local-identities.py`仅初次空业务新库，把冻结SQL的合成用户明确绑定新realm签发者+sub；记录本地绑定，后续不按用户名重绑定；不改变角色或原SQL
6. `bash tools/niko-test/run-stack.sh`供后续已有环境启动和正常停止；MinIO/OSS不被隐式启动或伪装就绪

这些入口只支持此次niko测试路径/端口，不是生产部署或任意环境安装器。下载来自[PostgreSQL Debian仓库](https://www.postgresql.org/download/linux/debian/)、[Keycloak官方版本](https://github.com/keycloak/keycloak/releases/tag/26.2.4)和[MinIO官方源版本](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z)。

## 本轮已验证

- 独立迁移执行4+1+1，后续检查及正常重启后检查均0迁移；V1–V6摘要不变
- persistence模块120项通过，其中69真实PG：基础5、修订6、报障8、照片事务24、配置诊断8、导入事务18；其余51隔离检查。照片/导入PG测试的注入存储替身只验证数据库事务，不证明对象存储链路
- 根Maven显式排除6项MinIO检查后：380总数、367执行通过、13历史fixture跳过，0失败/错误。367包含上述69，不重复累加；排除项是NOT_RUN，不是PASS
- 6个真实OIDC身份通过HTTP授权码/S256/nonce/state和HttpOnly应用会话检查。测试客户端只对这两个已授权loopback origin处理Keycloak的Secure cookie本地可信上下文，保留Secure标志；不据此宣称浏览器SameSite/渲染或真实手机通过
- 配置修订44个实际HTTP响应通过Ajv：内部审批五态、自审/伪造主体/CSRF拒绝、幂等、CAS、跨租户404、审批不生效，以及对象/嵌套数组1e999被400拒绝且不新增修订
- 本次拥有的PG/Keycloak/API正常停止再启动，8张项目表按排序行计算的SHA256与行数前后一致；恢复后重新OIDC/HTTP验证。不是崩溃、断网、MinIO故障或Worker耐久重试验收

HTTP脚本支持明确`MOP_TEST_SESSION_DIRECTORY`、`MOP_TEST_ENVIRONMENT_NAME`及`MOP_EVIDENCE_OUTPUT`。使用独立会话目录时必须指定独立证据路径，禁止覆盖历史原环境报告。`capture-http-sessions.py`只写本地私有会话，不输出密码、cookie、授权码或token。

证据：[环境汇总](evidence/niko-local-components-20261002.json)、[HTTP](evidence/niko-configuration-revision-http-20261002.json)、[正常重启](evidence/niko-graceful-restart-20261002.json)。最初测试运行因未给测试进程提供其明确要求的migrator变量而4个类初始化失败，补齐仅测试进程参数后69项全部通过；API进程仍不含迁移账号。此前独立命令无法互连和MinIO启动失败均保留，不包装为全部成功。

## 未完成

OSS连接/IAM/不可覆盖/大文件/照片及导入整链、浏览器UI、真实设备/企微、客户业务值、工作流其余功能和128项完整合同仍未完成。`OBJECT_STORAGE_PRESIGN_EXPIRES`不能直接改变平台授权grant或绕过租户检查；存储方向变更不代表权限或兼容性已通过。

## 独立客户报障验证

`capture-http-sessions.py`另输出4个`qa-ticket-<role>.json`私有别名。`customer-report-smoke.mjs`支持MOP_TEST_SESSION_DIRECTORY、MOP_TEST_ENVIRONMENT_NAME、MOP_TEST_POSTGRES_VERSION、MOP_TEST_KEYCLOAK_VERSION及MOP_EVIDENCE_OUTPUT；自定义环境必须显式全部信息且不能指向旧证据路径。当前脚本要求冻结V5照片表存在，未知附件404，不再沿用V4/Q28预期；照片上传未测试。分页只使用该次真实API创建的虚构报障，测试记录保留不自动删除。

正常重启验证：在同一组件命名空间运行`node tests/niko-report-restart-http.mjs before`；退出run-stack子shell使其仅停止所属组件；重新启动run-stack并重新capture会话，再运行同脚本`after`。脚本不自行重启或迁移、不覆盖既有checkpoint/重启证据，只比较该次已完成HTTP脚本留下的报障与operation；如需新一轮，先使用新的独立证据方案，不删除历史记录。该流程不能标为Worker故障或崩溃恢复通过。

## V7依赖草稿迁移（独立执行）

2026-10-02在niko新库单独执行`python tools/niko-test/run-migration.py --apply-asset-dependencies`：首次1、重复0、check0。启动API/Worker不会调用它。`frozen-migrations.json`继续保存原6版不变，`frozen-dependency-migration.json`单独保存已执行V7字节hash；后续结构修改另起迁移，不重写V7。旧指定服务器未执行本批迁移。当前只有草稿schema和实际约束测试，防环仓储/API/页面仍在开发。

## V8实填工时迁移（独立执行）

2026-10-02在niko新库执行`--apply-worklogs`首次1、重复0、默认check0；只在已验证V1–V7后允许。V8校验和另外保存在frozen-worklog-migration.json；源文件与编译资源一致后才允许CLI读取私有连接配置。原服务器没有执行V8；API/Worker不自动迁移，后续结构变更新增版本，V1–V8不可重写。当前是schema切片，未宣称工时API或页面完成。

## 2026-10-02 拥有的API突然中断与恢复

新增显式run-stack --verify-owned-api-crash模式，只针对本次启动且父PID/UID对应、JVM完整命令/项目jar及回环8080 LISTEN socket归属都核验的API进程。业务写先收到提交回执，再SIGKILL并等待exit137、确认连接拒绝；PG/Keycloak在该故障步骤不重启。随后重启同一API，重新验证监听归属和健康，再恢复OIDC会话；不扫描并批量停止其他项目进程。

最终故障测试通过：先7次HTTP提交合成报障及本人v1/v2工时；同一只读REPEATABLE READ快照对11张既有业务表计算排序行SHA-256/行数，API突然中断重启后、再次登录前逐表完全一致。恢复后8次HTTP读取父记录/工时/历史，原创建键仍重放原v1、修改键重放原v2，当前记录保持v2、历史[2,1]且对应审计仍2条。属于“已确认提交后API中断恢复”，不是在途事务杀进程、PG/WAL崩溃恢复或Worker/OSS故障。

首轮未通过：启动脚本运行期间发生修改，旧脚本退出出现MODE未绑定，先前已启动的本项目API残留；新子进程被kill后旧监听仍可达，所以未产生恢复成功证据。核验旧进程固定PID/start-ticks/UID/完整JVM命令和项目路径后仅正常停止该实例；归档首轮私有checkpoint不覆盖，补充runner函数一次解析与监听归属校验后另轮完整通过。首轮失败单独保留。[首轮证据](evidence/niko-api-crash-first-attempt-20261002.json)、[最终证据](evidence/niko-api-crash-recovery-20261002.json)。

新增9项无真实进程的守卫单测，合计29项niko私有配置/OSS配置/故障守卫通过；shell/Python/Node语法检查通过。结束后默认交互启动模式再次核验当前API监听并重新捕获既有OIDC会话。没有新账号、凭据、权限、迁移、端口/防火墙或原服务器操作。API V0.12和V1–V8不改。

上一审计UIebee0ab的CI37039467414初次backend在maven-resources-plugin的slf4j-api描述符解析阶段失败，未进入业务测试；Web与pure Java通过。仅重跑失败job，attempt2整体success，不改锁定依赖/校验或跳过测试以求通过。此处不推断具体网络错误码。

### 故障验证入口与复现限制

只对已经获准的niko组件执行bash tools/niko-test/run-stack.sh --verify-owned-api-crash。该模式启动已有组件，不初始化数据库/用户或执行迁移；要求本次证据路径全新。若检查失败，保留日志和私有checkpoint，不删除或覆盖它们来假装第一次通过。后续新一轮应明确采用独立证据方案；默认无参数仍进入交互QA shell。不要在运行过程中改写实际启动脚本，修改后下次重新启动使用新代码。

指纹查询只输出SHA-256和行数，原始行不进入工具日志/公共证据。它用于本次提交数据相等性，不是外部真实性/存储防篡改证明。真实重新OIDC登录会追加审计，因此指纹比较必须先于恢复登录。测试不证明浏览器会话体验，未触碰OSS或原服务器。

## 2026-10-02 V9手工资产资料不可变修订结构

V9新增mop_asset_manual_revision/details，当前指针引用不可变修订；actor/owner/site/asset均有同租户复合FK，修订可链接更早资料版本。before_snapshot中的资产标识/旧版本/IP/手工草稿状态受约束，首次未知metadata须为null；后续仓储仍必须锁定真实资产并构建真实前后快照，schema约束不能替代实际成员授权或来源核验。

可选资料/日期无默认值，SELF_REPORTED/monitoring_applied=false/cmdb_applied=false固定；运行身份只获资产name/type/site_id/version/updated_at/updated_by六列UPDATE，不能改IP、source、tenant或监控状态；历史资料没有UPDATE/DELETE权限。新schema只在niko显式check0→apply1→repeat0→check0，旧worklogs显式apply再次为0。V1–V8字节未改，V9应用后另存冻结hash；API/Worker不迁移，后续结构需新版本。

10项迁移策略、8项新增真实PG结构/ACL回归、30项niko守卫通过；schema测试事务全部回滚，不冒充资料服务持久化。根524=511执行（含119真实PG）+13fixture跳过，6MinIO排除，0失败错误。仓储/API/页面未接线，M3-5非DONE；API仍V0.12。见[结构证据](evidence/niko-asset-metadata-schema-20261002.json)。上一c6f2f25的CI37043222178已回读success。


## 2026-10-02 V10资料/内部备注独立API中断场景

新增独立--verify-owned-v10-api-crash模式，旧工时故障脚本、首轮失败及最终证据均保留。先退出原交互run-stack，让它清理自身组件，再修改并重新启动新场景；未改写运行中的启动脚本。本次仍只SIGKILL本次拥有、父PID/UID/JVM完整命令/回环监听归属都通过检查的API子进程，等待exit137及确认连接拒绝。PG/Keycloak在API故障窗口不重启，同一构建jar SHA256在前后相同；无新账户/秘密/权限/迁移/端口/防火墙或原服务器操作。

首轮完整通过：故障前14次HTTP提交合成报障、2位内部作者的2条备注、手工资产两版资料并读取历史/审计；14张明确白名单业务表（原11+V9两表+V10备注）在同一只读快照计算排序行指纹，重启后、重新登录前全部一致。故障后12次HTTP核验原客户登记、2条备注、当前资料v3和历史[3,2]；原资料键仍返回原v2，原备注键返回原记录，现值不回退、各自审计仍2条。重新OIDC登录成功；结束后默认交互模式与当前拥有API监听、既有账号会话恢复。

新增4隔离守卫，总35niko守卫通过，检查14表白名单、与旧证据路径分离、覆盖碰撞拒绝以及未知模式不读私有配置；shell/Node语法通过。该场景只证明“已提交后API突然终止”的恢复，不证明在途事务杀进程、PG/WAL、Worker/OSS故障、浏览器或原服务器验收。指纹是相等性，不是外部真实性。不得删除既有checkpoint后假装首次复现；同场景会拒绝覆盖。[V10故障证据](evidence/niko-api-v10-crash-recovery-20261002.json)。


## 2026-10-02 运行JAR与构建隔离

新增每次启动独立SHA256只读JAR副本，完整ZIP/本项目入口/复制稳定性/UID/权限/路径守卫，拒绝直接target回退；保持确切父进程/JVM命令/回环监听检查。48 Python守卫及语法通过；真实API运行期间强制Maven重打包，根616=603执行含163PG+13skip/6MinIO排除通过，target SHA变化而API PID/start-ticks/运行副本不变；不重启捕获6个新OIDC身份并20只读HTTP通过（含/assets404）。初次证据工具/proc children缺失失败已记录并修正为只读严格身份枚举；之前ThrowableProxy故障不声称唯一根因已复现。旧SIGKILL证据保留未重跑，V1–V10/API0.16/原私有配置不变。

见[运行产物隔离](niko-runtime-artifact.md)。


## 2026-10-02 V11客户可见评论schema（已执行冻结，业务仍待）

独立mop_ticket_comment/16列/14CHECK/复合租户FK与只追加ACL已显式check0→apply1→重复0→check0，旧内部备注apply0；V11 SHA256 d8535bbf13ca9e35c115278f36232a786b4562c99cc9afd9f5195a416028a8e3，V1–V11冻结。7真实PG schema测试全部回滚，新表只读复查0行；12迁移策略/49 Python守卫、根642=629执行含172PG+13skip/6MinIO排除通过。角色/报障关系仍须后续事务重查，schema不能冒充业务授权；没有评论API/页面/ITSM或状态变更，原服务器/私有配置不改。

见[客户评论V11结构证据](ticket-comments.md)。


## 2026-10-03 V13不可变手工事件登记结构与真实PG

在niko独立库显式执行V13一次，重复执行/只读check/旧V12模式均0。20列与18项CHECK、主键/同租户资产和作者组合外键、登记排序索引及运行角色仅SELECT/INSERT已验证；PUBLIC无授权，无列级UPDATE。原登记不可覆盖，后续人工恢复事实或工单关联需独立证据。MANUAL/REGISTERED_ONLY/SELF_REPORTED固定，监控/正式处置/优先级/实际发生和恢复时间保持null，自动建单false；数据库拒绝把监控正常当人工恢复。

8项新增真实PG schema测试全部事务回滚，涵盖原Unicode/200 code point、五等级、跨租户外键、版本安全范围、来源/未知投影、ACL及时间/快照边界。测试后只读确认登记表0行、Q08两项value仍null。完整根736=723执行（含220真实PG）+13历史fixture跳过，6MinIO排除，15迁移策略/51Python保护/35契约通过，无失败。CLI改为显式版本/目标矩阵，仍要求连续已验前置历史、独立单功能执行，不自动跨越迁移；纯矩阵测试覆盖所有模式与已验状态。

V1–V13已字节冻结，V13 SHA256为80734d0a0dcd436a3008ed76af0b7da3e81956f516a25d778c0717df79665700；后续结构修正V14+，API/Worker启动不迁移，API0.18不变。结构通过不等于当前角色授权、资产实际版本CAS、幂等/审计仓储或API/UI已验。下一步实现同租户当前内部成员的登记仓储、原请求重放前再授权及真实PG并发；人工恢复/正式事件状态/建单闭环与完整M5-6/D06/D07/128项仍未DONE。

独立命令：python tools/niko-test/run-migration.py --apply-manual-incidents；只读复核使用--check。运行角色不可迁移；V13已执行，不编辑原SQL。


## 2026-10-03 V14人工恢复独立证据结构与真实PG

显式独立执行V14一次；此前只读check0，重复执行/之后check/旧V13模式均0。mop_manual_recovery_confirmation共17列、16项CHECK，租户+原登记唯一保证一份不可变确认，外键只指向V13手工登记和同租户作者。原说明及UTF8摘要、可null的reported_recovered_at、单独recorded_at保留；已填恢复时间不得晚于确认时间。HUMAN_CONFIRMED仍SELF_REPORTED，监控/处置null、自动监控恢复/工单状态/SLA变更false。运行角色只SELECT/INSERT，无PUBLIC授权/列级UPDATE，也没有为原V13父表扩张UPDATE授权。

8新增真实PG schema测试涵盖原文/hash/未知时间、原登记指纹不变、跨租户外键、唯一确认与版本、Unicode/空白/控制符/错误摘要、未来恢复时间、声明伪造及ACL。测试原登记和确认全部事务回滚，随后只读确认恢复表0行、Q08两项value仍null。16迁移策略/52Python保护、完整根780=767执行（含242真实PG）+13历史fixture跳过、6MinIO排除及37契约通过。

V1–V14全部字节冻结，V14 SHA256为39be55d8759de87c6e2b8f85b3958c60be24ae9e6ece2deb5d849c225050ed72；后续结构修正V15+，API/Worker不自动迁移，API0.19不变。下一步当前成员与同租户手工父登记授权、确认CAS/审计/回执事务及真实并发；父表不可变且无UPDATE权限，不以额外授权换取显式父表FOR SHARE锁。结构测试不算业务恢复、公开HTTP、UI、监控回调或正式工单闭环验收。

显式独立命令：python tools/niko-test/run-migration.py --apply-manual-recoveries；只读复核--check。V14已执行，不能改原SQL。


## 2026-10-03 V13/V14已提交数据的真实API崩溃恢复

新增独立显式--verify-owned-v14-api-crash场景，先检查本轮证据路径不存在，保留此前11表与V10的14表证据。必须核验本次父进程直接启动的API进程、当前用户、精确Java/JAR命令、固定只读工件SHA256和回环8080监听，再SIGKILL。真实wait退出137、连接ECONNREFUSED已验证；用同一固定工件重启，PG和Keycloak在本次API故障期间未重启。

新身份登录前，比对20张明确白名单业务表的同快照只读数量/等值SHA256，全部不变；范围为原14表加客户评论、日历头/修订、手工登记、人工恢复确认和设计配置表，不等同整库或外部真实性证明。V1–V14前后独立只读schema check均执行0次迁移，未自动迁移。随后重新取得6个真实OIDC会话，故障前10/后12实际HTTP全部按API0.20契约验证。

两条合成手工原登记（其一已独立人工确认，其一尚无确认）在重启后均原样回读；原手工创建键仍返回原登记快照，原人工确认键仍返回原确认，原创建重放不重置当前恢复。登记与确认的原成功审计各仍1条/原ID，资产和未知实际恢复时间null保持。56Python进程/证据保护测试及39契约通过；Bash/Node语法检查通过。场景结束自动清理本次拥有组件，随后正常启动新的持续QA栈。

这是已提交后终止API，不是事务进行中被杀，不覆盖PG/WAL、Worker、OSS、浏览器/390px或原指定服务器故障验收。V1–V14和API0.20未改，其他合同阻塞仍保留，不把此场景标为所有恢复验收或128项完成。

见[真实V14 API崩溃证据](evidence/niko-api-v14-crash-recovery-20261003.json)。


## 2026-10-03 工作区丢失后的源码恢复与新组件基线

13:23 executor切换后，原MOP工作区/工具链/测试进程不可访问。通过同private仓库短期CI artifact恢复993个tracked文件，逐blob/整tree/原commit09b5854完全相符；Git仅恢复shallow HEAD，不带历史私有配置/未跟踪文件。13张旧截图全部原hash恢复，B08物化限制解除；仅查看其中3张历史移动截图，不等于新容量页面或实时浏览器验收。恢复导出新增原HEAD commit bytes并校验SHA，避免GitAPI日期丢失原+0800头信息；仍不导出祖先历史或秘密路径。

用户13:40明确批准重新建立PG/Keycloak隔离测试账号/随机凭据。新增窄范围prepare-private.py --create-approved-database-identity-credentials，只创建PG/Keycloak/OIDC6类凭据和6个测试用户，不创建MinIO/照片/导入签名凭据，不覆盖既有配置；2新增保护测试。凭据只在.local/niko-test/private，未进Git/提示/公共日志。

官方Node22.22.1/Maven3.9.9/Temurin17.0.20.1+1校验和恢复；PostgreSQL17.11官方Debian签名索引+3deb SHA256核验后仅解包工作区，Keycloak26.2.4官方发行SHA1核验。新PG/SCRAM/migrator-runtime分离、KC独立PG库/S256 PKCE/禁应用password grant，均回环；不是旧数据库备份恢复或原指定服务器操作。新库V1–V4显式执行4，V5–V16各独立执行1且立即重复0，最终只读check0；原SQL字节不改。API/Worker没有自动迁移。

新实例真实PG完整根1036=1023执行（322真实PG）+13历史fixture跳过，6MinIO排除；Web643/55契约/类型构建/Python79通过。另冷启动纯Java1042=701执行+341跳过（322PG+13历史+6存储），不和真实批次叠加。正常启动拥有的固定API JAR并验证回环监听，6个新OIDC授权码登录、34真实HTTP核对读写/原键重放/23能力/跨租户404/只读审计尾不变；4条合成业务记录不是旧数据。存储显式关闭，上传组件重测仍NOT_RUN。

[源码恢复](evidence/niko-workspace-recovery-20261003.json)、[截图物化](evidence/niko-repository-binary-materialization-recovered-20261003.json)、[新组件](evidence/niko-rebuilt-components-20261003.json)、[新HTTP](evidence/niko-rebuilt-environment-http-20261003.json)。B09源码与新PG/OIDC/API可用性已解决，但旧临时数据库未恢复；B01原服务器入口/私有连接、B02 OSS、B03新环境浏览器验收、客户口径/监控组件仍分别保留，继续独立开发。


## 2026-10-03 V17联系人结构

重建niko库独立V17执行1、重复0、只读check0，21表既有业务全列指纹不变；12结构PG/19迁移策略/82Python/57契约、根1088=1075执行含334真实PG通过。10项结构验证在未提交隔离租户用migrator、2项真实runtime验证ACL/触发器，全部回滚后新3表0行；没有新增身份绑定或持久联系人。API0.29保持ready，仓储/双连接竞争/API/UI/通知送达尚未验。V1–V17全部冻结，后续V18+，见[联系人](customer-contacts.md)。


## 2026-10-03 联系人导入结构

仅niko重建PG显式执行V18再V19，分别使用--apply-contact-imports和--apply-contact-import-fix，每次独立1、重复0、--check只读0。V19修复V18生成列在BEFORE触发器中的误比较，冻结V1–V19，下一修正V20+。26/28旧表等值指纹与13真实runtime结构回归通过，原服务器未执行；API仍0.31、没有业务导入作业执行，见docs/customer-contacts.md。


## 联系人Worker验证入口

根构建产出server/worker/target/mop-worker-0.1.0-SNAPSHOT-contact-imports.jar；仅显式开启MOP_CONTACT_IMPORT_WORKER_ENABLED=true，remote_test，复用私有runtime的3项受限PG连接。tools/niko-test/start-contact-worker.py要求先准备的独立固定artifact和明确租期，不初始化组件或自动迁移。tools/niko-test/verify-contact-worker.py --verify-owned-contact-worker只在自己的niko作用域进行一次实际进程证明；已有控制文件或证据时拒绝重复启动。验证使用真实仓储受理，6条合成联系人已归档，job历史保留，不等于公开HTTP或DOM。见联系人Worker证据。


## API0.32联系人导入实际HTTP入口

在已有隔离PG/KC与固定API0.32启动的QA shell，先运行capture-http-sessions.py刷新6会话，再显式运行tools/niko-test/verify-contact-import-api.py --verify-public-contact-import-api。一次性控制与证据已存在则拒绝重启场景；先确认无未完成作业/其他Worker，真实公开202受理后只启动/核验/关闭自身固定专用Worker。结果按实际公开接口逐条读取和归档合成联系人，5无关队列/身份表指纹一致，无迁移/新凭据。当前已验388请求、19作业、119联系人归档，Worker最终停止；重建栈只复用已批准本地数据，原服务器不改。

## 2026-10-04 独立V20导出结构

新增--apply-asset-exports，要求完整冻结V1–V19；V20真实执行1次后重复0/只读检查0，当前V1–V20冻结。28既有业务表迁移前后相同；首个未应用语法错误候选完整回滚并留证，修正后才成功。实际结构不代表API/Worker/OSS/下载已接通，原服务器V20未执行。见[导出结构与证据](asset-export-jobs.md)。

## 2026-10-04 V21巡检模板独立结构

在已有niko测试库显式--apply-inspection-templates执行V21一次；重复0/只读检查0，30张原业务表等值指纹不变。V1–V21冻结，后续V22+；只增加未发布模板头、不可变修订/检查项及完整性指纹保护，未种入模板或创建身份。14真实runtime结构测试回滚后无持久测试模板；尚无巡检仓储/API/执行单/报告验收。原服务器未执行，见[模板结构](inspection-templates.md)。

## API0.35未发布巡检模板HTTP入口

完成独立V21和全根构建后，正常停止原自有runner并重启现有PG/KC与固定API JAR，不初始化新组件或账号，不自动迁移。新API readiness要求巡检模板结构；storage=false保持。刷新6既有OIDC会话后运行tests/niko-inspection-template-api-http.mjs；首轮仅ready契约失败、创建0/待定0，显式--retry-after-readiness-contract-fix保留原控制文件后完成117请求/18合成模板全部归档。当前两个控制文件及成功证据均存在，不能盲目重跑；后续使用新独立场景，不删除历史证据。28无关业务表等值证明只覆盖HTTP窗口，不跨全根数据测试。API固定包SHA及结果见[实施记录](inspection-templates.md)。


## 2026-10-04 V23来源集合独立结构

显式`--apply-inspection-source-collections`执行1次，重复、`--check`及旧`--apply-inspection-worksheets`均0。48旧项目表全行指纹等值；17真实runtime结构与完整根1820实际执行（584PG）通过。V23 eabc4cf2512cdea920acf97ba1991499fde9d0901c7b91b5a8cc7b51f13599e8已冻结，修正只能V24+；原服务器不操作，API/Worker不自动迁移。新表只保存独立明确来源，不是正式报告；代码仓储/HTTP/新集合页面仍须后续验证。证据与范围见docs/inspection-report-sources.md。


## 2026-10-04 API0.38来源集合HTTP入口

V23已独立冻结，本轮仅只读check0执行，启动不迁移。正常退出自有旧runner后复用PG/KC数据目录/私有配置，固定API SHA42f6174a56dc40ba2efa4e2ad46a746733db381304a914263763973b9443ca89，11表在登录前全行等值。6既有OIDC登录后tests/niko-inspection-source-collection-http.mjs完成73请求/3不可变集合/1执行单1模板归档；原控制文件和成功证据已存在，不盲目重跑、不删除旧证据或集合。runtime账号仅追加/读取集合，summary不是正文校验，详情另核五层SHA；未知规则仍null且不生成正式报告。创建、列表、详情与ready契约见API0.38及docs/inspection-report-sources.md。


## 2026-10-04 集合页面显式组件HTTP测试

生产Page/Picker在`apps/web/qa/inspection-source-collection-page.http.ts`中用原生jsdom控件连接既有API；必须显式MOP_RUN_NIKO_SOURCE_PAGE_HTTP=true并选`--config vitest.niko-source-page.config.ts`，默认单元测试不收集该*.http.ts，不能将其称为真实浏览器。首次在途等待断言失败的控制已有reconciled标记和同key/body恢复证据；显式MOP_NIKO_SOURCE_PAGE_RETRY_AFTER_WAIT_FIX仅允许旧请求已恢复后使用独立r2控制。当前两控制与最终证据已经存在，不得盲目再运行或覆盖；新验证需另建明确测试场景。首轮/r2共2不可变合成集合保留，成功r2场景为真实HTTP11次/1创建审计，首轮失败及另3次原请求恢复分别留证；真实CSS/手机/WeChat仍NOT_RUN。


## 2026-10-04 V25变更报备草稿独立结构

继续复用niko-local-test既有PG17.11，独立CLI --apply-change-notice-drafts实际1次、重复/--check/旧--apply-backup-plan-drafts均0；53张旧表完整行指纹前后相同。新增三表四invoker守卫，没有新组件、账号、凭据、端口或代理；仅现有runtime具有所需草稿追加/读取和头版本更新权限。21真实PG结构测试通过，API0.39固定包未重启、Worker未启动，不自动迁移。审批/P3为null、执行/抑制false；原服务器、OSS、审批/SSH/通知联调均未因此完成。V1—V25冻结，后续修正新增V26，详见docs/evidence/niko-change-notice-schema-20261004.json。


## 2026-10-04 API0.40未审核报备草稿真实HTTP

复用既有PG17.11/KC26.2.4与V25；独立schema只读检查0迁移。旧拥有者runner正常退出后新固定API工件5cdbfa7620e72eb8caabf335a064e5138ee70d45ed6cda6fc492a7d066d36823启动，56张旧表在6OIDC新登录前完整行指纹等值；不称崩溃/故障恢复。83HTTP成功完成，2个自有合成草稿已归档，18修订/36设备引用/18成功审计（2创建），无pending原请求。审批/P3未知、不执行或抑制；原服务器/OSS未操作、无新账号或代理，API/Worker不自动迁移。当前completed控制.local/niko-test/change-notice-api-control.json禁止盲目重跑；需新场景才能再验证。见docs/evidence/niko-change-notice-api-20261004.json。
