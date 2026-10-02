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
