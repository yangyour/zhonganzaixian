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

## 2026-10-02 资产依赖页面代码与实际客户端请求验证

资产详情新增独立依赖抽屉：按完整租户图版本读取、展示当前设备相关草稿、查询本租户设备后明确上游/下游和nullable建议元数据、追加草稿。未知有效规则始终null、不展示监控已应用。刷新只GET；未知写结果冻结原body/版本/幂等键，显式原请求重试。已获持久化回执但新图回读失败时保留回执并要求只读刷新，不重发POST。租户/权限变化取消旧请求并清空显示，过期回读/回执不恢复旧上下文；普通离开页面与浏览器刷新有未确认请求提醒。

新增16项状态/回读隔离测试，前端总200项、类型/构建通过；契约20及生成一致性通过。首次类型检查2项错误（AssetPage未声明source、默认UUID函数过窄推断）已修复后复验，不省略失败记录。实际前端TS模块另经esbuild打包，在同一niko命名空间连真实HTTP：4次只读刷新/上下文校验通过；另9次HTTP以服务器首个200返回后主动丢弃响应模拟客户端丢响应，原键/body重试2次POST仅新增1图版本/1匹配审计。原候选对象修改也不替换待重试payload。首次只读运行停在会话验证，重新真实OIDC登录后通过。

这些客户端模块验证不是浏览器DOM/控件/视觉验收，浏览器阻塞及OSS等依赖集中登记在[当前阻塞表](current-development-blockers.md)，只暂停相关步骤。V1–V7及后端本批未改，API仍V0.9；M3-9不标DONE。见[代码验证](evidence/dot-asset-dependency-ui-code-20261002.json)、[真实只读模块](evidence/niko-dependency-client-readback-20261002.json)、[实际原请求重试](evidence/niko-dependency-client-retry-20261002.json)。上一3d8826e的CI37014617573总run已回读success。

## 2026-10-02 标准15列模板授权下载

实际API升级V0.10：GET assets/import-template按当前assets:write权限返回固定4573 bytes空XLSX及SHA/schema/CONFIG_PENDING头；无数据行/宏/外部链接/业务默认字典，不依赖OSS、不自动上传或解析。API只读预生成资源并核验固定摘要，没有在持有凭据的API进程引入POI或解析外部文件。导入抽屉新增独立下载按钮，客户端完整核验后才交给浏览器；拒绝长短读/摘要变化/错误JSON/206及失效上下文，不替换原上传请求。

新增后端5项（4授权/完整性、1实际包内模板+真实解析器空数据拒绝）及前端7项，根445=432执行通过（含84真实PG）+13fixture跳过、6MinIO排除，0失败错误；Web207、类型/构建、契约21与生成一致性通过。首轮真实HTTP发现全局过滤器与控制器重复设置no-store，原bytes/hash正确；删除控制器的冗余头后重建重启，11项完整真实HTTP通过，使用实际前端字节校验模块。没有将初次失败改写为成功。

见[下载说明](asset-import-template.md)、[构建边界](evidence/dot-import-template-download-20261002.json)、[真实HTTP](evidence/niko-import-template-http-20261002.json)。浏览器保存/视觉、OSS及完整导入仍未验收；V1–V7和原私有6文件不变，M3-6非DONE。前一3e66b2d的CI37017197287总run已回读success。

## 2026-10-02 未识别成员角色默认拒绝

复现Capabilities对未知角色兜底授予assets:read、null角色导致异常；会话建立/投影和切租户未统一拒绝不支持角色。现未知/null/大小写或空格变体返回空能力集；会话路径先核验启用成员和受支持角色，OIDC在成功审计/平台session创建前拒绝，切换目标在幂等记录和会话修改前拒绝。已知padmin/msp/tadmin/viewer四类能力列表未改，也没有扩大角色权限。

新增5项隔离回归修复前4失败/1错误，修复后通过；根450=437执行通过（含84真实PG）+13fixture跳过、6MinIO排除，0失败错误；Web207、类型/构建、契约22及生成一致性通过。重新真实OIDC登录6个既有身份，25项session能力/资产租户范围/配置权限/模板权限/匿名拒绝HTTP回归通过。未知角色只在隔离守卫中模拟，未向真实数据库/身份源插入非法角色，不能将此描述为真实未知角色账号测试。

API V0.10字段形状不变，补充当前成员角色来源与默认拒绝元数据；V1–V7及原私有6文件未动，不替代Q09生产权限确认，M2-6非DONE。见[守卫记录](evidence/dot-unknown-role-guard-20261002.json)、[已知角色真实HTTP](evidence/niko-known-role-http-20261002.json)。上一33ffb2e的CI37020293486总run已回读success。

## 2026-10-02 实填工时间隔纯核心

按Q23允许的实际工时记录先实现ManualWorklog：4类合同工作类型、显式带offset的起止、结束不早于开始、毫秒精度、原始备注及物理经过时长。缺值不变0/4小时，明确相同起止才为实填0；不套用原型0–24小时限制，不执行计费取整/成本/SLA口径。

新增6项隔离测试通过，根456=443执行通过（含既有84真实PG回归）+13fixture跳过、6MinIO排除，0失败错误。既有PG计数不冒充工时存储证明；当前尚无工时V8/仓储/API/页面。API V0.10、V1–V7及私有配置不变；M5-21/M10-13非DONE，见[实施边界](manual-worklogs.md)、[独立证据](evidence/dot-manual-worklog-domain-20261002.json)。上一a5dc571的CI37022715272总run已回读success。

## 2026-10-02 V8实填工时不可变修订结构

独立新V8仅创建工时头记录/修订：固定owner和父工单、同租户FK、修订actor=owner、最新修订延迟FK、MANUAL_INTERVAL来源及4类型/起止边界。运行账号不获历史UPDATE/DELETE或owner/parent修改权；未加默认工时或成本字段，不更新工单/SLA状态。niko显式check0→apply1→repeat0→check0，结构/ACL通过，V1–V7及原私有文件未改，V8执行后另存冻结hash；API/Worker不迁移。

9项迁移策略、6项新增真实PG约束、20项Python守卫通过；根463=450执行通过（含90真实PG）+13fixture跳过，6MinIO排除。首轮测试INSERT多一个占位符造成2失败/1错误；修正测试语句后全量通过，不改变已执行SQL。约束测试事务均回滚，不冒充工时服务持久化成功；仓储/API/页面及成本/毛利仍待。见[工时说明](manual-worklogs.md)、[schema证据](evidence/niko-worklog-schema-20261002.json)。

## 2026-10-02 本人实填工时持久化

WorklogRepository及PostgresWorklogRepository已接入既有幂等事务，限定当前启用msp成员本人：父工单同租户、创建检查父版本、修改检查工时版本、固定owner/parent、不可变修订和同事务审计。本人列表/历史使用有界keyset；外人记录和错误父范围404，权限版本失效401，管理员没有自动代改权。物理经过时间不改变工单、SLA、默认工时或成本。

12项新增真实PG仓储回归通过，覆盖持久回读、49小时/显式0、两类重放、CAS、本人权限、其他owner隐藏、下游失败回滚、两个独立连接并发、分页及0001..9999毫秒边界。首次并发测试未获得预期409，定位为联表FOR UPDATE等待后旧修订快照与新头版本不匹配；改为先锁稳定头记录、再单独读取当前修订，完整回归通过。其他owner场景为显式标记的历史合成记录，未提升viewer角色或宣称viewer经API创建成功。

根475=462执行通过（含102真实PG）+13历史fixture跳过，6MinIO排除，0失败错误；断库503/无受保护事务拒写守卫同时通过。API仍V0.10，尚无公开工时端点/页面；真实OSS、浏览器及完整M5-21/M10-13验收不在本切片内。V1–V8和6个原私有配置均未修改。见[仓储证据](evidence/niko-worklog-repository-20261002.json)。上一95dca54的CI37026678180已回读success。

## 2026-10-02 本人工时API V0.11

新增同租户msp本人工时list/create/get/PATCH/history；仅该角色增加worklogs:read_own/write_own，padmin/reviewer/customer未获团队查看或代改。创建CAS父登记版本、修改CAS工时版本；每次重新授权，CSRF/幂等/不可变历史/审计，GET不写入，跨租户或错误父范围404。严格重复键/未知字段/有界32KiB请求与有界keyset查询。工时schema及列权限加入readiness，不自动迁移；liveness独立。

响应明确MANUAL_INTERVAL/SELF_REPORTED，elapsed_millis是实填起止物理差；默认分钟、人工成本、交通成本、毛利继续null/CONFIG_PENDING。无24小时上限、计费取整、默认4h、管理员代填或工单/SLA变更。

8项controller+1项readiness新增隔离测试、23项契约、Web207及类型/构建通过。最终根485=472执行通过（含102真实PG）+13fixture跳过，6MinIO排除；包含另外独立提交的解析器关闭竞态第9项测试。75项真实PG/OIDC HTTP/Ajv检查通过：49h/显式0、两类精确重放、409冲突、同租户本人权限、跨租户/错误父404、并发200/409、12条分页及父登记完全不变；另25项6个既有身份当前能力回归通过。首轮HTTP父登记误传asset_id:null导致400，修正测试为省略可选字段后全轮通过，未改报障契约。

公开接口已接线，页面仍待实施；M5-21/M10-13及全部128项仍非DONE。原服务器、OSS、浏览器阻塞保留，V1–V8和原私有配置不动。工时仓储提交ecdc95d的CI曾因既有解析器关闭竞态失败，已独立修复422f118并确认CI37030716992成功，详见[说明](parser-shutdown-race.md)。见[工时HTTP](evidence/niko-worklog-api-http-20261002.json)、[当前角色HTTP](evidence/niko-worklog-role-http-20261002.json)、[本地回归](evidence/dot-worklog-api-20261002.json)。

## 2026-10-02 本人工时页面与原请求重试

报障详情新增“我的实填工时”独立抽屉：15条keyset列表、本人记录及不可变历史、明确类型/含时区起止、原始备注、物理秒数预览、新增/修改。表单不填默认日期/类型/工时，成本和默认值继续待确认；修改绑定操作者明确选中的id/version，后续读取到新版不能悄悄替换原编辑基线。

抽屉置于详情destroy-on-close之外，收起或切换登记详情保留原目标与未确认请求；存在未确认写入时不切到另一目标、离页提示，租户/身份/权限变动清理敏感上下文并拒绝迟到响应。保存结果未确认保留原body/key/CAS，仅明确点击原请求重试；普通刷新只GET，不自动补写。成功回执与当前列表分开，后续回读失败保留回执，不伪装整个页面已刷新。重试确认成功清空表单，避免误将原表单再次新增。

18项新增前端输入/DTO/分页/历史/状态回归通过，全Web225、类型检查/构建、契约23及生成一致性通过。实际前端WorklogWorkspace状态模块与ApiClient打包后连接真实PG/OIDC，28次HTTP完成：故意丢弃创建及修改的两个成功200响应，明确同键同body重试；普通刷新没有写入；另一客户端修改后旧编辑得到409，用户重新读取明确选新版；最终修订1/2/3且仅3条对应写审计。5次受测写尝试包含两次重放和一次409；另一个真实客户端的修改单独在28次HTTP内，不混淆为5次都新增版本。

这是受控客户端响应丢失注入，不是物理断网，也不是浏览器DOM/控件操作。桌面/390px视觉验收仍受B03阻塞，完整M5-21/M10-13及128项非DONE。V1–V8、API V0.11、私有配置未改。API提交e18ffac的CI37032139921已回读success。见[客户端HTTP证据](evidence/niko-worklog-client-http-20261002.json)、[页面回归](evidence/dot-worklog-ui-20261002.json)。

## 2026-10-02 审计链区间计算与既存PG验证

新增只读AuditChainVerifier，不改变V1写入编码或历史。最多1000条/批，完整核对row/payload、顺序/缺失/重复及前驱摘要；部分区间带前一条本地记录，但externallyAnchored=false，不冒充外部可信锚或WORM。8项隔离测试及2项真实PG测试通过：两租户5162条既存记录分7个区间，在各自REPEATABLE READ快照一致；真实行内存副本改字段被拒，数据库原记录回读未变。

根495=482执行（含104真实PG）+13fixture跳过、6MinIO排除，0失败错误。原写入器、V1–V8、API V0.11和私有配置不变；公开区间接口、检索分页与页面待，M2-12/M7-5非DONE。见[边界](audit-integrity.md)、[证据](evidence/niko-audit-chain-core-20261002.json)。上一工时UI1512e2c的CI37033778518已回读success；DOM验收仍受B03阻塞。

## 2026-10-02 审计只读仓储与稳定分页

AuditReader/PostgresAuditReader在REPEATABLE READ内重新核验当前启用padmin成员与permission_version；仅会话当前租户，不推定平台级或其他租户权限。按sequence倒序最多100条，第一页固定snapshot_sequence，后续before_sequence只读取该上界以前；并发正常追加不挤入旧页。actor/action/result/resource_type/resource_id/trace_id均参数化精确匹配，发生时间为[开始,结束)；字段和精度有技术边界，非任意SQL查询。

每条列表记录核对真实行/JSON envelope与本地hash；损坏/不兼容或断库503，不返回伪造正常。区间验证最多1000条、明确EMPTY/VALID/INVALID，读取本地前驱并保留失败枚举；EMPTY的valid=null，未来区间404。只查询原V1表，不提供修改/删除/修复，也不把本库链声明为外部可信锚。

7项新增真实PG回归通过：12条精确筛选记录的10+2分页、并发追加后旧快照稳定、新首页可见第13条、全筛选/半开时间、SQL注入文本无效、角色/权限版本/租户范围、区间及无写证明、默认有界/未来区间拒绝、参数边界；合成设置记录通过既有audit writer追加，读方法本身不写。根502=489执行通过（含111真实PG）+13fixture跳过，6MinIO排除，0失败错误。断库列表/校验均503守卫通过。

公开API/页面仍待接线；不宣称M2-12/M7-5完成，V1–V8和私有配置不改。上一2a58fbc的CI37035240717已回读success。见[仓储证据](evidence/niko-audit-reader-20261002.json)。

## 2026-10-02 审计只读API V0.12

新增GET /audit/records与GET /audit/integrity，仅当前启用padmin的audit:read，不接受tenant/namespace参数。records支持8类精确/半开时间筛选和固定sequence上界keyset；列表单条hash校验不能当整链校验。integrity区间含首尾，最多1000条，省略结束取min(快照尾,开始+999)；EMPTY返回valid=null，INVALID是200的失败校验结果，未来区间404。whole_snapshot_chain_checked只表示本次快照从1到尾完整且VALID；externally_anchored始终false，无外部签名/WORM证明。

7项新增controller隔离测试、契约24、Web225及类型/构建通过；根509=496执行通过（含111真实PG）+13fixture跳过，6MinIO排除，0失败错误。61项真实PG/OIDC HTTP/Ajv检查通过：12条筛选记录10+2稳定分页、正常追加后新首页13条、全精确筛选/半开时间、注入文本不改变谓词、区间与默认1000条、成功GET不追加审计、各非管理员/匿名拒绝、非法/重复/伪造scope和未来区间拒绝、无修改/删除入口405、denied可追溯。

没有对真实表进行篡改；INVALID只在隔离与真实行内存副本覆盖，不能称为真实存储攻击测试。V1–V8、原私有配置、角色能力未改，原/audit兼容入口保留近期100条，不当完整检索结果。新页面仍待，M2-12/M7-5整体非DONE。上一4823b9a的CI37036201421已回读success。见[HTTP证据](evidence/niko-audit-api-http-20261002.json)、[本地回归](evidence/dot-audit-api-20261002.json)。

## 2026-10-02 审计检索与区间校验页面

AuditPage切换到实际有界API，新增操作者/动作/结果/对象/trace精确筛选、UTC半开时间、应用筛选与固定序号上界分页；未应用输入不混入下一页。记录摘要只展示既存hash，并明确选择区间，查看不自动发起校验。区间面板独立显示当次快照、范围、前后hash、失败枚举及是否覆盖该快照整链；最多1000条/批，下一批需明确点击，不能将多次独立快照累计冒充整链证明。

严格客户端校验租户/排序/唯一性/分页边界/EMPTY-null/INVALID/外部锚为false；失败或非法输入清除旧结果，查询与校验各自取消迟到响应，租户/身份/权限改变清空两区。无修改/删除/修复入口，不改变保留策略。

14项新增状态/DTO/输入回归通过，全Web239、契约24、类型/构建通过。首轮模板dialog回调参数隐式any导致类型检查失败，改为具名boolean处理函数后通过。实际auditWorkspace+ApiClient模块连接真实PG/OIDC执行8次GET：20条两页稳定回读、trace筛选、单条校验、先1000再明确下一1000且不累计、最后序号上界不变；没有业务写请求或读取追加审计。另上一API的61项HTTP包括58项契约响应检查和3项不支持写方法405检查，不将不存在的写端点称为schema通过。

API c7294a1的CI37037860376已回读success。当前是页面代码/状态与真实客户端HTTP证据，浏览器DOM/390px仍NOT_RUN_B03，M2-12/M7-5整体非DONE。V1–V8、API V0.12、角色和私有配置不改。见[前端回归](evidence/dot-audit-ui-20261002.json)、[实际客户端HTTP](evidence/niko-audit-client-http-20261002.json)。

## 2026-10-02 拥有的API突然中断与恢复

新增显式run-stack --verify-owned-api-crash模式，只针对本次启动且父PID/UID对应、JVM完整命令/项目jar及回环8080 LISTEN socket归属都核验的API进程。业务写先收到提交回执，再SIGKILL并等待exit137、确认连接拒绝；PG/Keycloak在该故障步骤不重启。随后重启同一API，重新验证监听归属和健康，再恢复OIDC会话；不扫描并批量停止其他项目进程。

最终故障测试通过：先7次HTTP提交合成报障及本人v1/v2工时；同一只读REPEATABLE READ快照对11张既有业务表计算排序行SHA-256/行数，API突然中断重启后、再次登录前逐表完全一致。恢复后8次HTTP读取父记录/工时/历史，原创建键仍重放原v1、修改键重放原v2，当前记录保持v2、历史[2,1]且对应审计仍2条。属于“已确认提交后API中断恢复”，不是在途事务杀进程、PG/WAL崩溃恢复或Worker/OSS故障。

首轮未通过：启动脚本运行期间发生修改，旧脚本退出出现MODE未绑定，先前已启动的本项目API残留；新子进程被kill后旧监听仍可达，所以未产生恢复成功证据。核验旧进程固定PID/start-ticks/UID/完整JVM命令和项目路径后仅正常停止该实例；归档首轮私有checkpoint不覆盖，补充runner函数一次解析与监听归属校验后另轮完整通过。首轮失败单独保留。[首轮证据](evidence/niko-api-crash-first-attempt-20261002.json)、[最终证据](evidence/niko-api-crash-recovery-20261002.json)。

新增9项无真实进程的守卫单测，合计29项niko私有配置/OSS配置/故障守卫通过；shell/Python/Node语法检查通过。结束后默认交互启动模式再次核验当前API监听并重新捕获既有OIDC会话。没有新账号、凭据、权限、迁移、端口/防火墙或原服务器操作。API V0.12和V1–V8不改。

上一审计UIebee0ab的CI37039467414初次backend在maven-resources-plugin的slf4j-api描述符解析阶段失败，未进入业务测试；Web与pure Java通过。仅重跑失败job，attempt2整体success，不改锁定依赖/校验或跳过测试以求通过。此处不推断具体网络错误码。

## 2026-10-02 手工资产草稿资料纯核心

新增ManualAssetMetadata：名称/既有6类类型/站点及明确备注，其他手工资料显式null；严格ISO日期和Unicode技术边界，保留原文。仅pristine manual DRAFT状态允许后续维护，任何监控采样、非UNKNOWN可用性或接入进展都阻断。管理IP不进入新输入，不执行CMDB覆盖/凭据/模板或监控变化；owner格式校验不是租户成员授权。

6项隔离测试通过，根515=502执行（含既有111真实PG）+13fixture跳过，6MinIO排除，0失败错误。还没有此功能的结构/仓储/API/页面，既有PG回归不算新资料持久化证据；M3-5非DONE。V1–V8/API V0.12不改。见[边界](manual-asset-metadata.md)、[证据](evidence/dot-manual-asset-metadata-20261002.json)。上一API故障验证677d16a的CI37042152562已回读success。

## 2026-10-02 V9手工资产资料不可变修订结构

V9新增mop_asset_manual_revision/details，当前指针引用不可变修订；actor/owner/site/asset均有同租户复合FK，修订可链接更早资料版本。before_snapshot中的资产标识/旧版本/IP/手工草稿状态受约束，首次未知metadata须为null；后续仓储仍必须锁定真实资产并构建真实前后快照，schema约束不能替代实际成员授权或来源核验。

可选资料/日期无默认值，SELF_REPORTED/monitoring_applied=false/cmdb_applied=false固定；运行身份只获资产name/type/site_id/version/updated_at/updated_by六列UPDATE，不能改IP、source、tenant或监控状态；历史资料没有UPDATE/DELETE权限。新schema只在niko显式check0→apply1→repeat0→check0，旧worklogs显式apply再次为0。V1–V8字节未改，V9应用后另存冻结hash；API/Worker不迁移，后续结构需新版本。

10项迁移策略、8项新增真实PG结构/ACL回归、30项niko守卫通过；schema测试事务全部回滚，不冒充资料服务持久化。根524=511执行（含119真实PG）+13fixture跳过，6MinIO排除，0失败错误。仓储/API/页面未接线，M3-5非DONE；API仍V0.12。见[结构证据](evidence/niko-asset-metadata-schema-20261002.json)。上一c6f2f25的CI37043222178已回读success。

## 2026-10-02 手工资产资料授权仓储

ManualAssetMetadataRepository/PostgresManualAssetMetadataRepository接入既有幂等事务：当前启用msp/padmin成员与权限版本重新核验，锁定本租户资产，再检查站点/负责人归属和pristine manual DRAFT条件。CAS更新名称/类型/站点及版本，同时写不可变资料、真实前快照、当前指针、审计和重放结果。管理IP、source、创建时间、生命周期及监控字段保留；客户/tadmin维护范围待Q09，不自动授予权限。

读取当前资料/按版本分页历史为只读快照；无资料明确metadata=null，历史不造第一版。指针与最大修订不一致或持久格式非法返回503；当前核心字段与上次资料不同显示drift阻塞，不静默覆盖。改名冲突409、旧版本409、跨租户资产/站点/负责人404；外部/test_seed来源422，失败整笔回滚。

新增12项真实PG回归通过：实际前后值/日期/原文、连续历史、旧键回原收据不回退现值、改名与CAS、角色/引用范围、外部来源门禁、下游回滚、两独立连接并发一胜一409、协议日期边界以及站点最小权限。首轮11项中4失败/6错误，原因是显式SELECT FOR KEY SHARE需要站点UPDATE权；运行身份刻意只有SELECT。修正为普通授权引用查询，并由实际写入的复合FK取得引用锁，未扩大站点权限；新增42501回归确认这一边界，最终全量通过。V9未改。

根536=523执行（含131真实PG）+13fixture跳过，6MinIO排除，0失败错误；断库503和无受保护事务拒写同时通过。公开资料API/页面仍未接线，M3-5非DONE。见[仓储证据](evidence/niko-manual-asset-metadata-repository-20261002.json)。上一V9 dcbca1f的CI37046265831已回读success。


### 2026-10-02 手工资产草稿资料API

实际API升级V0.13，新增内部msp/padmin的资料当前/修订/PATCH及schema/ACL就绪检查；严格全字段显式null、CAS/幂等/CSRF/审计，IP/source/监控不改，客户Q09权限未放开。8控制器+1就绪新增，根545=532执行（含131真实PG）+13fixture跳过，6MinIO排除；70项真实HTTP响应契约全部通过，契约25、Web239及类型/构建通过。历史真实before、旧键旧收据不回退现值、并发一200一409和3条成功维护审计已验证。页面/引用选择器及最终浏览器验收待，不标M3-5 DONE。V1–V9未改，无OSS操作。详见[资料说明](manual-asset-metadata.md)和[API证据](evidence/niko-asset-metadata-api-20261002.json)。


### 2026-10-02 手工资产资料只读引用选择器接口

API V0.13.1新增同租户站点/活跃负责人查询；内部授权和资产404隔离、每类100有界/独立truncated、参数化字面子串、null默认负责人及写时重验。新增2控制器+2真实PG，根549=536执行含133PG+13skip，6MinIO排除；26契约及28真实HTTP响应校验通过。未改迁移、权限、账户或监控；大目录边界与浏览器选择交互不冒充已实测。页面继续开发，M3-5仍非DONE。见[说明](manual-asset-metadata.md)和[引用证据](evidence/niko-asset-reference-http-20261002.json)。


### 2026-10-02 资料维护页面与实际客户端

资料页接入当前/历史/站点与负责人选项、明确编辑版本、原请求不确定重试及独立成功回执。新20项Web单测，总259项及类型/构建、26契约通过；实际前端模块/ApiClient接真实API22次HTTP，5次写尝试含2原键重放/1CAS409，另1独立客户端修改，3修订和3成功审计、IP/监控不变。首次TS闭包unknown错误已修正并完整重跑。窄屏样式仅代码完成，浏览器DOM/390px仍B03未执行；M3-5/Q09/IP/CMDB整体非DONE。见[资料](manual-asset-metadata.md)及[客户端证据](evidence/niko-asset-metadata-client-http-20261002.json)。


### 2026-10-02 资料字段筛选核心/仓储

新增14维护字段显式AND查询及可空字段unknown_fields；固定列枚举/参数化字面子串，严格日期不推断维保状态。真实PG只读快照、当前内部成员/租户manual来源、至多101有界created_at/id keyset；跨页非冻结快照。6核心+3真实PG新增，根558=545执行含136PG+13skip/6MinIO排除全部通过。API和页面查询待接线，未改变主台账12列或客户权限，M3-5非DONE。见[资料说明](manual-asset-metadata.md)、[仓储证据](evidence/niko-asset-metadata-filter-repository-20261002.json)。


### 2026-10-02 内部资料筛选API V0.14

新增GET /assets/manual-metadata附加视图，严格14字段/显式未知AND、只读当前请求快照、成对创建时间/id分页和4096-byte编码查询限额。4控制器新增，根562=549执行含136PG+13skip/6MinIO排除，27契约/259Web/构建及63真实HTTP响应全部通过；12资产10+2分页、每字段/联合查询、编辑后筛选变动和跨租户/角色边界已验。页面筛选控件待，DOM/客户权限/主列版式Q31及全M3-5非DONE。见[资料](manual-asset-metadata.md)和[筛选API证据](evidence/niko-asset-query-http-20261002.json)。


### 2026-10-02 资料筛选附加页面

14字段明确不限/值/未知AND筛选、实际条件回显、15条配对cursor分页、同租户手工资料展开和进入维护历史面板已接线。12新模块测试，总271Web/类型/构建及27契约通过；实际查询模块/ApiClient29HTTP含8GET查询，16虚构资产/2明确资料写为准备，15+1无重复、变更后重读、显式未知/字面匹配及不写审计已验。主台账12列未改，查询不是冻结导出；DOM/390px仍B03，Q09/Q31与完整M3-5未DONE。见[资料](manual-asset-metadata.md)和[查询客户端](evidence/niko-asset-query-client-http-20261002.json)。


### 2026-10-02 D07原因/解决说明文字校验修复

先补3回归重现2失败：BOM-only原因及非文本控制字符可穿过既有纯核心。修正BOM边界空白和ISO控制符拒绝（保留LF/CR/TAB），不改变原文或业务状态。14核心/根565=552执行含136既有PG+13skip/6MinIO排除通过。没有关闭/重开真实状态或接口接线证据，API V0.14不变，完整D07仍待。见[说明](ticket-customer-action-rules.md)、[证据](evidence/niko-ticket-text-guards-20261002.json)。
