# 本地、云端与指定测试环境验证记录

更新：2026-10-01。云端最后交接`1db3976`已接回本地并停止云端业务写入；本批使用指定服务器组件和虚构项目数据，不以fixture或本机新服务替代。当前本地结果与以下云端/历史记录分开，不跨批次重复累加。

## 本批D04配置诊断（独立记录）

[配置有效性诊断](configuration-diagnostics.md)只读取当前租户的一致PG元数据，CONFIG_PENDING/null和NOT_IMPLEMENTED来源明确保留。第三轮完整检查退出0；下列结果不改写后续照片f56ad1b及SDK/CI历史，也不重复累计旧测试。

|检查|实际结果|证据与边界|
|---|---|---|
|最终Maven编译/打包/检查|29套190报告项，13fixture跳过，177实际执行全部通过，0failure/error|[安全XML根属性摘要](evidence/local-configuration-diagnostic-build.json)；123纯/隔离、51指定PG、3指定MinIO；只读name/counters/time，不解析环境属性、用例正文或日志|
|新增D04源与API|JDBC隔离13、Controller8、MVC6全部通过|包含于123项；MVC使用隔离依赖，不充当真实OIDC或PG|
|指定PG诊断|新增8项通过，既有43项回归通过；合计51|新MOP-FAKE租户/主体/登记/提案、一致快照与撤权CAS恢复、metadata形状/脱敏/5条限制；旧43项不作为新增|
|前端/类型/构建基线|初始11文件147项=旧113+新34，vue-tsc/Vite build通过；1665 modules/8.81s，无warning|main464.09kB、Configurations64.51kB、Tickets52.05kB、共享ticket9.68kB；保留初始完整check结果，59项定向不累加|
|UI安全修复后最终Web|12文件154项=初始147+新metadata7，全量/类型/构建通过；10.26s，无warning|main464.09kB、Configurations66.27kB、Tickets52.05kB；66项定向包含于154，不加为220|
|实际契约V0.7|generator check与10项契约测试通过|新只读单key诊断契约；Schema不替代真实接口或客户确认|
|6身份真实OIDC/HTTP|50项记录检查PASS：49项对应V0.7 Schema，另1项直接Keycloak well-known|[HTTP证据](evidence/local-configuration-diagnostic-http.json)；实际APPROVED仍pending/null、A7/B1近期5/截断与租户隔离、正文标记不返回|
|项目PG转发故障/恢复|diagnostic503/ready503、live200/Keycloak200；恢复同context/key200；finally恢复|只本run项目标记；旧registry/draft count及服务器内SHA256前后相同；没有DDL、成员或原值变更，6份会话/padminA/ready恢复|
|1440px/390px诊断页面|26项PASS，0pageerror，Root逐图复核|[UI证据](evidence/local-configuration-diagnostic-ui.json)：真实Edge154.0.4258.48持续Playwright无头，1440×900/390×844仿真；长key/行标识/入口/footer可达，document无横向溢出，表格内部水平滚动|

前两轮测试复现问题保留：新增独立fake账号暴露旧全局role picker不稳定，改成目标tenant内active且岗位匹配主体；新MVC嵌套配置误入正常MopApp扫描导致ProfilePolicy3/5失败，最终用@TestConfiguration及专用隔离test profile，原产品守卫和断言保留。MVC6+ProfilePolicy5定向11项及第三轮完整检查通过；没有通过放宽产品规则或修改原成员/185登记促成成功。

真实页面通过来源缺口/pending/null、APPROVED非active、近期5条/truncated、租户A/B/A、旧GET关闭后延迟不能替换新key、GET网络错误重试、只读面板往返及编辑modal保护。真实提案POST提交后丢响应，原表单重试回读同server ID；发布422且值仍null；异常Q记录保留并能查503，缺失Q显示unknown，viewer无入口。旧Q.join空表及非文本metadata标签投影已修，新增7项metadata回归；没有删改negative rows。141秒混合探索含截图；延迟辅助gate曾超时重启QA kernel，最终持续会话重跑并关闭浏览器，按最终26项证据记录。

D04运行定义/作用域优先次序、正式发布、客户真实性/资格、生效消费和业务快照仍未接入；本批完成诊断读取切片，不标完整D04/128项合同DONE。HTTP50、UI26、JVM177及最终前端154各有不同统计单位，初始147与最终154有包含关系，不合并或重复累计。

## 本批本地照片接续

|检查|实际结果|证据与边界|
|---|---|---|
|V5独立迁移及结构/ACL|Check后ApplyPhotos执行V5一次；核验通过；再次Apply为0DDL|迁移身份执行，API/Worker不自动迁移，V1–V4历史保留；V5 SQL SHA-256 `5A84F62BDAAB9BADFD5D87F4D30AE0AC1F9C1AE2F48D0D84E0635485A7BBDC55` 已冻结|
|完整Maven编译、打包及纯单元/隔离|96项通过，0失败/错误/跳过|根`deploy/check.ps1`真实执行通过；现存25份Surefire仅解析安全testsuite属性，不读取日志/环境属性|
|配置解析JUnit包装|15项通过，0失败/错误/跳过|`cn.mop.persistence.configuration.ConfigurationResolutionTest`包含于96项；仍无配置解析PG/API接入或客户批准|
|指定PostgreSQL集成|43项通过，0失败/错误/跳过|照片24项+原基线19项；PG事务/ACL/快照/CAS/租约/fencing测试，不代替真实MinIO或页面|
|指定MinIO存储|3项通过，0失败/错误/跳过|复用项目桶与受限账号；真实原图存储/hash和不可覆写行为；历史fixture13项仅跳过，不计通过|
|前端全量、类型及构建|全量113项通过；遮挡修复后41 operation+3 workspace定向回归及最终vue-tsc/Vite build通过|44项已包含于113项，不累加；最终Vite7.3.6、1661 modules、8.10s，主464.09kB/Tickets54.49kB/Configurations49.84kB，无包体警告|
|实际契约V0.6|最终generator --check及8项契约测试再次通过，退出0|包含typed照片就绪；此次是既有8项回归，不累加；JSON Schema与真实HTTP Ajv分别记录|
|GitHub CI独立回读|eba5630的历史push run 36836074365成功；照片提交f56ad1b的push run 36846410035为completed/success，3个job及28步骤均成功|[照片步骤摘要](evidence/github-f56ad1b-verification.json)对应照片SHA，不连接私有组件、不计联调|
|真实OIDC/HTTP及原图链路|4个测试身份真实登录，80次HTTP全部通过并经V0.6 Ajv校验|真实Worker→指定PG/MinIO原图存储、平台grant和hash回读；[公开HTTP证据](evidence/local-report-photo-http.json)|
|边界/重启/存储断链/撤权专项|4场景52次真实HTTP通过，finally恢复|5_000_000接收/5_000_001拒绝及原图hash；耐久API/Worker重启；MinIO项目转发断链/503/恢复；成员撤权/旧Cookie与grant拒绝；[专项证据](evidence/local-photo-resilience.json)|
|运行中Worker断链RETRY_WAIT|独立25次真实HTTP通过，finally恢复|正常ready200启动后断项目MinIO；PG快照证明真实RETRY_WAIT、未来next_at、lease释放和重试审计；恢复后同PID/startTime自然完成原任务/hash；[独立证据](evidence/local-photo-worker-retry.json)|
|照片1440px/390px页面|36项通过，0 pageerror；Edge154.0.4258.48持久Playwright无头|1440×900及390×844手机仿真，正常控件操作与逐图检查；[UI证据](evidence/local-report-photo-ui.json)，不代表物理相机/企微/全浏览器|

[local-photo-build-summary.json](evidence/local-photo-build-summary.json)核对现存Surefire：25套155项，142项通过、13项历史fixture跳过，0失败/错误。96纯单元/隔离、43 PG和3 MinIO与旧SDK16项有包含关系，不重复累加。SDK修复已独立提交`eba5630`，详情和运行入口见[本地接续验证](local-runtime-verification.md)。

照片就绪实际检查PG/OIDC、策略、V5结构/ACL及MinIO；已配置时照片enabled/ready/policy_ready/schema_ready/storage_ready全true，缺依赖503且不会fixture回退。未绑定file/FILE_UPLOAD仅上传人可读，内部角色按当前授权读取其他人已绑定登记的照片。前端受理后只GET，跨登记明确原队列目标，不确定请求保留原File/键/版本/目标。JPEG/PNG是开发方向，16_384边长/25_000_000像素是技术防护，不代表生产文件批准或病毒扫描。

真实UI发现登记回读打开详情抽屉遮挡仍需补传的表单。本批改为表单打开时暂存登记ID、只刷新列表，关闭后才展示详情，上下文重置清空待ID；修复后正常上传/追加可达。36项包括授权下载PNG原始hash、先真实route.fetch提交再丢上传/追加响应并验证同File/key与追加key/body/version/target、两页真实CAS409比较重试、3张已存配额、跨登记A/B队列保护、策略重载、租户A/B/A与logout401。另有50秒探索；390px仿真表单366px、长名换行无水平溢出，截图仅在Git忽略的`.local`。

80次基础HTTP、52次恢复/撤权HTTP、25次运行中Worker重试HTTP和36项UI分别记录，不当作同一脚本或测试数；44项定向单元是113项的重复回归，不加为157项。旧52次脚本先停Worker，其RETRY_WAIT=NOT_RUN保留为单脚本历史边界，另25次证据补齐运行时重试。新专项只对新虚构job.next_at执行两次各1行CAS，不改lease/token/attempt/status；finally恢复marker/hold、三进程就绪与四会话，未改远程配置/V5/IAM/成员。

完整M5-5/M8-6、全部128合同TC未完成。正式state/priority/SLA仍null，扫描UNSCANNED、生产UNVERIFIED、保留null；真实手机拍照、企微送达、客户正式规则和许可仍待确认。

## 历史云端照片：PARTIAL

以下保留云端当时的版本及NOT_RUN，不能改写为在云端执行成功；本批本地V5、完整构建及真实组件结果以上节为准。

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

## 历史云端后续独立D04/D07纯功能

|功能|实际执行结果|边界与记录|
|---|---|---|
|配置筛选/建议差异|新增20项；当次全前端69项、类型和构建通过|[独立证据](evidence/cloud-configuration-comparison-verification.json)；无真实影响对象、HTTP/UI或发布验证|
|配置解析/不可变快照|Java17严格编译；15组250个纯断言通过|[云端证据](evidence/cloud-configuration-resolution-verification.json)；云端当时15个JUnit包装未执行，本批本地15项已通过；真实持久化/客户核验未实现，登记值未激活|
|工单详情operation回读|新增29项；当次全前端98项、类型和构建通过|[独立证据](evidence/cloud-report-operation-verification.json)；只GET现有操作，真实OIDC/HTTP/UI仍未执行|

98项包含此前69项，69项包含照片批次49项，不重复累加。纯核心的合成核验/发布事实不表示真实客户批准；上述切片不完成完整D04/D07或128项合同验收。

资产依赖纯结构校验随后独立通过：JDK17严格编译、12组14,987个断言，其中独立算法对照4096个四节点图与25,000边深链。实现与复现见[资产依赖校验](asset-dependency-validation.md)及[机器证据](evidence/cloud-asset-dependency-verification.json)。没有API/PG保存或真实抑制联调，TC-M3-9仍NOT_RUN；不与照片或前端测试计数合并。

资产导入已解析行的纯预检独立通过：JDK17严格编译、14组1,479个断言，50行三类错误准确阻断三行，500行计算12.67ms；[实现边界](asset-import-precheck.md)和[证据](evidence/cloud-asset-import-precheck-verification.json)明确没有.xlsx/CSV解析或实际入库。该计时不表示完整导入≤60秒；TC-M3-6、真实并发配额和重复导入均NOT_RUN。

历史云端[CI配置](ci-validation.md)通过本地结构/语法及隔离Web构建，当时Actions读取受HTTP传输/代理拒绝，三个job为NOT_OBSERVED；此历史不改写。后续eba5630及照片提交f56ad1b已取得各自真实成功回读，早期1db3976/d4375f7失败保留。CI不连接私有组件，不能算联调。配置解析portable脚本另复现原15组250个断言，不计新增测试。

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

## 当前本地复现入口与历史云端边界

Windows当前完整检查入口为`pwsh -File deploy/check.ps1 -Install`；独立迁移默认Check→显式ApplyPhotos及`-WithPhotoWorker`运行见[README](../README.md)。先停止本项目应用并备齐受控私有配置，测试会写项目数据，不能称只读。原target4测试保留基线作用，V5由独立CLI执行；历史云端NOT_RUN不代表本批本地未验证。

启动应用后执行 `npm run test:api`：捕获工具在已安装浏览器中完成真实Keycloak登录，将临时Cookie/CSRF只写入被Git忽略的私有会话文件，随后执行HTTP契约检查。重启验证的入口及执行顺序见README。云端或第二机器复现仍须通过受控私有配置连接同一指定测试环境；缺少连接资料不能据纯单元通过宣称已联调。

## 局限与历史记录

- 已验证的是上述测试切片。标准OIDC成功流程不等于已经实测全部伪造签名、密钥轮换、会话超时与身份灾备分支。
- PostgreSQL测试库和API的tenant+id约束不替代客户生产RLS/独立库及六层隔离验收；生产Q02/Q03/Q17与客户正式岗位仍待确认，production/integration不作为可用运行模式。
- Redis、RabbitMQ没有对应缓存/消息业务联调；本批MinIO照片链路已实际验证，历史云端未执行记录保持原边界。Zabbix、外部ITSM、堡垒机及相关资料缺失继续登记，真实监控未知值不冒充正常。本平台权威方向已明确，正式工单规则仍待落实。
- 报障耐久outbox仍CONFIG_PENDING，消息投递、其他业务消费者、审计外部受保护链头及至少3年保留治理未实现。本批真实运行的是照片PG Worker，不表示报障outbox已执行或送达。
- 第二机器/云端复现、客户H5、全浏览器、真实设备采样、容量/性能/灾备及128项合同逐项验收尚未完成。

首批local_fixture记录已归档至 [历史验证](history/2026-09-30-local-fixture-verification.md)、[历史QA计划](history/2026-09-30-local-fixture-qa-inventory.md)及 [历史摘要](history/2026-09-30-local-fixture-verification-summary.json)。其16项后端/33个HTTP等旧计数仅描述当时fixture阶段，不能合并为本轮真实组件结果。

D04诊断功能提交`c624ba1`的[独立CI回读](https://github.com/yangyour/zhonganzaixian/actions/runs/36861304649)已确认三个job全部success，见[精确提交安全摘要](evidence/github-c624ba1-verification.json)。此CI只验证源码、编译与隔离检查，不替代指定服务器的业务联调，也不表示完整D04或128项完成。

## 2026-10-02 Dot：完成请求后的只读刷新显示修复

基线 `f51fed151aa9e5bbb1fc0ab3b22cde02c140720b`，分支 `handoff/cloud-20261001-photos`。普通刷新在来源的身份、原文件摘要、单调版本和资源政策校验通过后，移除与当前 store/parse 操作 ID 或解析产物绑定不一致的旧显示操作。只修改呈现引用，历史请求、原参数、幂等键和映射收据均保留；不自动查询新 operation，也不触发 POST。

新增9项隔离回归：3种替代来源（新解析待处理、新解析完成、产物绑定变化）、2种仍匹配操作保留、4种非法回读（所有者、摘要、倒退版本、政策）不得修改原显示/历史。修复前3项失败，修复后定向22项通过；前端全量14文件181项、类型检查与构建通过；契约18项及生成一致性通过。定向结果包含于全量，不累加计数。

当前云工作区没有指定服务器私有配置，真实 API/UI/PG/MinIO 验证为 BLOCKED/NOT_RUN；没有启动替代服务或 fixture 应用。V1–V6与基线逐字节一致，未执行迁移。完整 M3-6 与128项合同验收仍未完成。证据见 [刷新回归记录](evidence/dot-import-refresh-20261002.json)。

## 2026-10-02 Dot：移动布局和重复表头选择的独立改进

移动端步骤条改为逐行显示，保留完整中文标题，不再用11px文本挤在五等分横排中；增加阶段区域名称。移动选择框和忽略列标签最小高度44px。新增17源列隔离输入用例：重复“设备名称”不自动选择，明确NAME=0且同时忽略15/16后才生成15目标请求；少忽略任一列、忽略已映射列均拒绝，原始源列及草稿顺序保留。

前端全量182项、类型及构建、契约18项和生成一致性通过。这里只验证代码和隔离输入逻辑；实际移动控件映射、最终回读、真实API及视觉验收仍NOT_RUN。独立Chromium布局检查在启动时因socket权限失败（已尝试获准提升执行），云浏览器拒绝file协议；没有截图或视觉PASS。没有用替代API或组件冒充指定环境验收。详情见[移动改进证据](evidence/dot-import-mobile-20261002.json)。

## 2026-10-02 Dot：合同15列空模板

新增[独立XLSX生成器](asset-import-template.md)，复用现有POI与领域列定义，不新增基础组件或HTTP接口。模板只有原序15列、无示例数据/自定字典；CLI拒绝覆盖。JDK17/Maven打包及解析器全量29项通过，其中新增4项；真实打包入口生成4573字节文件，默认ParserMain可读目录，未填数据明确拒绝EMPTY_DATA。一次初始断言误把shared-string lexical索引当文字，已按既有原始语义修正测试，解析器未修改。证据见[模板验证](evidence/dot-import-template-20261002.json)。现有API契约V0.8不变，未增加页面下载入口；真实指定组件/手机UI及完整M3-6仍未验收。

## 2026-10-02 私有配置到位与SSH信任策略

原工作区已核验接续81b2352的6个配置，目标主机固定键存在，配置本身不再缺失。当前网络直连ENETUNREACH、现有环境代理CONNECT超时，实际登录0次、远程命令0次；组件/API/UI/重启故障验证仍NOT_RUN。旧连接helper改为只接受固定主机键，5项隔离回归与Python编译通过，不改密钥文件。HTTP/API契约不变。见[实际检查记录](evidence/dot-private-connectivity-20261002.json)。

## 2026-10-02 D07合同固定校验核心

[客户动作纯校验](ticket-customer-action-rules.md)新增客户主体/报障人/启用决策人关系、跨租户404、撤权、未知关系拒绝、20码点说明与72小时精确边界检查。11项新隔离用例通过，完整Maven305项中实际执行217、跳过88，0失败/错误；前端182及契约18、生成一致性通过。定向用例包含于全量，不累加。见[验证摘要](evidence/dot-ticket-action-guards-20261002.json)。

该核心尚未接入真实工单状态机/PG/API，不宣称客户确认或重开已完成；API V0.8不变，Q07/Q09/Q27及SLA规则仍待落实，V1–V6与私有配置未改。用户确认暂无额外批准SSH入口，云端网络路径继续BLOCKED；真实组件/HTTP/UI未执行，不以本机组件或fixture替代。

## 2026-10-02 D08时间区间数学基础

[区间集合核心](time-interval-sets.md)实现并集、交集、差集、裁剪与精确累计时长。16项隔离JUnit（含一个500组独立逐秒集合对照）通过；完整Maven321项中实际执行233、跳过88，0失败/错误；契约18及生成一致性通过。定向/对照案例不另累加为全量测试。见[独立证据](evidence/dot-interval-sets-20261002.json)。

本核心没有可用率、分母、工作日历、SLA承诺或赔付策略，不把未知当可用；Q05/Q08/Q26/Q27仍未确认。API V0.8、私有配置及V1–V6不变；云端网络阻塞和真实联调NOT_RUN保留。前一D07提交cd0a531的CI36969314716成功，不能替代业务验收。

## 2026-10-02 D08显式工作时间推进

[工作窗口推进](explicit-working-time.md)只使用明确输入的窗口/覆盖/版本，跳过非工作段，保持纳秒精度；缺输入、覆盖不完整或工时不足返回UNRESOLVED且deadline=null。26项新增JUnit（20组参数化边界+6项缺值/版本等保护）通过；完整Maven347项中实际执行259、跳过88，0失败/错误，契约18及生成一致性通过。见[独立证据](evidence/dot-working-time-20261002.json)。

不预设客户工作日、不把P3次日变为24小时，不直接生成正式SLA承诺；Q08及来源/客户批准、真实仓储和时钟Worker尚未接入。API V0.8、V1–V6、私有配置和网络边界不变，真实组件联调NOT_RUN。前一区间提交7e3ce75的CI36969801922成功，与业务验收分开。

## 2026-10-02 M10-7容量估算纯核心

[容量数学模型](capacity-estimate-core.md)实现8输入/9输出，模型系数及版本必须显式提供，缺值返回UNRESOLVED/outputs=null。DECIMAL128计算、不用二进制浮点，池数用未舍入比值向上取整。10项新JUnit及完整Maven通过：357项中实际执行269、跳过88，0失败/错误；契约18和生成一致性通过。见[独立证据](evidence/dot-capacity-core-20261002.json)。

仅估算，不代表实测NVPS、HA、采购或合同性能；没有新增页面/API/组件。Q13/Q21/Q25/Q30、真实联调和完整TC仍待，V1–V6及私有配置不变。前一工作时间提交bf08452的CI36970495081成功，仍不等于正式日历或SLA验收。

## 2026-10-02 D04嵌套数值溢出校验

复现配置提案/历史草稿共用校验器只检查顶层数值的问题：JSON对象中的1e999被解析为Infinity，原校验通过，后续序列化会变成字符串，改变原值类型。现对任意嵌套对象/数组数值统一应用既有顶层有限double范围检查，非法值400；不补默认、不转换合法数值或嵌套null。

新增2项隔离回归（其中溢出用例覆盖对象、对象内数组及顶层数组）；修复前1项失败，修复后完整Maven359项中实际执行271、跳过88，0失败/错误；契约18和生成一致性通过。实际OpenAPI补充动态校验说明，结构版本V0.8不变。此处是输入验证，不证明真实HTTP/PG写入、客户批准或配置生效，真实组件联调NOT_RUN；V1–V6及私有配置未改。见[独立证据](evidence/dot-configuration-finite-20261002.json)。前一容量提交34cec3a的CI36971237098已回读三个job全部success。

## 2026-10-02 M8-10静音窗口纯核心

[静音判定](notification-quiet-hours.md)使用显式策略版本/时区/本地起止和Instant，支持跨午夜、[start,end)与DST重复/跳时；P1只绕过静音过滤，不绕过其他派发门禁。缺值UNRESOLVED，不猜客户时区或严重级映射。新增20项隔离JUnit通过；完整Maven379项中实际执行291、跳过88，0失败/错误；契约18及生成一致性通过。见[独立证据](evidence/dot-quiet-hours-20261002.json)。

没有页面、真实偏好存储、消息发送或供应商回执，不宣称M8-10完成。API V0.8、V1–V6与私有配置不变；网络仍阻塞，真实组件联调NOT_RUN。上一配置数值修复8af7dde的CI36972330429三个job已回读成功。

## 2026-10-02 D05解析产物结构边界

复现原ParsedArtifact构造器接受重复sheet/column/row标识、不存在的选中sheet、未声明列中的cell、表头/表头前数据、空PARSED结果和空版本的问题。现统一拒绝这些结构；SELECTION_REQUIRED不能夹带选中sheet/header。重复中文表头和稀疏有效单元格仍合法，不重排数据、不规范化日期、不执行预检或导入。

新增7项隔离测试，修复前6项失败；修复后全量Maven386项中实际执行298、跳过88，0失败/错误，现有解析器29项也通过。契约18及生成一致性通过；OpenAPI明确PARSED至少1行，跨字段唯一/关联规则写入binding checks。见[独立证据](evidence/dot-artifact-structure-20261002.json)。私有配置和V1–V6未改；真实子进程/组件/HTTP/UI链路仍待指定网络，不能把单元验证计为完整M3-6验收。前一静音提交32a9776的CI36973004629三个job全部成功。

## 2026-10-02 niko获准独立真实组件验证

用户新增允许niko部署测试组件和独立凭据后，实际启动PG17.11/Keycloak26.2.4/API，保留旧服务器与配置不变；新库冻结V1–V6单独4+1+1应用、只读check0。69真实PG检查通过，6合成身份真实OIDC/PKCE登录通过，D04内部审批44项HTTP/Ajv响应通过；8表正常重启前后指纹一致、重启后再次验证。上述范围内真实验证已执行，不能继续笼统写成所有真实集成都NOT_RUN。

根Maven明确排除6项MinIO：380总数=367执行通过+13历史fixture跳过，0失败错误；367包含69真实PG，不累加。4项私有配置守卫单测、Python编译、shell/JS语法、契约18及生成一致性通过。新脚本/独立证据路径禁止覆盖旧服务器报告，API进程不注入迁移账号。见[环境说明](niko-local-test.md)、[组件证据](evidence/niko-local-components-20261002.json)、[HTTP](evidence/niko-configuration-revision-http-20261002.json)、[正常重启](evidence/niko-graceful-restart-20261002.json)。

MinIO源码构建成功但启动前netlink EPERM，提升执行同样失败；用户随后指定OSS，适配/认证/对象写入尚未验证，SeaweedFS候选停止。存储整链、Worker断链耐久重试、浏览器UI、崩溃恢复、正式业务和128项验收仍待；局部阻塞不停止独立开发。V1–V6和原私有6文件未改。

## 2026-10-02 OSS原生适配核心

用户选定OSS后新增[原生SDK适配核心](oss-storage-adapter.md)：V4/TLS、固定北京端点、独立部署前缀、私有对象、单次禁止覆盖PUT、原始长度/SHA读回、冲突/未知失败明确区分；版本控制Enabled/Suspended会使OSS禁止覆盖头失效，因此前置检查失败时不写，不修改桶设置。17项隔离测试通过，根Maven403=315执行+88跳过，0失败错误；契约18及生成一致性通过。

本批尚未接入API/Worker工厂，没有使用提供的密钥认证、探测权限或写入云对象，不能标OSS联调通过；不产生预签名下载或改变平台授权grant。证据见[适配记录](evidence/dot-oss-adapter-20261002.json)。上一独立环境提交6872d14的CI36996358992三个job成功；其69真实PG/44HTTP只属于该批，不与本批累计。V1–V6及原私有6文件不变。

## 2026-10-02 OSS接线与不完整配置真实降级

API及照片/导入Worker统一选择OSS或既有MinIO；完全无全局存储设置才保留旧路径，显式关闭/部分配置/未知provider不回退。Worker仍保留显式启用、受限DB与解析器路径门禁；原生SDK视图可关闭且共享资源只释放一次，关闭后不继续请求。OSS凭据不传给解析子进程，运行凭据字段不得进入配置提案，SDK原始日志关闭。

新增16项JUnit；根隔离验证419=331执行+88跳过，0失败错误。随后在niko真实PG启用、明确排除6项MinIO的根验证413=400执行+13历史fixture跳过，0失败错误；400包含69真实PG，不累加。契约18及生成一致性通过，API V0.8响应结构不变，补充provider选择/禁密说明。

真实HTTP验证：不完整OSS时live200/ready503且存储未就绪，OIDC和配置读取200，fixture入口403（5检查）；恢复默认无OSS配置后45项D04/契约检查通过，含运行密钥字段拒绝不落库。见[接线证据](evidence/dot-oss-wiring-20261002.json)、[降级](evidence/niko-oss-unready-http-20261002.json)、[回归](evidence/niko-oss-wiring-regression-http-20261002.json)。这不证明OSS认证/IAM/写入成功；仍等待受控私有配置文件。旧MinIO双账号权限证据不迁移到OSS。V1–V6及原私有文件未改；前一c576ce3的CI36997962248三个job成功。

## 2026-10-02 OSS接线后的独立JDK入口修复

bf1abfe的CI37001705608中，后端完整编译/测试/打包与契约/Web两个job成功，但纯Java入口失败：WorkerRuntimeSettings新增了两份只依赖JDK的OSS配置类，脚本显式源码列表未同步，导致cannot find symbol。已在本地复现，不是OSS云连接或Maven测试失败。

`verify-photo-pure-jdk.sh`现加入OssStorageSettings与StorageProviderSelection源码，仍不引入OSS SDK、Maven、网络或凭据。独立Worker配置断言从9增至15，覆盖有效OSS、不完整配置不回退、关闭/未知provider/缺secret/非法前缀拒绝。四个纯JDK脚本均重新通过；断言计数不冒充JUnit或组件联调。原失败和修复记录见[CI入口证据](evidence/dot-oss-ci-fix-20261002.json)。本修复不改变应用、API、私有配置或迁移。

## 2026-10-02 OSS私有文件只读校验

新增严格JSON/简单dotenv私有配置读取器：64KiB、普通私有文件、重复/未知键拒绝、固定北京端点、显式桶/稳定前缀、运行凭据输入不回显；无source/插值，无落盘、激活、认证或网络操作。14项合成单元测试通过并加入CI。API V0.8结构、V1–V6及原6个私有文件不变；真实OSS认证/IAM/对象操作仍NOT_RUN。见[配置入口说明](oss-storage-adapter.md)和[独立证据](evidence/dot-oss-private-file-20261002.json)。

此前6a70403修复的CI37002701110三个job已全部回读成功，关闭上一纯JDK入口失败；不能将CI成功计为OSS云验收。

## 2026-10-02 niko客户报障真实HTTP与正常重启

在新PG17.11/Keycloak26.2.4/API上，客户报障54项真实HTTP/Ajv响应通过：持久202登记、同键重放/冲突、同资产同报障人去重且不覆盖、未知资产不擅自合并、不同报障人及跨租户404、内部角色不能代客户报障、CSRF/幂等/字段伪造/无效输入拒绝、筛选及actor/filter绑定游标。分页数据由本轮API创建11条虚构登记并以唯一前缀隔离，不依赖旧库已有条数；不使用fixture运行时。

首次运行停在旧脚本的照片错误预期：旧V4脚本要求422/Q28，当前冻结V5已存在，仓储对未知附件应返回404/OBJECT_NOT_FOUND。已按当前实现/契约纠正测试并完整重跑通过，不将初次失败改记为成功。原历史证据不覆盖；独立环境必须显式会话目录、环境/版本及新证据路径。API V0.8未改变。见[HTTP证据](evidence/niko-customer-report-http-20261002.json)。

随后通过已有run-stack拥有权清理/重启PG、Keycloak和API；重新OIDC登录，原报障与operation的2项前/后只读响应摘要完全相同，state/priority/SLA仍null，联系人UNVERIFIED、operation仍PENDING/CONFIG_PENDING。见[正常重启回读](evidence/niko-customer-report-restart-20261002.json)。这是正常退出后的持久性，不是崩溃恢复、Worker断链、OSS或正式D07状态流转验收。契约18项及生成一致性通过；V1–V6和原私有6文件未变。

页面验收另有明确阻塞：同实验执行空间Chromium因Unix socket EPERM在加载前终止；支持的云浏览器访问前端被ERR_BLOCKED_BY_CLIENT拒绝，未改变权限/代理/端口来绕过。0项浏览器验收通过，移动映射与视觉继续未验证，见[脱敏记录](evidence/niko-ui-blocker-20261002.json)。此前7f1e8cd的CI37004144354三个job均已成功。

## 2026-10-02 报障Unicode边界空白修复

复现服务端String.strip与浏览器trim不一致：NBSP、figure space、narrow NBSP或BOM组成的描述/联系人/显式title会绕过必填校验，边界空白电话也与前端结果不同。新增2项JUnit在修复前1失败/1错误，修复后通过；后端边界去除统一覆盖Unicode空白、space separators及BOM，内部空白/多行内容不改写，原始长度和控制字符/无效Unicode限制保留。前端行为不改，补2项回归。

重新运行真实PG启用的根Maven：415项=402执行通过（包含69真实PG）+13历史fixture跳过，6项MinIO明确排除；0失败/错误。计数来自本轮日志汇总，不把target中残留的6项旧MinIO XML当成本轮结果。Web184项、类型/构建、契约18项及生成一致性通过。API V0.8字段结构不变，补充实际输入边界说明。

重启到新构建并重新真实OIDC登录，报障HTTP/Ajv75项通过，其中新增18个空白拒绝和3个边界修整后持久登记/operation/详情回读；与上一54项有重叠，不累计为129个独立验收。照片、正式状态/SLA、OSS及UI仍待，未标DONE；V1–V6和原私有6文件未改。见[修复记录](evidence/dot-report-unicode-20261002.json)及[真实HTTP](evidence/niko-report-unicode-http-20261002.json)。前一842f87b的CI37005174219三个job成功。

## 2026-10-02 资产依赖V7独立草稿schema

新增V7图指针、不可变修订和修订边三表，跨租户资产/作者复合FK，自指和重复边约束；仅允许DRAFT/pending/monitoring_applied=false，提出的类型/抑制范围/enabled允许null。运行账号仅SELECT/INSERT及图version/updated_at列更新，无历史更新/删除/DDL。尚未接入事务防环仓储、API或页面，不能称M3-9完成。

在niko新库通过独立CLI先check0、显式apply1、重复apply0、再check0，结构/ACL核验成功；V1–V6逐字节保持，新V7执行后也单独冻结hash。新增显式apply模式只允许V1–V6有效历史之后；默认check只读，API/Worker不自动迁移。8项迁移策略单测和6项真实PG约束测试通过，约束测试事务全部回滚；本轮根422=409执行通过（含75真实PG）+13fixture跳过，6MinIO明确排除。Python19项通过。见[独立证据](evidence/niko-asset-dependency-schema-20261002.json)、[后续实施边界](asset-dependency-persistence-plan.md)。API仍V0.8、公开契约无新端点；监控/OSS/UI及完整合同验收未执行。

## 2026-10-02 资产依赖真实PG事务仓储

新增独立AssetDependencyRepository/Postgres实现，仅保存提出的人工草稿；同幂等事务内重新核验成员/资产权限、租户级锁、读取完整当前图、CAS、既有纯防环校验、新不可变修订/边及审计。跨租户/未知引用404先于版本/结构错误；重复边不覆盖原提案。读取使用只读一致快照，断线503，不用空图或fixture兜底。批次100/总边10000为显式技术防护，不是客户配额，超限拒绝而不截断。

9项新增真实PG检查通过，涵盖A→B→C后拒绝C→A、两个独立连接并发相反边一成功一409且重试拒绝环、幂等重放/冲突、历史不可变、下游失败导致图/修订/审计/重放整体回滚、角色/事务范围、引用与参数边界。首次1项失败来自测试直接比较Jackson LongNode/IntNode；改为真实JSON序列化/回读比较后完整重跑。根431=418执行通过（含84真实PG）+13fixture跳过，6MinIO排除；断线守卫定向2项通过。V1–V7未修改，API/页面尚未接线，监控发布未执行，M3-9非DONE。见[独立仓储证据](evidence/niko-asset-dependency-repository-20261002.json)。上一930d1db的CI37007282641三个job成功。

## 2026-10-02 资产依赖草稿API与只读就绪

新增GET/POST assets/{id}/dependencies，实际契约V0.9；读取完整当前租户图，路径资产必须授权可见，写入仅assets:write并保留CSRF/幂等/租户图CAS/防环/同事务审计。建议字段必须显式null或提出值；有效规则仍null、监控未应用。严格JSON字节/token/深度/重复键限制、批次及完整图技术上限见[API说明](asset-dependency-api.md)。就绪新增只读V7 schema/权限检查，失败503且不自动迁移，不影响其他独立API。

7项控制器单元+2项就绪回归通过；根440=427执行通过（含84真实PG）+13fixture跳过、6MinIO排除，0失败错误；Web184、类型/构建、契约20和生成一致性通过。旧契约测试的固定0.8断言首次失败，按新增端点更新至0.9后通过。PG审计断言改为全表受限计数，不依赖最近100条页面切片。

真实HTTP首轮46、最终含就绪47项分别通过，两轮重叠不累加；包含HTTP双并发相反边一200一409，重试拒环、原图/历史不被失败请求改写、审计同键不重复。见[最终证据](evidence/niko-asset-dependency-api-final-http-20261002.json)。V1–V7、原私有6文件未改，页面与监控发布尚未验收，M3-9非DONE。上一9c8e7cd的CI37008484429总run已回读success。
