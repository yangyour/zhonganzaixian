# MOP 实施状态

更新日期：2026-10-01。云端最后交接`1db3976`已接回本地，云端停止业务写入。全部128项继续逐功能验证、留证、提交推送，复用指定服务器组件，禁止fixture或本机新服务替代。本批照片V5/完整构建/PG/MinIO、基础HTTP80次、恢复/撤权52次、运行中重试25次及UI36项分别通过；完整合同仍DOING/PARTIAL。见AGENTS.md、[本地交接](local-development-handoff.md)及[本地验证](local-runtime-verification.md)。

|任务|本轮已实现|仍待完成|
|---|---|---|
|D01 工程/环境|完整构建/check及最终Web通过；V5独立Apply一次、再次0DDL/ACL；显式Worker/拥有者停止/依赖就绪；eba5630及照片提交f56ad1b各自3个CI job独立回读成功；真实重启、项目存储断链恢复及运行中退避重试|第二环境复现及生产部署；CI不替代组件联调|
|D02 页面|照片36项真实Edge持久无头UI通过，1440×900/390×844仿真正常控件、0 pageerror；跨登记队列/原请求保护、下载hash、并发CAS与三张配额；修复表单被详情遮挡；113项全量+修后44项重复回归及最终类型/构建通过|物理相机、企微嵌入身份、其余页面/客户H5、全浏览器与性能验收|
|D03 身份隔离|真实OIDC授权码+PKCE；验证签发者+sub不可变映射；每次请求成员复核；PG查询tenant+id；事务内权限版本复核；会话岗位与capabilities同取当前membership并完成纯测试/真实降级验证|客户正式岗位/身份源、生产RLS或独立库及六层隔离专项(Q02/Q09/Q17)|
|D04 配置|185项原登记保持原value/null；PG独立草稿与CAS、内部审批及发布门禁；筛选/差异及解析/快照纯核心；当前租户只读PG/API诊断新增13JDBC+14API+8PG、V0.7、6身份50HTTP及26真实UI通过；安全metadata修复后154前端/类型/构建通过|运行定义、有效值解析/业务快照的真实仓储及发布、客户真实性/资格/正式确认、作用域/覆盖次序和实际影响对象仍待；APPROVED不生效，完整D04未完成|
|D05 资产|双租户36条明确test_seed、manual持久草稿、校验/事务幂等与接入门禁；图依赖和已解析行纯预检；2026-10-02原CSV/XLSX来源/解析/15列映射及V0.8契约，V6结构/ACL、指定PG新增18、MinIO新增3、真实Worker/HTTP两批通过；最新前端172/契约18通过|完整移动映射/视觉、COMPLETE后旧操作显示及整链故障回归待Dot接续；标准模板、真实预检/最终入库/500行≤60秒/并发配额/去重与回执未完成；依赖PG保存/20下联抑制、Q20/Q31、五步纳管和真实采样仍待|
|D07 工单|4个身份80次基础OIDC HTTP/V0.6 Ajv、36项UI通过；READY/CAS与去重追加；真实两页冲突比较重试、同原请求补传；正式状态/优先级/SLA仍null|正式状态图、资格、接单/转派/解决/确认/重开/工时/SLA/通知未完成；未知设备/共享去重待定|
|共享底座|持久幂等、审计/hash链与24项照片PG；52次恢复/撤权专项、25次运行中Worker RETRY_WAIT及自然退避完成原任务/hash独立通过，finally恢复|RabbitMQ/其他outbox未消费；保留、外部链头、导出/恢复治理|
|图片存储组件与应用|V5冻结；JUnit/PG/MinIO、基础80/恢复52/运行中重试25次HTTP及36 UI分别通过；耐久原图、不可覆写对象、grant/CAS、上传人/绑定授权；V0.6|指定环境测试切片通过；物理相机/企微、扫描/隔离、生产许可、Q10留存仍待；不标M5-5/M8-6整项DONE|
|D06/D08–D14|保留接口与待确认业务门禁；尚未开始完整模块|事件/SLA/通知/备份/远程/报告/治理/联调交付|

历史及本批结果分别记录，本批照片真实组件链路通过不表示生产或全部功能完成。测试种子无真实监控采样，不显示正常/达标；资产接入与配置发布不创建假operation。原128项合同TC仍NOT_RUN，不用照片切片测试替代逐条合同验收，优先级/负责人仍待填。

本轮及历史测试分开维护于[验证记录](verification.md)，照片范围见[照片代码切片](report-photos.md)。历史fixture不用于本轮共享测试环境验收；本轮纯测试也不代替集成。

历史不与本批累加。本批96纯单元/隔离、43 PG、3 MinIO通过，fixture13跳过；前端113及其修后44项重复回归、最终类型/构建与契约8项通过。基础80次、恢复52次、运行中重试25次HTTP及36 UI分别见[构建](evidence/local-photo-build-summary.json)、[HTTP](evidence/local-report-photo-http.json)、[恢复](evidence/local-photo-resilience.json)、[重试](evidence/local-photo-worker-retry.json)、[页面](evidence/local-report-photo-ui.json)，不合并单脚本计数。用户选择本平台权威仅关闭方向分歧，Q07剩余处理规则与Q08/Q09/Q14/Q16仍保留。

## D04诊断独立切片

[新说明](configuration-diagnostics.md)、[构建属性摘要](evidence/local-configuration-diagnostic-build.json)、[HTTP证据](evidence/local-configuration-diagnostic-http.json)和[UI证据](evidence/local-configuration-diagnostic-ui.json)记录这一后续功能：完整检查123纯/隔离+51PG+3MinIO通过，13fixture跳过；初始147前端=旧113+新34，安全metadata修复后最终154=初始147+新7，类型/构建及契约10通过。66定向回归包含于154；D04新增13JDBC、14API和8PG包含于JVM全量，旧测试不累计为新增。照片f56ad1b和CI此前结果保留各自版本。

真实APPROVED提案仍CONFIG_PENDING/null，六角色OIDC、严格单key/授权、A7/B1提案近期5条与截断/租户隔离、正文不返回、PGonly503及恢复通过；原registry/draft count和服务器内SHA256前后不变。26项真实Edge桌面/390px仿真UI、0pageerror及截图复核通过，旧列表非法Q类型安全展示已修并保留negative fixture；原表单真实POST丢响应重试同ID、发布422、只读/编辑保护和迟到GET隔离均留证。诊断读取切片验证完成，来源能力仍NOT_IMPLEMENTED/null，未实现正式生效或完整D04。

## D05来源与映射切片及Dot交接

2026-10-02 [来源切片记录](asset-import-source.md)区分原文件READY、目录SELECTION_REQUIRED、原始PARSED与映射保存。来源只对当前授权租户的创建者开放，写入保留CSRF/幂等/权限版本/CAS及审计；202之后只GET回读，映射200继续来源GET确认。前端保留不确定原请求并在上下文变化时终止清空，五个阶段都不显示资产导入成功。最终业务状态CONFIG_PENDING/can_import=false、有效规则/配额null，预检NOT_RUN。

用户最新要求编程默认由Dot自己完成，不得自行创建/调用/委派Codex或Work任务。旧Codex目标保持用户暂停状态；[交接](dot-development-handoff.md)记录真实验证、已执行迁移和明确未完成项。当前并未完成全部128项需求。

本地新前端定向15项包含在全量169项内；V0.8新增8项包含在18项契约检查内，类型与构建通过，均为隔离验证。parser25项来自解析子任务隔离结果，待主任务归档。指定MinIO的[命名空间/IAM证据](evidence/asset-import-component-verification.json)只证明项目组件权限，不证明V6、应用事务、Worker、真实HTTP/UI或完整TC-M3-6；相关真实链路在本草稿仍待补证。历史D04/照片计数与本批不累加，原128项仍NOT_RUN。

## 下一批

1. 内部修订、[配置筛选/差异](configuration-comparison.md)及[解析/快照纯核心](configuration-resolution.md)有独立证据；继续客户证据核验、发布及真实仓储接入，不能将内部审批视为客户生效。
2. 报障持久登记与查询已完成本切片验证，继续工单实际处理命令、客户确认、未解决、重开与SLA未知值边界。
3. 以已有PG outbox登记意图为基础，复用已发现的RabbitMQ做项目投递/消费者/租约/授权复核，不使用其他项目vhost或队列。
4. 按缺失组件清单获取Zabbix/堡垒机及通知资料；一期本平台工单登记不依赖缺失外部ITSM，后续外部对接如有需求再明确接口。组件依赖须真实联调，不能本机另建替代服务。

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
