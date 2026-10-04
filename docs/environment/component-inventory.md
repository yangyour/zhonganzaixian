# 指定测试环境组件清单

历史服务器盘点日期：2026-09-30；本地接续验证更新：2026-10-01。云端已停止业务写入，本批本地通过工程loopback隧道复用同一指定服务器，照片PG/MinIO/OIDC实际联调已通过；历史云端未连通记录另保留。原连接资料仅在Git忽略的`deploy/private/develop.txt`，本文不记录密码、token或含密连接串。

用户要求本地启动及涉及外部组件的集成测试也复用本环境。默认运行模式为 `remote_test`，组件失败不得回退到fixture或本机替代服务。项目全局规则见 `AGENTS.md`。

|能力|服务器已有组件/版本|项目本机SSH转发|测试环境复用/验证状态|
|---|---|---|---|
|关系数据库|shared-postgres：PostgreSQL 17.9；原镜像timescale/timescaledb-ha:pg17-pgvector086|127.0.0.1:15432 → 服务器127.0.0.1:12060|独立mop_test、migrator/runtime；V5独立迁移一次及结构/ACL核验，重复Apply为0DDL；43项真实PG测试（照片24+原19）通过；file/job/grant/operation/附件CAS/审计持久化，V1–V4历史保持|
|身份|hltk-keycloak：Keycloak 26.2.4|127.0.0.1:18110 → 服务器127.0.0.1:12110|本项目mop-test realm/client及6个虚构身份；本批4个测试身份真实OIDC与80次HTTP/契约通过，沿用当前membership授权；正式身份源/岗位及客户批准仍待确认|
|缓存|shared-redis：Redis 7.4.9|127.0.0.1:16379 → 服务器127.0.0.1:12070|发现运行组件；本批没有依赖缓存的业务，不另建缓存、不声明已集成|
|消息|zhsx-rabbitmq：RabbitMQ 4.3.2|本机AMQP15673 → 服务器5673；管理16673 → 15673|发现运行组件；待outbox/Worker开发时建项目vhost与受限账号，不使用其他项目队列|
|对象存储|shared-minio：RELEASE.2025-09-07T16-13-09Z|127.0.0.1:19000 → 服务器127.0.0.1:12080|3项真实存储、原图/grant/hash；52次边界/恢复/撤权和25次运行中RETRY_WAIT/自然完成独立通过，finally恢复；历史IAM证据保留；扫描/保留/报告存储仍待|
|Web反代|已有nginx容器与服务；本项目静态原型容器zabbix-msp-prototype运行|现有原型端口10000；本地Vite5173|原型仍是静态参考，不能视为Zabbix采集引擎；未改既有反代或端口|

宿主探测为CentOS Linux 7、Docker 26.1.4。本轮复用现有测试服务，未升级宿主/组件，未停止或重配其他项目服务。版本/许可/支持范围是否满足客户正式生产基线仍按Q03核验，本轮测试接入不代替正式批准。

## 缺失或尚未具备的组件

2026-10-02 资产导入测试存储已在同一现有 MinIO 中新增独立桶 `mop-test-asset-imports` 与仅限 `asset-import-sources/*` 的受限账号。真实 Get/Put、Delete 拒绝、其他桶不可见、前缀外及匿名访问拒绝已验证；照片账号/IAM、服务配置和生命周期不变。见[原始源存储说明](../asset-import-storage.md)及[组件证据](../evidence/asset-import-component-verification.json)。应用大文件条件写、V6/Worker/解析/映射仍待独立验证，不能据本组件证明导入完成；扫描、留存和生产许可门禁继续保留。

以下结论仅为当前盘点范围内未发现运行实例，不代表合同功能被取消。

|组件|探测依据|影响|替代候选|后续状态|
|---|---|---|---|---|
|Zabbix Server/Proxy|容器与运行进程未发现采集引擎；仅有同名静态原型|资产草稿可以持久化，真实纳管、采样、事件处理保持CONFIG_PENDING|优先客户既有监控；Zabbix具体版本待许可，无已批准替代|登记缺失；待组件许可/环境与Q03/Q06确认，不擅自安装|
|外部ITSM/工单系统|未发现可确认的专用实例或接口资料|一期本平台权威已由用户明确选择，本轮PG报障登记不依赖外部ITSM；若后续需要外部对接仍缺接口，正式状态/SLA/处理规则尚未完成|本平台为已确认开发方向，不新增替代组件；外部ITSM候选仅在后续明确对接需求后评估|缺失登记保留；权威方向已明确，Q07剩余状态/规则及Q08/Q09/Q14/Q16继续登记|
|堡垒机/录屏服务|未发现可确认的JumpServer/Guacamole或客户堡垒机入口|真实远程连接、双审批执行、录屏不能验证|优先客户堡垒机；JumpServer/Guacamole仅条件候选|登记缺失；按Q01/Q03确认路线后实施|
|通知通道/企业微信应用|仅凭主机盘点不能证实账号、应用或回执API|通知送达和客户H5绑定暂不能联调|按客户指定通道，当前无已批准替代|资料缺失；按Q17等提供测试应用与授权|
|客户CMDB接口|未提供可确认的接口资料或测试凭据|现有测试种子不代表真实客户资产来源|客户指定CMDB优先，测试种子不充当正式替代|资料缺失；后续绑定外部asset/source映射|

## 命名空间与权限

只新增本项目mop_test数据库、mop_test_migrator/mop_test_runtime角色以及mop-test realm/client/test users。数据库运行角色非超级用户、无建库/建角色/复制权限；Flyway与运行身份分离，运行角色不具DDL权限，审计表仅SELECT/INSERT。不存在自动数据库clean入口。

角色的成员关系从项目数据库读取，Keycloak签发者与sub仅用于身份映射。用户名只用于展示，同名重建账户不得自动继承旧映射。数据库服务共享不表示已经完成客户生产租户RLS或全部六层隔离，Q02仍单独确认。

报障及照片切片复用同一PostgreSQL/Keycloak/MinIO，不安装组件或开放公网端口。V5仅新增所需照片权限和ticket的version/updated_at更新，原内容与审计无覆写/删除权限，运行身份无DDL；结构与ACL已真实核验。报障outbox仍CONFIG_PENDING、next_at=null，没有RabbitMQ投递；独立PG Worker只消费照片任务。联系方式UNVERIFIED，JPEG/PNG仅用户开发方向，扫描UNSCANNED、生产UNVERIFIED、留存null；Q28/Q10及正式岗位门禁保留。

公开探测摘要：`component-probe.json`。凭据仅在 `deploy/private/`；原始全机盘点输出不用于公共产品页面。

## 2026-10-01后续D04诊断复用

[D04诊断](../configuration-diagnostics.md)继续使用同一PostgreSQL17.9/Keycloak26.2.4及loopback项目隧道，没有新建组件、迁移或扩大照片MinIO权限。读取器在单只读REPEATABLE READ内复核mop_test/runtime、active user/membership、当前padmin与授权版本，只投影当前租户登记/提案元数据；定义、发布和客户核验来源保持NOT_IMPLEMENTED/null。

后续完整检查51项指定PG=既有43回归+新诊断8，3项MinIO回归、123纯/隔离通过；[安全属性摘要](../evidence/local-configuration-diagnostic-build.json)与照片旧构建按批次分开。六个真实OIDC身份50项[HTTP记录检查](../evidence/local-configuration-diagnostic-http.json)通过；只关闭本run项目PG转发时diagnostic/ready503、live/Keycloak200，恢复同上下文后200，finally恢复标记、ready、六会话及padminA。旧registry/draft count和服务器内SHA256未变化，没有DDL/原成员/原值变更。

[实际页面证据](../evidence/local-configuration-diagnostic-ui.json)另有26项PASS、0pageerror，真实Edge154.0.4258.48持续Playwright无头、1440×900/390×844仿真及Root逐图复核。旧列表非数组Q空表和非文本metadata标签投影已修，negative rows保留并可见/可查明确503；缺失Q仍unknown，没有修改数据或fixture回退伪造成功。最终前端154=初始147+新metadata7，全量/类型/构建通过，66定向不再累计。诊断不接入Redis/RabbitMQ/监控或正式发布/客户核验，不据读取成功宣称生产批准。

## 2026-10-01本地照片组件联调

独立CLI默认Check、显式ApplyPhotos；V5已执行一次并冻结，重复Apply执行0DDL。API/Worker启动不执行迁移，进程拥有者记录覆盖API/Web/照片Worker；停止前核验路径、命令和启动时间，不停止远程组件或其他项目。

配置照片能力后，`health/ready`实际核对PG/OIDC、策略、V5结构/ACL及MinIO，照片`enabled/ready/policy_ready/schema_ready/storage_ready`全部true才就绪，失败503及脱敏原因码，不静默回退。未绑定文件仅上传人可读，内部授权成员只可读取其他人已绑定登记的文件；原图必须经当前会话和平台grant授权。

[构建摘要](../evidence/local-photo-build-summary.json)为96纯单元/隔离、43 PG、3 MinIO通过，fixture13跳过。基础80次HTTP、[恢复52次](../evidence/local-photo-resilience.json)、[运行中重试25次](../evidence/local-photo-worker-retry.json)和[UI36项](../evidence/local-report-photo-ui.json)分别通过。旧52次脚本先停Worker，其RETRY_WAIT未执行范围保留；新25次在正常就绪后断项目转发，真实RETRY_WAIT与同PID/startTime退避完成原图/hash，finally恢复marker/hold、三进程就绪及四会话。新专项未改V5/IAM/成员/全局或远程配置；UI为Edge持久无头和手机仿真，非物理相机/企微；未新建组件替代服务。

## 历史：2026-10-01云端接续阻塞与新增代码

下表保留云端当时的NOT_RUN/BLOCKED，不作为本批本地状态；V5及后端编译的本地结果以上述记录为准。

|依赖|本轮证据与状态|影响及后续|
|---|---|---|
|项目私有配置|develop.txt存在；runtime.json和known_hosts缺失；helper预检明确拒绝|真实OIDC/PG/MinIO及启动NOT_RUN；通过受控私有配置交付已有绑定，不重新生成或旋转账号密码|
|指定SSH通道|平台代理目标域名策略拒绝，未建立本项目受控隧道|指定组件云端连通及集成BLOCKED；保留原主机信任配置，不开放公网组件端口、不改其他服务|
|Maven依赖下载|Maven Central返回429、Google镜像返回403；有工具链不等于构建已通过|完整Java编译/JUnit仍未完成；保留TLS/制品校验，网络恢复后重新验证|
|照片PG结构与权限|V5为未执行草稿；V1–V4未改；API/Worker启动不自动迁移|新增照片请求/Worker明确未就绪；先审核再独立迁移，不以普通启动或target4测试偷偷执行V5|
|照片业务|新增PG租约/fencing、MinIO条件写、附件CAS、15分钟平台grant及前端代码|DOING/PARTIAL；真实恢复/竞争/撤权/原图HTTP尚未验证，不把PG消费者记作RabbitMQ业务接入|

当前云端未新建数据库、缓存、队列或监控替代指定组件。历史MinIO桶/IAM验证仍有参考价值，但新增业务与本轮访问权限须另外验证。Q28扫描/隔离、Q10留存清理及生产Q02/Q03/Q17等门禁继续保留，未知结果不记通过。

工程[GitHub Actions验证配置](../ci-validation.md)固定官方actions完整SHA及项目工具链。SDK提交`eba5630`的历史回读见[安全摘要](../evidence/github-eba5630-verification.json)。照片提交`f56ad1b`的[独立run 36846410035](https://github.com/yangyour/zhonganzaixian/actions/runs/36846410035)已通过connector实际回读，契约/Web、纯Java和后端三个job及28步骤全部成功，见[照片摘要](../evidence/github-f56ad1b-verification.json)。CI不连接私有指定组件，不提供替代PG/缓存/队列/监控，不能计入组件联调。历史失败及当时读取阻塞仍保留。

## 2026-10-02 Dot直接工作区访问检查

本次从指定handoff分支SHA恢复源码并校验全部400文件，未接回此前云工作区的私有运行资料。`deploy/private/develop.txt`、`runtime.json`、`known_hosts`及导入命名空间私有配置均不存在；已检查MOP环境变量引用但未输出任何秘密。指定PG/Keycloak/MinIO/SSH本轮没有执行连接探测，状态为BLOCKED_PRIVATE_CONFIGURATION，不能沿用本地历史PASS宣布云端就绪。影响：真实API/UI、完整Worker故障/重启及指定组件集成验收未运行。后续需要受控提供既有私有配置和主机信任资料；不重新生成凭据、不创建替代服务、不放开公网组件端口。代码、隔离单元、契约、类型及前端构建可独立推进。

终端Git/gh未登录，普通HTTPS Git读取失败；已连接GitHub工具可读取指定分支，并用于逐文件校验获取源码。没有在日志或仓库保存访问令牌。

## 2026-10-02 Dot获取私有配置后的实际检查

在原工作区更新到`81b2352d19198c76e8e8bbfb705f224b7a9b599c`，确认远端分支和本地提交/目录树一致。6个配置已逐文件校验，JSON可解析，照片/导入桶与runtime一致，指定SSH目标在固定known_hosts中存在。没有输出秘密或改变这些文件。

凭据缺失阻塞已解除，网络阻塞仍在：指定SSH端口直连返回ENETUNREACH(errno101)，获准提升执行后的同一无认证探测结果相同；仅尝试现有环境代理的CONNECT超时。未发送登录凭据，未执行远程命令，未建立隧道，因此PG/Keycloak/MinIO及真实HTTP/UI未运行，不能记为组件故障或凭据错误。当前只证实本地loopback监听可用。需当前云工作区具备到指定SSH端点的已批准可用网络路径；未开放远端公网组件端口、修改网络/服务或切换任务环境。

原runtime的解析器JAR是Windows路径，在Linux不可直接使用；工程内已有构建JAR可作为启动时进程环境覆盖，尚未启动API/Worker。后续运行仍排除迁移账号变量，保持V1–V6冻结和不自动迁移。

发现旧`inspect_test_server.connect`使用AutoAddPolicy且会重写known_hosts，已改为强制既有固定目标键、RejectPolicy、不保存主机键；缺文件/缺目标键在认证前失败。5项隔离信任策略回归通过，不是服务器握手验证。见[脱敏连接记录](../evidence/dot-private-connectivity-20261002.json)。

## 2026-10-02 niko独立环境新增授权与真实结果

用户明确允许niko部署组件，新增范围不修改原服务器。新PG17.11/Keycloak26.2.4及API已运行，69真实PG、6真实OIDC身份及44配置审批HTTP通过；正常重启8表指纹一致。MinIO因netlink EPERM未启动，提升执行同样失败；存储改按用户选定OSS推进，尚未认证或写入。原远程网络阻塞保留，新环境与旧结果不混合。详见[独立说明](../niko-local-test.md)和[新证据](../evidence/niko-local-components-20261002.json)。

## 2026-10-02 独立浏览器验收边界

新环境PG/OIDC/API的客户报障HTTP可以继续；同执行空间Chromium启动前因Unix socket EPERM终止，支持的云浏览器访问127.0.0.1前端被ERR_BLOCKED_BY_CLIENT拒绝。未加载页面、无UI通过证据，不改变系统权限或创建代理/转接绕过。详见[独立记录](../evidence/niko-ui-blocker-20261002.json)。真实OSS仍待受控替换配置。


## 2026-10-02 niko PostgreSQL Unicode查询依赖

实际目录读取确认新库server_encoding=UTF8、datcollate/datctype=C/C，已有pg_catalog.und-x-icu（ICU提供者）；默认lower保持Ä不变，导致新增Unicode资产筛选回归首轮失败。改用该既有collation的逐表达式小写，不创建collation、不修改数据库/服务器locale或权限。真实PG检查Ä/ΟΣ/İ/K、原文保持和无Unicode归一，前后全局C/C未变；主台账/资料查询/引用文本采用一致表达式，ready实际验证依赖、缺失失败503。原指定服务器尚无法连通，不能宣称具有相同依赖或效果。详见[主台账读取](../asset-ledger-reads.md)。


2026-10-02补充：niko API从每次启动独立SHA256/0400副本运行，不再直接使用可被Maven覆盖的target。真实重构建中target字节变化、API进程及副本不变，随后6身份OIDC/20只读HTTP通过；原ThrowableProxy时序线索、首轮/proc children采集失败和边界见[运行产物隔离](../niko-runtime-artifact.md)。未改变组件版本/账号/权限/原服务器；48守卫和根616（163真实PG）通过，其他阻塞仍在。


2026-10-02：niko独立V11客户评论表显式迁移1、重复/检查/旧模式0，16列/14CHECK/租户FK及runtime只追加ACL核验，7真实PG schema测试全部回滚后新表0行。V1–V11冻结，无原服务器/组件账号/父工单授权变化；尚无评论业务仓储/HTTP证据。见[客户评论结构](../ticket-comments.md)。


## 2026-10-03 V13/V14已提交数据的真实API崩溃恢复

新增独立显式--verify-owned-v14-api-crash场景，先检查本轮证据路径不存在，保留此前11表与V10的14表证据。必须核验本次父进程直接启动的API进程、当前用户、精确Java/JAR命令、固定只读工件SHA256和回环8080监听，再SIGKILL。真实wait退出137、连接ECONNREFUSED已验证；用同一固定工件重启，PG和Keycloak在本次API故障期间未重启。

新身份登录前，比对20张明确白名单业务表的同快照只读数量/等值SHA256，全部不变；范围为原14表加客户评论、日历头/修订、手工登记、人工恢复确认和设计配置表，不等同整库或外部真实性证明。V1–V14前后独立只读schema check均执行0次迁移，未自动迁移。随后重新取得6个真实OIDC会话，故障前10/后12实际HTTP全部按API0.20契约验证。

两条合成手工原登记（其一已独立人工确认，其一尚无确认）在重启后均原样回读；原手工创建键仍返回原登记快照，原人工确认键仍返回原确认，原创建重放不重置当前恢复。登记与确认的原成功审计各仍1条/原ID，资产和未知实际恢复时间null保持。56Python进程/证据保护测试及39契约通过；Bash/Node语法检查通过。场景结束自动清理本次拥有组件，随后正常启动新的持续QA栈。

这是已提交后终止API，不是事务进行中被杀，不覆盖PG/WAL、Worker、OSS、浏览器/390px或原指定服务器故障验收。V1–V14和API0.20未改，其他合同阻塞仍保留，不把此场景标为所有恢复验收或128项完成。

见[真实V14 API崩溃证据](../evidence/niko-api-v14-crash-recovery-20261003.json)。

### 2026-10-03 07:18 niko联调执行通道恢复

旧交互QA会话在exec传输断连后不可恢复。使用已有标准启动脚本重新启动获准回环PG17.11/Keycloak26.2.4/API固定工件；未新建库/身份凭据、未迁移、未手工移除PG PID文件。就绪、完整根875（862执行含264真实PG）及6OIDC/79日期列表HTTP通过，当前API0.23。原服务器B01/OSS B02/DOM B03/其他业务组件B05不因此解除。


## 2026-10-03 补充维保V15结构切片

独立V15执行1次，重复/只读/旧目标0次；新增可空起始/供应商及明确声明标记，旧默认写入在历史已声明后失败关闭，原列/回执/审计20表投影指纹不变。10新真实PG结构、17迁移策略、60Python保护、根935（922执行含283真实PG）/49契约通过；结构测试回滚后补充声明0行。V1–V15冻结，下一迁移V16+。仓储/HTTP兼容/UI、供应商核验/送达/DOM仍未完成，不标M3-10或128项DONE。见[结构与边界](../manual-warranty-details.md)。


## 2026-10-03 独立维保API0.26

10控制器/根962（949执行含295真实PG）/51契约/554Web/类型构建和97真实HTTP通过；1合成资产11修订11维护审计。旧体保留补充字段/显式清空/旧新原回执/当前资源重放授权/两角色混合并发200+409/10+1历史分页有证。旧基础DTO不增列，供应商及覆盖未核验；页面/DOM、隐患送达导出仍待。V1–V15不改、不自动迁移，M3-10与128项非DONE。见[维护说明](../manual-warranty-details.md)。
# 2026-10-03 本地复核补充

当前 Windows 可通过固定主机键 SSH 访问原指定组件；实读 PostgreSQL17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z，PG已有 `und-x-icu`。原项目 `mop_test` 从V6逐功能升级到V15，运行/迁移角色及其他项目隔离不变，未改服务生命周期或全局配置。每功能1次、重复0次、最终只读0次；见[本地迁移记录](../local-schema-continuation.md)与[结构证据](../evidence/local-schema-v15-20261003.json)。用途是承接新增工时、资料、评论、日历和人工事件等真实业务验证，结构通过本身不等于这些功能联调完成。云端B01、OSS受控认证及外部监控/通知缺口仍保留其原范围。

## 2026-10-03 本地应用与完整存储流水线

原指定PG17.9/Keycloak26.2.4/MinIO的API、照片Worker、导入Worker均已实际启动；295PG、6MinIO、六OIDC及97维保HTTP已验。原文件在真实PG受理、MinIO存储、独立解析子进程解析；本项目回环存储断链进入RETRY_WAIT、恢复后同一任务完成。用途与状态已从仅组件可访问推进到上述明确范围的真实业务验证；Redis/RabbitMQ业务集成、监控/通知/堡垒机、OSS受控凭据仍沿用缺口，未擅装替代组件。就绪仍不虚称Worker活性，任务执行证据另见[本地记录](../local-runtime-validation-20261003.md)。

2026-10-03收尾：原组件不变，最新API0.27与两个Worker已重新就绪、六新OIDC及72容量HTTP通过；目录现21项，无新组件、迁移或业务写入。完整各批边界见[本地交付](../local-delivery-20261003.md)。


## 2026-10-03 容量场景V16结构切片

仅niko项目库独立V16执行1次、重复/只读/旧目标0次，V1–V16冻结。12真实PG结构/18迁移策略/63Python、根1004（991执行含307PG）/53契约通过；20表旧值指纹不变、测试回滚后场景表0行。Windows显式入口已补，49PowerShell保护待CI，本机未执行；原服务器V16未验。仓储/公开保存/UI与完整M10-7/128项非DONE。见[结构边界](../capacity-estimate-core.md)。


## 2026-10-03 niko联系人V17结构

仅现有niko PG17.11项目库独立V17 1/0/check0，21既有表全列不变；12新增真实PG结构、根1088报告/1075执行含334PG、19迁移策略/82Python/57契约通过。结构角色范围和0残留见[联系人边界](../customer-contacts.md)。没有新组件或账号，原服务器V17、联系人仓储/API/页面/并发与渠道送达未验；下一迁移V18+。


2026-10-03 18:44 niko重建实例：既有PG显式V18/V19联系人导入结构分别执行1/重复0/check0；V18真实功能错误用V19独立修复，原SQL不改。最终13真实runtime结构测试通过/全部事务回滚，新队列与结果0行；现有API0.31正确health/ready端点200。没有启用实际导入Worker、对象存储或通知组件，原服务器迁移仍未执行。详见niko-customer-contact-import-schema证据。


2026-10-03 19:39 niko联系人Worker：新增专用contact-imports JAR复用现有受限PG，无新账号/对象存储/通知组件。实际正常处理与在途SIGKILL(-9)/重启fence2恢复通过，3次JVM均只管理本次直接启动且已核验身份的进程；API、PG、KC未重启。所有验证Worker最终停止，6合成联系人归档、作业终态保留；不声明持续生产服务或公开202接口就绪。


2026-10-03 20:18 niko重建实例：自有隔离PG/KC/API正常关闭再以固定API0.32重启，六真实OIDC会话刷新；公开202/creator回读与专用Worker合计388HTTP通过，19作业119自建联系人已归档。仅复用现有受限PG账号，V1–V19不变；Worker验证后正常停止，ready仅证明结构/访问，worker_liveness=null。五无关队列/身份表指纹未变，原服务器/OSS/通知/浏览器B03仍未补齐。


## 2026-10-03 21:40 niko监控组件候选：待单次明确授权

为补齐B05真实监控缺口，已提出仅niko的Zabbix 7.0 LTS Server/Web/Agent及必需依赖候选，并请求批准新建独立测试库、受限数据库用户、测试管理账号和只读API账号。范围为本项目测试实例、回环监听、随机凭据仅私有目录；原服务器和MOP库不改。此时尚未收到本次明确确认，**未安装、未创建身份/令牌/数据库、未启动采集**；此前PG/KC许可不自动扩张到新Zabbix账号。相关步骤保持待许可，其余代码/测试继续。

只读预检：当前自己的执行环境为Debian13.6/x86_64；command -v未发现docker/php/nginx，可见dpkg-deb。未取得可用的cgroup memory.max，因此资源容量未确认，不能把宿主机内存推成测试容量。只有公开官方文档/包索引查询，未下载或运行新组件。官方[生命周期](https://www.zabbix.com/life_cycle_and_release_policy)列7.0 LTS完整支持至2027-06-30、有限支持至2029-06-30；官方[7.0要求](https://www.zabbix.com/documentation/7.0/en/manual/installation/requirements)和[Debian包目录](https://repo.zabbix.com/zabbix/7.0/debian/pool/main/z/zabbix/)可作后续版本/依赖核验来源。具体补丁包、签名/摘要、资源与真实兼容性尚待核验，不使用不稳定版本或关闭TLS/主机信任。

该候选仅开发测试，不替代客户Q03生产许可/版本决定、Q06阈值矩阵或原服务器环境。未知监控仍未知；不会把当前阈值数学诊断当采集引擎。

## 2026-10-04 01:18 B03现有Chromium启动探测

本次实际发现 `/usr/bin/chromium`，版本154.0.8037.57（Debian13）；不再将“未取得Playwright下载包”解释为系统没有浏览器。使用已安装Playwright1.62.1，要求Chromium沙箱开启，先普通启动、再通过执行权限审核路径启动空白页；两次均在process_singleton的socket创建阶段返回Operation not permitted，页面打开0。第二次使用项目私有XDG目录，仍失败；ptrace也被禁止。

未关闭沙箱、修改防火墙/端口/系统权限、开放公网、传入凭据或安装新软件。原云浏览器localhost阻塞、官方包下载失败保留为各自历史，不能混为新探测结果。B03仍阻塞当前真实DOM/移动视口验收；继续代码和隔离测试。证据见 [启动结果](../evidence/niko-browser-startup-blocker-20261004.json)。

## 2026-10-04 原指定库V19接续

当前Windows通过固定主机键SSH实读PG17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z；原mop_test从V15逐功能升至V19。四步各1次、重复0次、最终只读0次，58迁移/27进程保护通过；备份存本机忽略目录，未实际恢复。用途为容量草稿、联系人修订与批量作业，结构通过不代表本次实际Worker/页面已验；完整组件回归和浏览器结果后续单列。未改变已有共享组件生命周期/端口/全局设置。证据：[原库V19](../evidence/local-schema-v19-20261004.json)。

## 2026-10-04 Windows联系人Worker真实运行

已在原PG17.9/Keycloak26.2.4/MinIO上启动API/Web及照片、资产导入、联系人三个专用Worker；六真实OIDC及35租户GET通过。联系人实际批量2行成功、五角色重启后原键回放及新1行成功均有证；恢复脚本保留原明确启用的联系人角色，不擅自启用未登记Worker。原远程组件不重启、不新增账号/权限；未知监控/配额/OSS/通知及PoC仍待。完整根399PG+6MinIO，后续导出库另6只读PG，边界见[运行验证](../local-contact-validation-20261004.md)。

## 2026-10-04 V20异步导出结构独立执行

V20首次候选CASE语法失败完整回滚，只读确认仍V19/无新表函数，修正未应用资源后独立执行1次、重复0/只读0；28既有表等值指纹不变。V1–V20冻结，16新增PG结构回滚测试和2迁移策略通过，根1522报告1509执行含434PG/13跳过/6MinIO排除，73契约。无应用导出受理/Worker/OSS/下载/通知，原服务器V20未执行；见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 导出排队任务正常停启与新JVM回放

受理提交a38c84a的CI全过。实际停止/重启仅本runner PG/KC/API，PG启动时间改变；26排队任务、0领取及原幂等/审计四表相同。新JVM读回26任务/回放26原收据，接受回调0、四表再比相同；随后6真实OIDC登录通过。首次不必要的Flyway历史runtime读取被拒，移除该探针且未提权。9新增纯门禁/122Python/73契约通过；不当Worker崩溃或OSS完成。见docs/asset-export-jobs.md。

## 2026-10-04 独立导出准备Worker实际进程与故障恢复

新独立模块只处理显式单job，不启动其他队列/Web/迁移/存储客户端。4个真实自有JVM验证：实际XLSX准备、源备注变化后保留原文件、新PG审计等待点SIGKILL(-9)无半文件、过期后fence2恢复；原收据QUEUEDv0保留，7无关表等值指纹不变，Worker全部结束。15Java/9新增Python门禁，根1604报告1591执行含482PG/13跳过/6MinIO排除，131Python/73契约通过。仍未发布或调用OSS/下载/通知，见docs/export-preparation-worker.md，M3-7非DONE。

## 2026-10-04 09:14 巡检模板页面B03复核

niko现有/usr/bin/chromium版本154.0.8037.57、Playwright1.62.1，保留chromiumSandbox=true启动仍因Operation not permitted/EPERM失败，应用页打开0。没有更换组件、禁用沙箱、变更系统/网络权限或创建新任务。新的巡检模板页面17项SFC/jsdom与此前99真实客户端HTTP分别成立，不能合并宣称真实浏览器验收；桌面/移动视口仍NOT_RUN。见[当前阻塞证据](../evidence/niko-inspection-page-browser-blocker-20261004.json)，继续不依赖浏览器的开发。

## 2026-10-04 09:41 既有联调栈在终端断连后恢复

QA73514传输恢复失败并返回Unknown process id；发起的新根日志不存在。源码/私有配置/既有PG数据仍在。只用原启动脚本恢复同一隔离组件，未手动移除PID、初始化数据库、创建凭据或迁移；PG17.11日志报告非正常关闭后的WAL自动恢复并ready，KC/API重新可用。新QA2689，V21只读check执行迁移0，6OIDC刷新及40HTTP核对36已知归档模板通过；不是故障前后全库字节等值证明，终端丢失原因未查明。见[恢复证据](../evidence/niko-inspection-session-recovery-http-20261004.json)，B01原服务器、B02 OSS、B03真实浏览器等不因此解除。

## 2026-10-04 10:11 niko V22独立结构验证

复用QA2689的原PG17.11，独立CLI显式V22一次/重复0/只读0，33旧表迁移窗口等值；18新增真实受限账号结构测试回滚，根1699执行含533真实PG通过。没有新账号/服务/原服务器操作，API0.35保持、不自动迁移；执行单表结构不代表巡检业务/API或B01/B02/B03依赖通过，见[实际证据](../evidence/niko-inspection-worksheet-schema-20261004.json)。

## 2026-10-04 10:44既有组件普通重启与执行单HTTP

QA2689主动正常退出后，以原脚本/原数据/原凭据重启现有PG17.11/KC26.2.4/API0.36，QA62871活动；PG日志有正常关库/再启动，9张巡检/审计/收据/身份表重启前后等值（75个旧执行单）。V22只读check、迁移0，6真实OIDC会话及124执行单HTTP通过，HTTP窗口28其他表等值。未做强制崩溃/真实存储/浏览器、未改原测试服务器。首次另一Web执行器丢失后在QA62871重跑1059Web通过；组件证据边界见[API验证](../evidence/niko-inspection-worksheet-api-20261004.json)。

## 2026-10-04 本地原指定库V22接续

本轮固定主机键SSH实读原PG17.9的mop_test为V19；应用停止后逐功能执行V20导出队列、V21模板修订、V22人工执行单，各1次/重复0次/最终只读0次。41张既有业务表行数与全行摘要保持一致，备份仅存本机忽略目录且未实际恢复。复用原运行/迁移角色，无新服务、账号、全局权限或端口变更。结构可用不等于导出发布、模板批准、执行单公开API或季度报告可用；Worker/HTTP/DOM另行留证。见[独立结构证据](../evidence/local-schema-v22-20261004.json)。


2026-10-04 Windows导出准备复用原PG17.9和mop_test_runtime，14次JVM尝试未新增账号/服务或迁移。最终自有项目源表临时锁已释放，所有验证JVM退出；XLSX和进程恢复范围见[证据](../evidence/local-export-preparation-worker-20261004.json)。发布、OSS、下载和通知仍未接通。
## 2026-10-04 11:34 执行单页面B03复核

niko自身Chromium154.0.8037.57配Playwright1.62.1、chromiumSandbox=true仍在启动时EPERM/Operation not permitted，应用页打开0；未降低沙箱/系统安全或建立代理。30输入/SFC模拟DOM测试与11实际来源HTTP分别通过，不能合并宣称浏览器/移动视觉验收。见[当前探测](../evidence/niko-worksheet-page-browser-probe-20261004.json)及[页面边界](../evidence/niko-inspection-worksheet-page-20261004.json)，继续不依赖该入口的开发。


2026-10-04 API0.36/V22本地五角色已连接原PG17.9/KC26.2.4/MinIO，ready实测8.253秒后200；只调项目启动等待窗口，未改共享组件。100行专用Windows联系人作业真实完成并归档，见[实际证据](../evidence/local-contact-maximum-worker-20261004.json)。通知/身份绑定/OSS保持原门禁。


2026-10-04 原指定PG17.9/KC26.2.4巡检模板、执行单API/请求模块及真实Edge桌面/390px已验；B03只对这两页面明确场景解除，物理设备、证据/通知/季度发布和客户确认仍待。没有新组件/账号；见[本地记录](../local-inspection-validation-20261004.md)。


2026-10-04 最新来源捕获只复用原PG17.9，13真实只读捕获/3选择/20清单已验，未新增接口/表/组件/身份；原V22及API0.36保持。Windows符号链接创建权限缺失只影响1条路径保护测试前置条件，未修改宿主机权限；该条明确SKIP，见[最终证据](../evidence/local-inspection-source-capture-20261004.json)。


## 2026-10-04 niko来源追溯新UI的浏览器路径

13:43 UTC已实际发现托管Chromium/CDP运行，按既有Vite回环5173/真实API8080打开来源页面；导航返回ERR_BLOCKED_BY_CLIENT，随后观测被浏览器URL安全策略拒绝。应用页打开0，新来源面板真实DOM/移动/WeChat验收NOT_RUN；不将此原因归为机器人检测，也不改协议、代理、端口或沙箱绕过。既有原Windows28项模板/执行单检查保留为旧页面独立证据，不能代替新增面板。仅关闭此轮拥有的Vite进程，PG/Keycloak/API不停止。详见docs/evidence/niko-inspection-source-ui-browser-blocker-20261004.json；其余19真实只读HTTP继续验证，无新组件/凭据/迁移。


## 2026-10-04 niko来源集合V23结构

只在已批准niko-local-test PG17.11执行独立V23一次、重复/检查0；48既有项目表迁移前后完整行hash一致。新增两张来源集合表及三触发器，已有runtime仅追加/读取，无新账号、组件、端口或代理。17真实runtime结构测试通过；原指定服务器本轮未操作，API/Worker不自动迁移。这里只证明冻结来源结构，不代表季度业务规则、正式报告/存储/附件发布，见docs/evidence/niko-inspection-source-collection-schema-20261004.json。


## 2026-10-04 niko来源集合API0.38

继续复用既有PG17.11/Keycloak26.2.4，普通拥有者守卫重启到固定API0.38工件，11表完整行在再次OIDC前等值（含88既有集合）。6登录/73HTTP通过，3新增合成集合留存且全未发布，父1执行单/1模板已归档；未改端口、身份、网络、存储或迁移。只读V23结构检查0执行。原服务器未操作，新集合UI及B03浏览器路径仍未验，季度规则待确认；见../evidence/niko-inspection-source-collection-api-20261004.json。
