# 本地、云端与指定测试环境验证记录

2026-10-01最新接回本地后，MinIO JVM依赖兼容修复已有独立实际证据：5项适配器安全测试、8项Controller隔离测试、3项指定MinIO存储测试通过。范围及工作区构建边界见[本地接续验证](local-runtime-verification.md)，不把这次SDK结果当作完整照片PG/HTTP/UI验收；以下云端结果保留其原环境和版本边界。

首次记录：2026-09-30；更新：2026-10-01。当前在云端接续`handoff/cloud-20261001-photos`，目标仍为全部128项合同需求。历史Windows本地应用曾通过loopback SSH隧道连接指定PostgreSQL/Keycloak/MinIO；云端尚未复现组件连接或应用启动。以下分开记录本轮云端验证与历史本地证据，不合并计数，不以fixture或新建本地服务代替指定组件。

## 本轮云端照片：PARTIAL

云端实际工具链为Node 22.22.0、npm 10.9.4、JDK 17.0.19、Maven 3.9.9、Python 3.12.14，通过`/workspace/cloud-setup/activate.sh`激活。`develop.txt`存在，私有runtime及known_hosts缺失；指定SSH路径经平台代理受到目标域名策略拒绝，Maven Central返回429、Google镜像403。完整后端构建、fullstart及照片真实联调均未执行完成。V1–V4无修改，V5仍是未执行草稿；API/Worker启动无自动Flyway迁移。

|检查|实际结果|证据边界|
|---|---|---|
|实际API契约V0.5|`node tests/contracts.test.mjs`：7项通过、0失败/跳过；`npm test`、`npm run contract:check`退出0|npm的Node测试发现输出为1个测试文件；不与直接运行的7项重复计数。新增4项验证照片投影、CAS/数量/重复引用、策略未知值与operation状态组合，只是JSON Schema测试|
|前端纯测试|Vitest 7文件49项通过（历史21项+新增28项）|client 12、photo 6、photoQueue 11、photoAttachments 6、ticket 6、configurationRevision 5、configuration 3；无真实HTTP、组件浏览器、手机或企业微信证据|
|前端类型及构建|vue-tsc与Vite build通过|主包464.07kB/gzip164.36kB；TicketsPage 47.41kB/gzip15.61kB，无包体警告；不表示UI验收|
|纯JDK策略断言|真实DomainException/PhotoUpload/PhotoPolicy编译；56个独立断言通过|修复JPEG首个结构EOI之后的尾随内容缺口；没有JUnit/framework stub，不覆盖Controller/Spring/MinIO模块类型检查|
|纯JDK领域与Worker设置断言|当次列明的domain源及WorkerRuntimeSettings编译；8+9个独立断言通过|原图隔离、hash/size、附件/退避、显式启用与运行账号/端点限制；不覆盖PG SQL、Worker租约或真实服务|
|Java语法解析|API/adapters 32文件、domain/persistence/worker 29文件解析通过|仅JavacTask.parse；无符号归属/类型检查，不等于Maven模块构建|
|新增JUnit及真实组件测试|NOT_RUN：API 33项隔离JUnit、3项指定MinIO；PhotoRules 5项、Worker 7项JUnit；PG照片16项|源码已加入，Maven依赖与指定组件未恢复；不能以独立JDK断言充作这些测试通过|
|指定PG/MinIO/OIDC HTTP/UI/恢复验收|NOT_RUN|V5迁移、运行权限、CAS/幂等/去重并发、撤权/grant到期、条件写、断链/重启/fencing及1440/390px页面均待真实执行|

公开机器摘要与验证时源码SHA-256见[cloud-photo-verification.json](evidence/cloud-photo-verification.json)。本轮将三个独立JDK断言程序交付到`tests/pure-java/`，在JDK17下执行`bash tools/verify-photo-pure-jdk.sh`可复现8+56+9个断言；严格编译真实源，不连接组件、不执行迁移。原`.local/cloud-validation/`语法解析工具仍只在本机保存，摘要保留原验证hash。辅助脚本语法/缺项预检通过也不等于fullstart通过。照片实现仍DOING/PARTIAL，UNSCANNED、生产UNVERIFIED、留存null；完整验收与客户规则门禁见[照片代码切片](report-photos.md)。128个合同TC仍全部NOT_RUN，照片不等于M5-5/M8-6全部DONE。

独立[照片策略证据](evidence/cloud-photo-policy-verification.json)保留JPEG修复前后的真实反例：旧版本接受合法JPEG+junk+FFD9及两个拼接JPEG，当前纯策略检查以400 VALIDATION_ERROR拒绝。无点号png/jpg/jpeg文件名的3个新增反例同样被拒绝。APP内FFD9/FFD8/FFDA、真实baseline stuffing/restart、progressive多SOS仍保留原bytes/hash并通过解码。合成TEM/DNL仅证明边界检查，完整解码仍拒绝，不能宣称额外codec支持或病毒扫描。前端49项是照片批次的验证版本，后续D04配置页测试另行记录，不计作新增照片测试。

静态审查发现工单投影在多次SELECT间可能混入并发新增附件。已加入独立只读REPEATABLE READ快照及详情/列表两个真实PG回归用例（包含在16项内），[修订记录](evidence/cloud-photo-read-snapshot-pending.json)只证明两源码语法解析与审查，不证明类型检查或真实回归执行。主查询快照为读取授权的线性化点；之后提交的撤权不会追溯取消此次读取。未新增V4运行角色的UPDATE/行锁权限要求，V4回归也仍待执行。

主聊天只读UI显示GPT-6.1 Sol选中、GPT-6.1 Sol Ultra菜单配置，记为UI_CONFIGURED；代理spawn参数显式指定同模型/强度。当前云会话最新原生/status界面显示used 143,050/total 258K，上下文记为UI_REPORTED 258K，1M为NOT_APPLIED。底层精确窗口与自动压缩阈值仍UNKNOWN，不由显示推定272000、95%或256k tokens；模型/强度服务端回显仍SERVER_NOT_ECHOED。当前Onboarding更新schema无context字段，也没有cloud-coordinator或host config工具；未改无效CLI或提示词覆盖，未重启。本结论仅描述当前云会话，不将原生模型最大窗口当作此会话证据，也不推定所有Cloud都不支持1M。

## 后续独立D04/D07纯功能

|功能|实际执行结果|边界与记录|
|---|---|---|
|配置筛选/建议差异|新增20项；当次全前端69项、类型和构建通过|[独立证据](evidence/cloud-configuration-comparison-verification.json)；无真实影响对象、HTTP/UI或发布验证|
|配置解析/不可变快照|Java17严格编译；15组250个纯断言通过|[独立证据](evidence/cloud-configuration-resolution-verification.json)；15个JUnit包装及真实持久化/客户核验未执行；登记值未激活|
|工单详情operation回读|新增29项；当次全前端98项、类型和构建通过|[独立证据](evidence/cloud-report-operation-verification.json)；只GET现有操作，真实OIDC/HTTP/UI仍未执行|

98项包含此前69项，69项包含照片批次49项，不重复累加。纯核心的合成核验/发布事实不表示真实客户批准；上述切片不完成完整D04/D07或128项合同验收。

资产依赖纯结构校验随后独立通过：JDK17严格编译、12组14,987个断言，其中独立算法对照4096个四节点图与25,000边深链。实现与复现见[资产依赖校验](asset-dependency-validation.md)及[机器证据](evidence/cloud-asset-dependency-verification.json)。没有API/PG保存或真实抑制联调，TC-M3-9仍NOT_RUN；不与照片或前端测试计数合并。

资产导入已解析行的纯预检独立通过：JDK17严格编译、14组1,479个断言，50行三类错误准确阻断三行，500行计算12.67ms；[实现边界](asset-import-precheck.md)和[证据](evidence/cloud-asset-import-precheck-verification.json)明确没有.xlsx/CSV解析或实际入库。该计时不表示完整导入≤60秒；TC-M3-6、真实并发配额和重复导入均NOT_RUN。

[工程CI配置](ci-validation.md)通过本地结构/脚本语法、官方action refs/metadata核验和实际Maven版本门禁；隔离离线安装及公开Web类型/构建实际通过，未改源码锁或node_modules。配置定义的三个GitHub job结果NOT_OBSERVED，Actions回读通道受到HTTP传输/代理拒绝，不能把配置或本地构建写成完整后端CI/联调通过。配置解析portable脚本另实际复现原15组250个断言，不计为新增测试。

## 历史本地已执行结果

以下仅为云端交接前的本地记录。本机当时为Windows 11、Temurin JDK 17.0.20、Maven 3.9.6、Node 22.22.1、npm 10.9.4、Python 3.12，使用独立`mop_test`、`mop-test`身份域和虚构测试数据。指定组件版本及缺失项见[组件清单](environment/component-inventory.md)；测试环境接入不表示客户生产配置、许可或合同验收已批准。

2026-10-01图片存储组件准备已单独验证：复用指定MinIO，项目桶/受限账号的真实Put/Get、Delete拒绝、photos前缀外写拒绝、其他项目桶不可见及匿名拒绝均通过。机器证据见[组件验证](evidence/photo-component-verification.json)，范围见[图片测试存储](photo-test-storage.md)。应用照片上传/补传、Worker及授权内容回读仍在实施，不能把这次组件验证归入尚未完成的业务验收。

2026-10-01继续本地开发，新增配置修订与内部审批功能已验证：Java纯单元18项、指定PG集成11项、前端15项、契约3项均通过。新增三主体真实OIDC下40个HTTP响应及1440/390px页面流程通过，详情见[配置修订验证](configuration-revisions.md)和[evidence/configuration-revision-http.json](evidence/configuration-revision-http.json)。客户正式确认、配置生效和外部下发仍未实现，不能因内部批准记为合同全量完成。

### 历史客户报障登记：已执行验证

用户已明确一期由本平台作为工单权威；本轮只实现真实PostgreSQL报障登记与查询，operation为PENDING/CONFIG_PENDING，正式状态、优先级与SLA字段为null。照片上传及其余D07闭环未完成。实现范围和接口边界见[客户报障持久登记](customer-report-registration.md)。

|检查|当前结果|证据边界|
|---|---|---|
|Java纯单元与指定PostgreSQL集成|24项纯单元、19项真实PG集成通过，共43项|全量检查22纯+19PG；会话修正后补跑2项纯测试并重新打包；历史fixture 13项跳过，不合并计数|
|会话岗位投影补充|2项纯测试、8次真实HTTP检查通过|current membership岗位与capabilities同源；原Cookie降级后报障202/回读200、配置与资产写403，finally恢复成员且权限版本递增|
|前端单元、类型与构建|21项通过；类型检查和构建通过|输入/Unicode、安全result_ref、稳定幂等键与当前客户端回归；主包462.32kB，无包体警告|
|契约检查|3项通过；实际契约V0.4|登记与正式业务状态分离，未知值不默认生效|
|真实OIDC/HTTP报障流程|4个角色真实登录；47次HTTP及V0.4 Ajv响应校验通过|登记/回读、同键重放/冲突、已知设备语义去重、不覆盖原描述、同租户他人/跨租户404、输入/CSRF/附件门禁、SQL游标授权绑定|
|1440px/390px真实浏览器流程|16类控件/场景通过；0 pageerror，无水平溢出|登记、事实回读、筛选恢复/分页、内部只读、切租户、已受理GET失败只回读、POST已提交但响应丢失后原body/同键重试、手机表单与详情操作可达|

公开脱敏证据见[本轮汇总](evidence/customer-report-verification-summary.json)、[HTTP契约](evidence/customer-report-http-verification.json)、[真实成员角色](evidence/membership-role-http-verification.json)、[页面验收](evidence/customer-report-ui-verification.json)。截图保留于本机Git忽略的`.local/qa-customer-reports/`。不能据此将完整D07或128项需求标为完成；未接入RabbitMQ消费者、通知、图片服务，不把CONFIG_PENDING outbox记录当作已经投递或处理。

### 首个测试切片

下表保留首个测试切片的原始计数，与后续配置修订/报障批次分别记录。

|检查|结果|证据与实际范围|
|---|---|---|
|Java编译、打包及纯单元|通过；13项纯单元，0失败/错误|现有Surefire：Profile 5、OIDC映射4、Session安全2、Postgres安全2；不把隔离替身当作组件联调|
|指定PostgreSQL集成|通过；5项，0失败/错误|独立仓储实例回读/重放、双连接并发幂等、异常整事务回滚、配置CAS与null不激活、真实运行权限及审计hash重算|
|历史fixture工作流|13项默认跳过|仅保留历史/隔离测试用途，不计入本轮共享测试环境通过数|
|前端客户端单元、类型与构建|10项通过；类型检查与构建通过|CSRF/幂等头、401处理、迟到响应隔离与未知值语义；不替代真实身份和数据库联调|
|API契约与原登记一致性|3项通过|原登记副本、pending null与写入口门禁；首个切片契约为V0.2、配置修订批次V0.3，报障批次实际契约V0.4|
|真实OIDC登录|通过|密码仅在Keycloak SSO页输入；标准签名/签发者验证成功，观察PKCE S256与state/nonce；身份以verified issuer+sub关联项目成员|
|真实RP注销与重新登录|通过|标准Keycloak注销后SSO清除，可改以viewer重新登录；临时授权客户端信息仅保留服务端会话|
|伪造注销与回调边界|通过|没有pending上下文的GET `/auth/logout`、invalid-state callback均未注销既有会话，随后 `/session` 仍200|
|真实API HTTP/OpenAPI校验|28个响应通过|真实OIDC Cookie下执行CSRF、fixture入口拒绝、资产与游标、持久幂等及并发、租户隔离、配置草稿/发布门禁和耐久审计|
|API重启后的持久回读|5个HTTP检查通过，进程身份断言通过|已确认API的PID/startTime变化及默认remote_test启动成功；重新真实OIDC登录后，原资产及created_at、原幂等key结果、配置草稿及版本仍保留，执行退出码0|
|真实受限运行账号|通过|audit INSERT=true、UPDATE/DELETE=false；配置registry_json不可UPDATE；Flyway历史不可SELECT；迁移与运行角色分离|
|真实成员撤权及降级|通过|旧Cookie读取与已提交写重放在撤权后401；仍active但降为viewer后写重放与配置读取403；测试后恢复原成员，权限版本单调增加|
|组件断链与恢复|通过|只停止本机SSH隧道，ready与写入口503；恢复后仍remote_test且durable=true，没有fixture回退，未停止远程服务|
|真实管理端与移动页|通过本批范围|PC 1440×900无pageerror；真实tadmin在390×844登录，无水平overflow；viewer无创建/配置/租户切换菜单，写与配置HTTP均403|

后端执行摘要读取 `server/api/target/surefire-reports/` 与 `server/persistence/target/surefire-reports/`。机器可读总表见 [verification-summary.json](evidence/verification-summary.json)，真实权限/撤权结果见 [remote-security-verification.json](evidence/remote-security-verification.json)。它们只记录环境、计数与行为结果，不包含密码、Cookie、CSRF token、OIDC token或完整协议跳转URL。

移动截图已生成于本机 `.local/qa-remote/assets-mobile.png`。`.local/`、编译产物、私有运行配置和会话文件均未作为Git交付材料；截图不等同于服务端授权或组件联调证据。

## 历史本地复现入口与云端待执行

Windows历史入口为`pwsh -File deploy/check.ps1 -Install`；云端Linux入口及迁移审批/执行分离见[运行说明](../README.md)。需先备齐受控私有配置、网络和已审核结构。现有远程PG测试的Flyway target为4，可能执行V1–V4与写入测试数据，不能作为只读检查或V5照片验收。

启动应用后执行 `npm run test:api`：捕获工具在已安装浏览器中完成真实Keycloak登录，将临时Cookie/CSRF只写入被Git忽略的私有会话文件，随后执行HTTP契约检查。重启验证的入口及执行顺序见README。云端或第二机器复现仍须通过受控私有配置连接同一指定测试环境；缺少连接资料不能据纯单元通过宣称已联调。

## 局限与历史记录

- 已验证的是上述测试切片。标准OIDC成功流程不等于已经实测全部伪造签名、密钥轮换、会话超时与身份灾备分支。
- PostgreSQL测试库和API的tenant+id约束不替代客户生产RLS/独立库及六层隔离验收；生产Q02/Q03/Q17与客户正式岗位仍待确认，production/integration不作为可用运行模式。
- Redis、RabbitMQ没有对应缓存/消息业务联调；MinIO项目空间和受限账号的历史组件验证不代替云端照片应用验证。Zabbix、外部ITSM、堡垒机及相关资料缺失保持登记状态，真实监控未知值不冒充正常。一期本平台工单权威已由用户确定，缺失外部ITSM不阻塞本平台持久报障登记；正式规则仍待落实。
- 报障耐久outbox仍CONFIG_PENDING，消息投递、其他业务消费者、审计外部受保护链头及至少3年保留治理尚未实现。本轮新增仅照片PG消费者/租约fencing代码，未真实运行，不因该代码宣称报障outbox已执行或送达。
- 第二机器/云端复现、客户H5、全浏览器、真实设备采样、容量/性能/灾备及128项合同逐项验收尚未完成。

首批local_fixture记录已归档至 [历史验证](history/2026-09-30-local-fixture-verification.md)、[历史QA计划](history/2026-09-30-local-fixture-qa-inventory.md)及 [历史摘要](history/2026-09-30-local-fixture-verification-summary.json)。其16项后端/33个HTTP等旧计数仅描述当时fixture阶段，不能合并为本轮真实组件结果。
