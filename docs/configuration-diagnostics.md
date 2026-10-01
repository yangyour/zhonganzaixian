# 当前租户配置有效性诊断

更新：2026-10-01。范围为D04/SET-D16的一项独立切片：从指定PostgreSQL一致读取当前授权租户的配置登记和近期内部提案元数据，解释当前生效读取的阻塞。第三轮完整检查、50项真实HTTP记录检查、26项真实页面检查和修复后最终154项前端/类型/构建均已通过；完成的是这个诊断读取切片，完整D04仍保留正式来源与客户规则门禁。

本接口返回 `status=CONFIG_PENDING`、`effective_value=null`。当前没有运行定义、发布和客户核验适配器；内部 `APPROVED` 仍是提案流程状态。它不激活185项原登记或草稿，不生成客户结论，不持久化业务创建时的配置快照，也不宣称D04或128项合同验收完成。相关边界见[内部修订](configuration-revisions.md)、[解析/快照纯核心](configuration-resolution.md)及[实施状态](implementation-status.md)。

## 接口与授权

`GET /api/v1/settings/effective?key=<已登记配置键>`

只接受一个 `key`。键格式为 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`；不修剪、大小写归并或猜测键名。缺少、重复或额外查询字段返回400；客户端不能传入 `tenant_id`、主体、对象作用域、覆盖次序或核验事实。GET没有发布或另一条写业务路由。

|结果|含义|
|---|---|
|200|当前授权范围的诊断元数据已读取并记录成功审计；配置状态仍CONFIG_PENDING、生效值仍null|
|400 VALIDATION_ERROR|查询字段、数量或键格式不符合约定|
|401 SESSION_EXPIRED|会话、成员关系或期望授权版本已失效|
|403 ACTION_FORBIDDEN|当前数据库岗位没有配置读取能力|
|404 OBJECT_NOT_FOUND|获授权读取范围内没有该配置登记|
|503 UPSTREAM_UNAVAILABLE|指定数据库、来源形状、请求范围绑定或成功审计不可用；不返回替代诊断|

主体和当前租户来自已有真实会话。Controller检查当前membership及 `configurations:read`；本测试工程该能力仅padmin具备，读取器也明确复核padmin。浏览器声明的岗位或权限不能升级当前数据库权限。此技术读取能力不代表Q09的客户确认、审批或发布资格已确定。

成功仅追加 `configuration.effective_diagnostic.read` 审计，原因固定为 `CONFIG_PENDING_METADATA_ONLY_NOT_EFFECTIVE`，不保存原值或资料正文。拒绝沿用现有 `ApiExceptionHandler` 的DENIED审计路径。成功审计失败时接口返回503；不会把一次诊断读取写成已生效或已发布。

## DTO与来源分类

实际接口定义在 `contracts/local-api.openapi.json` V0.7，生成源为 `tools/build-api-contract.mjs`。所有返回字段均由 `ConfigurationDiagnostic.dto()` 白名单投影。

|字段|内容与空值含义|
|---|---|
|key / status / effective_value|请求键、固定CONFIG_PENDING、显式null；null不转换成0、false或默认值|
|context|服务端当前 `tenant_id` 和 `environment=remote_test`；租户上下文是授权维度，不自动定义配置覆盖作用域|
|observed_at|同一数据库事务的UTC读取时间；不伪装为发布或生效时间|
|registry|DESIGN_REGISTRY、记录行版本、原type/unit/scope/source/decision_status/classification标签及原Q编号；标签只按原文展示|
|recent_proposals|本租户最近最多5项ID、流程记录版本、workflow_status、INTERNAL_REVISION、UNVERIFIED、REFERENCE_ONLY及引用数量；`truncated=true`必须恰好返回5项|
|runtime_definition|availability=NOT_IMPLEMENTED；schema_version、allowed_layers、precedence、resolved_scope全为null|
|publication|availability=NOT_IMPLEMENTED；记录引用、修订ID/版本、生效时间及授权/生产许可核验全为null|
|customer_verification|availability=NOT_IMPLEMENTED；是否必须核验、decision_ref、资格和真实性核验全为null|
|reasons|原因代码、source_kind、可确定的源ID/记录版本；不可用来源的ID/版本保持null|
|question_ids|与registry原Q编号一致；未提供保持null，明确空数组只表示登记未列关联Q编号|

登记行版本来自 `mop_configuration.version`，可以随旧草稿保存递增；它不是运行定义版本或生效版本。提案版本来自内部流程记录，也不等同于已发布配置版本。近期顺序只供展示，不选择“最新批准版本”作为生效输入。

|source_kind|事实边界|
|---|---|
|DESIGN_REGISTRY|已有设计登记与其元数据；没有运行发布事实|
|INTERNAL_REVISION|本租户内部DRAFT/IN_REVIEW/APPROVED/REJECTED/WITHDRAWN流程记录；资料仅为未核验引用|
|RUNTIME_DEFINITION|当前没有接入声明允许覆盖层、优先次序和运行校验定义的来源|
|RUNTIME_PUBLICATION|当前没有接入授权、版本绑定及有效时间的发布来源|
|CUSTOMER_VERIFICATION|当前没有接入客户资料真实性及确认资格核验来源；本项是否必须核验也保留未知|

原因代码为 `DESIGN_REGISTRY_NOT_RUNTIME_CONFIG`、`RUNTIME_DEFINITION_SOURCE_UNAVAILABLE`、`PUBLICATION_SOURCE_UNAVAILABLE`、`CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE`、`INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION`、`PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE`、`REGISTRY_QUESTIONS_UNAVAILABLE`。客户核验来源不可用是能力诊断，不能据此补填每个键的客户核验要求或Q编号。

缺失的定义/发布/客户来源使用NOT_IMPLEMENTED和null，不用空记录集合冒充已完成来源查询。登记标签不转换成 `ConfigurationResolver.Definition.precedence`；真实运行定义和核验来源具备后，才能向已有纯核心提供明确事实。

## 一致读取与隐私

独立读取器使用一个新连接，按只读、REPEATABLE READ、关闭自动提交的顺序建立事务。在首个实际SELECT的快照内核对 `mop_test`、当前及会话运行身份 `mop_test_runtime`、active user/membership、期望permission_version和当前padmin读取权限；登记和提案查询复用同一连接与快照。

该授权快照是此次读取的线性化点。随后提交的撤权或提案变化不混入当前响应，下一请求重新核验并读取新的事实。读取器不取得membership的FOR SHARE锁，也不发布连接到旧仓储的写事务ThreadLocal；失败回滚，没有内存/fixture或空诊断回退。

SQL只投影登记标签、Q编号、提案ID/版本/流程状态和证据数组长度，不将registry原值、draft_json、proposed_value、reason/review_comment或资料正文读入返回对象。标签采用JSON字符串专用CASE；root、标签、Q列表及证据引用形状错误返回503，不能把JSON对象或数组正文转成标签。未知标签、Q或引用数量保持null，不补造默认信息。

`document-ref`、SHA256和内部审核记录不证明客户资料真实存在、签署或确认人有资格。接口不下载/打开被引用资料，输出也不包含引用正文、标题或证据明细。现有登记、提案及审计历史仍保留，读取器不修改它们。

## 前端与现有写表单

配置页“有效性诊断”入口沿用padmin读取权限。诊断面板只GET；字段白名单和类型检查要求同key/tenant、CONFIG_PENDING/null、各不可用来源为NOT_IMPLEMENTED/null，Q顶层与登记一致，原因的源ID/版本对应当前返回的登记或近期提案。非字符串标签、数组/对象workflow状态、正文额外字段或假生效数据会被拒绝。

关闭、切换配置键/租户/主体/岗位/授权版本或退出时取消并清空诊断；迟到响应不能恢复旧上下文的内容。失败只显示错误和重新查询，不显示旧诊断作为新成功结果。页面原文明确近期提案不是生效选择、APPROVED仍未发布、允许层级和客户真实性/资格未知。

新入口先检查编辑/发布表单、保存/检查中的请求，以及修订创建/审批表单和不确定原attempt；需要回原表单处理时保留输入、原请求和版本。只读面板切换在nextTick之后再次核验上下文和写入保护，不自动关闭仍需处理的写表单。

## 运行与共享组件边界

Controller和装配仅注册于 `remote_test`，使用既有指定PostgreSQL与Keycloak、项目数据库/schema、运行身份和当前会话。不注册fixture适配器、不执行启动DDL、不修改V1–V5、不增建数据库/缓存/队列/监控，也不拓宽照片MinIO桶、purpose或IAM权限。

API正常开发仍通过既有loopback SSH隧道连接指定组件。组件版本及照片/CI历史证据继续见[组件清单](environment/component-inventory.md)和[验证记录](verification.md)。这条诊断读取路径不消费Redis、RabbitMQ或监控数据；已有组件可用不代表缺失的发布与客户核验来源已实现。私有配置、Cookie和CSRF仅从被Git忽略的配置/会话引用读取，公开记录不含秘密。

## 验证状态与复现

当前记录严格区分独立隔离检查、完整检查和实际组件/页面验收。照片提交f56ad1b、SDK及CI已有历史结果不被本切片覆盖或重复计数。

|检查|当前状态|边界|
|---|---|---|
|独立javac17严格编译及JDBC隔离|PASS：13项，0失败/跳过|只编译新模型/端口/读取器和定向测试，使用隔离JDBC双；没有连接PostgreSQL或执行迁移|
|API Controller/MVC安全|PASS：最终完整检查回读8+6项通过|Controller与MVC安全测试使用隔离依赖，不作为OIDC证明；包含于全量123项纯/隔离|
|前端定向与类型|PASS：初始diagnostic34+comparison20/revision5共59；安全metadata修复后66项定向通过|初始59与最终66存在包含关系，不重复累计；最终全量154、类型及构建通过|
|实际契约V0.7|PASS：10项契约检查|Schema检查不代替真实接口/组件验证|
|指定PG诊断集成|PASS：最终完整检查回读8项通过|只新增MOP-FAKE租户/主体/登记/提案，撤权仅自己的虚构actor并CAS恢复；包含于全量51项PG，旧43项为回归|
|第三轮完整检查|PASS：deploy/check.ps1退出0；29套/190报告项/13fixture跳过，177实际执行全部通过|123纯/隔离、51指定PG、3指定MinIO；[安全属性摘要](evidence/local-configuration-diagnostic-build.json)仅读现存XML根属性，不解析properties、用例正文或日志|
|全量前端/类型/构建基线|PASS：初始11文件/147项、vue-tsc/Vite build；1665 modules/8.81s，无warning|147=既有113+新增34；main464.09kB、Configurations64.51kB、Tickets52.05kB、共享ticket9.68kB；保留初始完整check版本|
|UI修复后最终前端|PASS：12文件154项、typecheck/build退出0；10.26s，无warning|154=前述147+新增metadata7；main464.09kB、Configurations66.27kB、Tickets52.05kB；66项定向包含于全量|
|真实OIDC/HTTP、数据库转发故障/恢复|PASS：6个真实OIDC身份，50项记录检查全部通过|49项匹配对应V0.7 JSON Schema，另1项直接Keycloak well-known检查；[真实HTTP证据](evidence/local-configuration-diagnostic-http.json)|
|1440px/390px诊断页面、旧写表单保护|PASS：26项检查，0未处理pageerror，Root逐图复核|真实Edge154.0.4258.48、持续Playwright无头、1440×900/390×844仿真；[UI证据](evidence/local-configuration-diagnostic-ui.json)，保留negative fixture|

第三轮 `deploy/check.ps1` 已由主任务实际执行退出0。本说明只审核29份现存Surefire XML的name/tests/failures/errors/skipped/time根属性并写公开摘要，没有再运行测试。123纯/隔离包含新增JDBC13与API14以及旧测试，51PG包含新诊断8与既有43，3MinIO为回归；13历史fixture只计跳过。初始147项前端包含旧113和新34，最终154包含前述147和新增metadata7；59/66项定向回归均包含于相应全量，不另加。HTTP50、UI26、JVM177及前端154各按自身统计单位记录，不合并成合同验收数。

前两轮检查暴露的测试复现问题保留：新增独立MOP-FAKE账号使两套旧测试的全局“首个同角色账号”选择不稳定，主任务改为选择目标租户内active且岗位匹配的测试主体，没有修改产品成员或185项原登记来促成通过。第二轮新增MVC测试嵌套 `@Configuration` 被正常MopApplication扫描，与旧文件测试的repository同名Bean冲突，旧ProfilePolicy5项中的3项失败。最终使用 `@TestConfiguration` 和专用隔离test profile，原产品profile守卫和断言保持；先完成MVC6+ProfilePolicy5定向11项，再第三轮完整检查通过。失败记录是测试隔离和复现修正，不改写照片提交的既有历史证据，也不代表产品规则放宽。

真实HTTP通过正常配置修订接口建立本run的新MOP-FAKE提案：租户A7项、B1项；真实APPROVED仍显示CONFIG_PENDING/null，A返回精确近期5项和truncated，B只见本租户记录，登记/草稿/提案/审核/资料正文标记不出现在诊断。辅助SQL在服务器内比较此前所有registry/draft的count与SHA256，公开摘要只记录未变化，不返回正文或完整摘要值。仅关闭本项目PG转发时，诊断与ready实际503、live200、Keycloak well-known200；恢复后同context/key回读200且未生效。finally移除本run标记、恢复ready/photos.ready、padmin租户A和6份会话；没有DDL、成员或原值变更。

真实页面验证使用正常控件及实际后端响应，覆盖来源缺口/pending/null、APPROVED不生效、近期5条/截断、租户A/B/A、旧GET延迟后关闭再打开新key、GET网络错误与重试、只读面板往返及编辑modal保护。旧提案POST先真实提交再丢响应，原表单以同一请求重试并回读同一server ID；发布实际422，诊断值仍null。异常Q记录保留并可查询明确503，缺失Q保持unknown，viewer没有诊断入口。

最初UI发现旧列表对非数组Q直接join导致空表；现已补安全缺失/错误展示及非文本metadata标签正文投影防护，不删除或修改这些negative rows求通过。Root逐张检查1440×900和390×844截图：长key换行、行标识/入口和末项footer可读可达，document没有水平溢出，近期表格自身水平滚动。混合筛选/表单/发布/移动表格探索约141秒，含截图。延迟gate辅助等待曾超时并重启QA kernel，最终持续会话重跑检查并关闭浏览器；没有据超时推断业务成功，不影响服务器或构建。截图只保存在Git忽略的 `.local/qa-configuration-diagnostics`，手机仿真不作为物理设备或全浏览器验收。

完整检查与本地启动入口沿用README，条件齐备后由主任务统一执行。HTTP复现脚本为：

```powershell
node tests/configuration-diagnostic-http.mjs
```

真实OIDC会话引用为 `deploy/private/qa-diagnostic-{padmin,reviewer,viewerA,msp,tadmin,viewerB}.json`；仅记录文件引用，不复制或展示内容。脚本私有运行状态位于 `.local/configuration-diagnostic-http-request.json` 和 `.local/configuration-diagnostic-http-fixture.json`。辅助脚本 `tools/configuration-diagnostic-http-fixture.py` 仅插入本run唯一的MOP-FAKE登记，不能覆盖185项原登记或草稿。

故障验证仅控制本项目 `.local/postgres-forward-disabled` 标记，按run UUID/项目归属核验并finally恢复，不停止或重配远程数据库。公开[HTTP摘要](evidence/local-configuration-diagnostic-http.json)记录50项实际HTTP检查，其中49项校验对应V0.7 Schema与预期状态、1项校验Keycloak well-known；辅助SQL的count/boolean不计HTTP，finally真实会话回读按实际HTTP计入。它不枚举浏览器全部SSO网络请求。[页面摘要](evidence/local-configuration-diagnostic-ui.json)另记26项正常控件/实际响应检查和视觉复核；最后前端154项与初始147项版本分别保留于[构建摘要](evidence/local-configuration-diagnostic-build.json)。

本切片仍保留客户正式资格/真实性、每键允许作用域与覆盖次序、正式发布/外部回读、生效消费、业务创建快照及完整D04验收边界，不把内部审核或诊断200标为DONE。
