# MOP 实施状态

## 指标点与端口展示纯核心（2026-10-05 01:46 UTC）

首页e2a8960/CI37252350857成功；新增显式指标点标准化和端口合同展示基础，保留原值/Unicode单位/来源记录时刻，缺值sampledAt=null；精确转换不猜单位、不同scope/乱序/重复/超限拒绝，端口>50黄/>80红仅用于展示不发布阈值。24新增纯单测、根1503执行/781跳过、125契约、146Python通过；无新HTTP/迁移/采集/凭据。44行有进展备注，全部128正式NOT_RUN。见[核心说明](metric-series-projection.md)。

## 固定运营总览页面（2026-10-05 01:39 UTC）

上一客户端c883198/CI37252075550成功；/dashboard已接固定六KPI/六面板、三个趋势预设、30秒只读刷新/后台暂停、授权变更与晚到响应清理。18新Vue隔离测试，1608Web/94文件、类型构建、125契约通过。无健康/零值/曲线伪样本，无布局保存；原资产首页重定向保持。尚无当前真实PG/OIDC/HTTP或浏览器/CSS/手机证据，仍NOT_RUN。见[页面证据](evidence/niko-dashboard-page-20261005.json)。

## 首页前端只读模块（2026-10-05 01:35 UTC）

上一API77bc4a3/CI37251834577成功；生产前端协议核对与刷新状态机新增28隔离单测，1590Web/93文件、类型构建、125契约通过。严格固定顺序/null/上海各时间窗，同预设并发去重、跨预设/授权晚到防护、刷新错误清除旧投影，只有GET。当前PG/HTTP/浏览器仍未执行；[客户端证据](evidence/niko-dashboard-client-20261005.json)。

## 固定首页只读API（2026-10-05 01:31 UTC）

API0.44新增GET /dashboard及2schema，旧105路径/233schema不变；重新当前授权、只接收趋势window预设、固定顺序/未知null、错位投影503、no-store。21项新隔离边界/安全测试，43聚焦、根1479执行/781跳过、125契约、1562Web/类型构建、146Python通过。没有部署或恢复组件，没有将mock单测称为PG/OIDC联调；页面仍待实现。基础CI追踪备注失败及1b2c27c修复按实留证。详见[API证据](evidence/niko-dashboard-api-20261005.json)。

## 固定首页基础切片（2026-10-05 01:24 UTC）

恢复/阻塞记录已推送739b948；首页纯核心与只读PG授权/时钟reader重建完成，当前22纯聚焦、Java根1458执行/781跳过通过。固定六KPI/六面板、上海月初与独立24小时窗；未知指标/列表保持null，无自定义布局或写入。此批无HTTP端点/页面，契约仍0.43.0，当前组件/HTTP/浏览器未验。原环境PG观察与当前哈希证据分开，详见[首页说明](dashboard-fixed-overview.md)。128项仍全部NOT_RUN，42行进展备注不是完成项数。

## 当前执行环境恢复状态（2026-10-05 01:19 UTC）

最新已发布d24fecd/CI37248295961成功；源码1677文件、完整tree和commit已恢复核验，1562Web/类型构建、120契约、146Python在新环境通过。旧测试库/私有会话未恢复，新组件和凭据重建许可待答复，真实PG/OIDC/HTTP尚未重新执行。新首页基础代码已重建并通过22纯聚焦，Java根1458执行、781跳过；不把旧环境762PG记录当新环境通过。下方概览是中断前状态，不能据此认定API当前在线。详见[恢复说明](executor-recovery-20261005.md)。

## 中断前接续概览（2026-10-05 00:36 UTC，历史）

当前源码在niko自己的工作区，分支为`handoff/cloud-20261001-photos`。前一本人偏好客户端`5c75ad5e442fbaf3e413d71fe2e6b114f5077aeb`的CI37247374122已成功；在线后端为经过真实HTTP验证的API0.43固定包。以下为不同阶段的最新可核验证据，不将它们相加为一次验收。全部128项正式合同验收仍NOT_RUN；40行有切片进展说明，不是40项已完成。

|范围|已实现和真实证据|仍待完成|
|---|---|---|
|环境/版本|既有niko PG17.11、Keycloak26.2.4、API0.43；V1—V26冻结，V26显式独立执行/56旧表完整行等值；正常部署重启58表原行等值先于6次新OIDC登录|原指定服务器云端入口、OSS安全接入、生产部署/完整灾备；没有把niko验证当原服务器复验|
|备份计划草稿|V24不可变历史/同租户目标、受保护仓储、80真实API HTTP、原请求模块26HTTP、页面24单测/18真实组件HTTP；原未知重试/CSRF/CAS/归档恢复均有独立证据|正式可执行计划/默认模板自动纳入、SSH设备授权/命令、OSS/KMS和调度；草稿enabled/effective=false，浏览器/CSS/手机未验|
|恢复演练/备份差异/报备|自述演练记录与固定上海日期严格超过90天纯核心；备份run/attempt、脱敏diff、未审核报备草稿核心/V25结构/受保护仓储分别有证（结构21+仓储24真实PG及83API HTTP，客户端49测试/26HTTP+3GET、页面26SFC/18组件HTTP），审批/P3仍null|真实历史备份对象、演练持久化/页面、实际设备演练；没有自动恢复|
|巡检/来源集合|V21—V23模板、人工执行单、明确历史来源和冻结集合PG/API/前端；模板/执行单原服务器Windows指定场景已补；集合页面11真实组件HTTP|真实巡检附件/扫描、来源纳入规则、正式季度/月报/审批发布；集合不是正式报告|
|工单/工时/客户资料|本人实填与历史、跨工单本人查询（4纯/12PG、API47HTTP、只读模块28测试/17GET、页面18SFC+1状态/15真实组件GET，未聚合/团队化）、评论/内部备注、手工登记/人工恢复、联系人CRUD与导入队列已有PG/API/客户端切片；未知写入保护持续回归|正式状态/资格/SLA/客户确认/通知、团队工时与成本、真实身份绑定与渠道；外部ITSM不是一期建单前提|
|资产/导入/导出|来源解析映射及只读刷新修复、资料/维保/依赖草稿、XLSX准备与部分故障证据已留；新增本人只读进度投影6纯/7真实PG及明确仅准备API54HTTP、原请求客户端31测试/21HTTP、页面20SFC/10组件HTTP；新空结果请求实际JVM准备与同作业回读分段通过，文件描述不授予下载；原Windows所列移动仿真/截图已导入|真实预检/业务导入/500行指标、最终OSS发布、CMDB/监控及物理手机；D05仍CONFIG_PENDING/can_import=false|
|通知偏好请求|本人请求纯模型19测试，复用明确分钟静音、六通道枚举与固定上海时区；null/空集不混同，有内容指纹；V26本人唯一草稿/连续不可变修订22真实PG结构测试，受保护本人仓储26真实PG（原回执/CAS/历史/失败回滚）、API0.43/69真实HTTP/2本人槽位6修订6原回执；生产客户端35测试/26HTTP保留未知与原回执，既有槽位再追加2修订后恢复原字段；页面24SFC/13组件HTTP再追加2修订恢复原字段|真实浏览器/视觉、真实接收人绑定、Q14矩阵冲突、真实渠道/回执；不自动生效或发送|
|配置/审计/容量|草稿/内部提案/诊断/日历/能力目录/审计查询和容量保存/增长对照已有证据|客户真实性/发布生效、岗位矩阵、外部审计锚；实测容量及12500NVPS24h+2倍峰值30min PoC|
|最新质量|本人偏好API批根2213报告=2200执行，含754真实PG；13历史fixture跳过、6MinIO排除。最新页面1562Web/91文件/120契约/类型构建；前API批146Python；上批V26 CI原生pwsh121保护/0DB已通过|CI、纯模型、jsdom组件和模块HTTP均不等于真实浏览器或128项正式验收；未执行项不标DONE|

直接证据：[本人页面](evidence/niko-own-worklog-page-20261004.json)、[报备页面](evidence/niko-change-notice-page-20261004.json)、[报备API](evidence/niko-change-notice-api-20261004.json)、[备份页面](evidence/niko-backup-plan-page-20261004.json)、[备份API](evidence/niko-backup-plan-api-20261004.json)、[巡检来源](inspection-report-sources.md)、[合同进展索引](contract-progress.json)、[具体阻塞](current-development-blockers.md)。本概览不覆盖或重写下方历史时点。

## 2026-10-03接续概览（历史证据，不与后续批次累加）

开发已从executor丢失恢复到自己的工作区。源码993文件/13截图原hash核验；用户批准重建后的新niko-local-test实例与旧测试库分开留证。全部128项仍未整体验收DONE，历史批次不累加。

|范围|当前真实证据|仍未完成|
|---|---|---|
|环境与恢复|源码09b5854/tree完整恢复；新PG17.11/KC26.2.4/API0.30，V1–V16原字节初始化，V17联系人结构独立执行；6新OIDC和34HTTP；旧Windows原服务器V15证据单列|旧临时数据库未恢复；原服务器新入口/私有连接、OSS、生产部署与完整灾备|
|工单与工时|本人实填/历史、评论/内部备注、手工登记/独立人工恢复均有PG/API/实际模块切片；17个既有写路径原unknown重试保护已补并分别留证|正式工单状态/岗位资格/SLA/客户确认/通知、团队工时与成本；外部ITSM不是一期建单前提|
|资产资料|基础资料/筛选/日期对照/维保开始与供应商、查询与原请求回读均有真实证据；Windows所列移动导入/维保控件和13截图历史保留|新补丁真实存储、原生移动/完整浏览器、CMDB/监控和真实导入门禁|
|配置与审计|草稿/内部提案/诊断/日历/能力目录26项与审计查询有证；配置原请求重试4xx保护已修复|正式客户核验/发布生效、岗位矩阵、外部可信审计锚及导出|
|容量|8输入9输出/Proxy、不可变方案PG/API/原键恢复/保存回读面板、2–20明确主机数增长对照；API71、状态机19、增长39HTTP为各自批次|新页面实际浏览器/图形、原服务器V16、真实基准和12,500NVPS24h/2倍峰值30min PoC|
|最新质量|新实例根1120=1107执行含351真实PG+13历史跳过，6MinIO排除；本批Web680/61契约/构建通过；Python82为V17批次|不将纯测试、CI、自定义Vue renderer或模块HTTP冒充浏览器或完整合同验收|

详见[工作区恢复与新组件](evidence/niko-rebuilt-components-20261003.json)、[阻塞清单](current-development-blockers.md)。

## 2026-10-01历史交接基线（保留原环境事实）

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

新增14维护字段显式AND查询及可空字段unknown_fields；固定列枚举/参数化字面子串，严格日期不推断维保状态。真实PG只读快照、当前内部成员/租户manual来源、至多101有界created_at/id keyset；跨页非冻结快照。6核心+3真实PG新增，根558=545执行含136PG+13skip/6MinIO排除全部通过。API和页面查询待接线，未改变当时既有7列主台账布局或客户权限（12列合同尚未实现，见后续纠正），M3-5非DONE。见[资料说明](manual-asset-metadata.md)、[仓储证据](evidence/niko-asset-metadata-filter-repository-20261002.json)。


### 2026-10-02 内部资料筛选API V0.14

新增GET /assets/manual-metadata附加视图，严格14字段/显式未知AND、只读当前请求快照、成对创建时间/id分页和4096-byte编码查询限额。4控制器新增，根562=549执行含136PG+13skip/6MinIO排除，27契约/259Web/构建及63真实HTTP响应全部通过；12资产10+2分页、每字段/联合查询、编辑后筛选变动和跨租户/角色边界已验。页面筛选控件待，DOM/客户权限/主列版式Q31及全M3-5非DONE。见[资料](manual-asset-metadata.md)和[筛选API证据](evidence/niko-asset-query-http-20261002.json)。


### 2026-10-02 资料筛选附加页面

14字段明确不限/值/未知AND筛选、实际条件回显、15条配对cursor分页、同租户手工资料展开和进入维护历史面板已接线。12新模块测试，总271Web/类型/构建及27契约通过；实际查询模块/ApiClient29HTTP含8GET查询，16虚构资产/2明确资料写为准备，15+1无重复、变更后重读、显式未知/字面匹配及不写审计已验。当时既有7列主台账布局未改（12列合同尚未实现，见后续纠正），查询不是冻结导出；DOM/390px仍B03，Q09/Q31与完整M3-5未DONE。见[资料](manual-asset-metadata.md)和[查询客户端](evidence/niko-asset-query-client-http-20261002.json)。


### 2026-10-02 D07原因/解决说明文字校验修复

先补3回归重现2失败：BOM-only原因及非文本控制字符可穿过既有纯核心。修正BOM边界空白和ISO控制符拒绝（保留LF/CR/TAB），不改变原文或业务状态。14核心/根565=552执行含136既有PG+13skip/6MinIO排除通过。没有关闭/重开真实状态或接口接线证据，API V0.14不变，完整D07仍待。见[说明](ticket-customer-action-rules.md)、[证据](evidence/niko-ticket-text-guards-20261002.json)。


### 2026-10-02 D07内部备注输入核心

新增InternalTicketNote原文/2000 Unicode界限/精确UTF8 SHA256及追加仓储契约。5核心通过，根570=557执行含136既有PG+13skip/6MinIO排除通过；此136PG不是新备注持久化证明。真实结构/仓储/API/页面待，不向客户暴露内部身份，不改变工单状态/SLA；API V0.14与V1–V9不变，M5-D05非DONE。见[内部备注](internal-ticket-notes.md)、[证据](evidence/niko-internal-note-core-20261002.json)。


### 2026-10-02 V10不可变内部备注结构

仅niko独立PG执行新V10：check0→apply1→重复0→check0，旧资产迁移target也0。V1–V10现在全部冻结，新增结构另用V11；默认check只读、API/Worker不迁移。6真实PG约束/ACL回归事务回滚，11策略+31Python守卫，根577=564执行含142PG+13skip/6MinIO排除通过。SHA256 ba1ad02e7df8079936288d8712a84062a5bd695885b1e45daacec9f4d35ad198。内部备注仍待实际仓储/角色/幂等/API/页面，不改变父工单状态/SLA或扩展客户可见范围。见[内部备注](internal-ticket-notes.md)、[V10证据](evidence/niko-internal-note-schema-20261002.json)。


### 2026-10-02 D07不可变内部备注仓储

12新增真实PG覆盖授权/父版本/不可变原文/幂等并发/回滚/客户DTO不泄露；同原键仅一条，不同追加请求可分别保留，父业务版本和SLA不变。根589=576执行含154PG+13skip/6MinIO排除通过；断库503、不受保护事务拒写，无V10修改。公开API/页面继续，完整评论/客户可见/附件/通知及D07未DONE。见[内部备注](internal-ticket-notes.md)、[仓储证据](evidence/niko-internal-note-repository-20261002.json)。


### 2026-10-02 D07内部备注API V0.15

当前租户内部备注列表/追加/单条回读接线，msp/padmin专用能力，客户403、跨租户/错父404，严格body/CSRF/幂等/父版本；immutable/INTERNAL/SELF_REPORTED，父版本与SLA不变，无PATCH/DELETE。新增8控制器+1就绪，根598=585执行含154PG+13skip/6MinIO排除；83真实HTTP响应契约、28契约/271Web/构建通过，14备注14审计且客户DTO无泄露。页面继续；完整评论/附件/通知/工单闭环未DONE。见[内部备注](internal-ticket-notes.md)、[API证据](evidence/niko-internal-note-api-http-20261002.json)。


### 2026-10-02 内部备注页面与实际客户端

工单详情外保留内部备注目标/恢复入口、明确父版本、不可变追加、原请求不确定重试及独立回执接线。18新模块测试共289Web/类型/构建，28契约通过；实际模块/WebCrypto/ApiClient28HTTP，页面4POST含2原键重试+1独立内部作者追加，3备注3审计，父与客户DTO不变。原文摘要实际校验，不同租户/权限迟到响应清除；无编辑/删除、客户发送、附件或通知。DOM/390px仍B03，完整M5-7/D07非DONE。见[内部备注](internal-ticket-notes.md)、[客户端证据](evidence/niko-internal-note-client-http-20261002.json)。


### 2026-10-02 V10资料/备注已提交后API中断恢复

新独立拥有API故障场景首轮通过，旧工时及失败证据保留。故障前14HTTP、后12HTTP；只kill本次已核验API，exit137/连接拒绝/同jar重启，14白名单业务表指纹一致。2内部备注、资料当前v3/历史[3,2]保持；原键返回原备注/旧v2而不回退现值、不重复审计。默认组件/真实OIDC已恢复，35隔离守卫通过。没有PG/WAL/Worker/OSS/浏览器或原服务器故障验收，V1–V10不改，API V0.15不变。见[独立测试环境](niko-local-test.md)、[故障证据](evidence/niko-api-v10-crash-recovery-20261002.json)。


### 2026-10-02 主台账数据库有界分页/授权快照与Unicode修复

主GET /assets及详情改为真实PG同快照成员/权限版本核验，筛选/升序keyset/LIMIT在PG执行，应用最多取得page_size+1行，签名cursor绑定保持；不再主台账整租户加载。实际PG发现默认C/C locale不能对Ä小写，改用现有ICU根collation表达式并加真实就绪检查，资料查询/引用选择同步修复；未改全局locale/存储原文/角色/迁移。根616=603执行含163真实PG+13默认历史skip/6MinIO排除，54实际HTTP、29契约/289Web/构建通过。历史13 MockMvc仅单独隔离回归，不算组件证明；共享target并发Maven失败保留，最终串行全量通过。API V0.16；不声称5000台性能/DOM或完整合同验收。见[主台账读取](asset-ledger-reads.md)、[证据](evidence/niko-asset-ledger-read-20261002.json)。


## 2026-10-02 主台账12列纠正（M3-2，非DONE）

复核发现旧页面实际7列，之前“12列未改”措辞已纠正。按T00R027精确列名/顺序接线12列，地址/来源/草稿保留名称辅助文本；公共DTO缺少的Proxy/业务/CPU/内存/问题数/负责人显示未提供，不取内部资料或推断零/正常。9新增Web（总298）/类型/构建/29契约通过；实际列投影与ApiClient6身份20只读HTTP/90行、逐响应契约与审计不变通过，重启新会话同20请求另留证、不重复累计。首次会话刷新后续/assets500及ThrowableProxy类缺失如实记录，重启后恢复，运行JAR隔离另行修复。无Java/迁移变更，不把此前616根测试记作本次执行；DOM/390px/B03和实际缺失字段仍待，M3-2不标DONE。

详见[12列纠正与验证](asset-ledger-columns.md)。


## 2026-10-02 运行JAR与构建隔离

新增每次启动独立SHA256只读JAR副本，完整ZIP/本项目入口/复制稳定性/UID/权限/路径守卫，拒绝直接target回退；保持确切父进程/JVM命令/回环监听检查。48 Python守卫及语法通过；真实API运行期间强制Maven重打包，根616=603执行含163PG+13skip/6MinIO排除通过，target SHA变化而API PID/start-ticks/运行副本不变；不重启捕获6个新OIDC身份并20只读HTTP通过（含/assets404）。初次证据工具/proc children缺失失败已记录并修正为只读严格身份枚举；之前ThrowableProxy故障不声称唯一根因已复现。旧SIGKILL证据保留未重跑，V1–V10/API0.16/原私有配置不变。

见[运行产物隔离](niko-runtime-artifact.md)。


## 2026-10-02 工单Unicode查询与失效会话（API0.16.1）

2真实PG先复现Unicode漏查，再使用既有ICU根locale字面查询，保留原文/本人客户范围/授权租户和签名分页。8新增控制器含实际advice与安全响应的非字符串/空白租户、inactive成员拒绝，消除审计二次强转500，保留DENIED审计。最终根626=613执行含165PG+13skip/6MinIO排除、30契约/298Web/类型构建、62逐响应契约真实HTTP通过；14本轮合成登记/10+2分页、Unicode描述/字面边界/越权与非法输入已验。失败轮PG、控制器、版本断言和HTTP验收窗口/数据隔离修正均记录；不删除失败轮合成数据、不累计重复轮。正式状态/优先级/SLA仍null，浏览器/ITSM/OSS/完整M5未DONE，V1–V10不改。

见[工单读取防护](ticket-read-hardening.md)。


## 2026-10-02 客户可见评论核心（非持久化/API验收）

新增独立评论原文/精确UTF8 hash、1..2000码点技术边界；新鲜成员/权限/父租户/报障人守卫，客户仅本人，内部限当前租户，缺关系失败关闭。公共作者标签固定报障人/工程师，Entry不含真实actor ID/name；不转换内部备注，不改业务状态/SLA，不调用ITSM。8纯规则测试及根634=621执行（含165既有PG回归）+13skip/6MinIO排除通过；既有PG不算评论仓储证据。API0.16.1/前端/V1–V10不改。下一步独立V11与真实仓储，M5-7/M8-7/D07仍非DONE。

见[客户可见评论核心](ticket-comments.md)。


## 2026-10-02 V11客户可见评论schema（已执行冻结，业务仍待）

独立mop_ticket_comment/16列/14CHECK/复合租户FK与只追加ACL已显式check0→apply1→重复0→check0，旧内部备注apply0；V11 SHA256 d8535bbf13ca9e35c115278f36232a786b4562c99cc9afd9f5195a416028a8e3，V1–V11冻结。7真实PG schema测试全部回滚，新表只读复查0行；12迁移策略/49 Python守卫、根642=629执行含172PG+13skip/6MinIO排除通过。角色/报障关系仍须后续事务重查，schema不能冒充业务授权；没有评论API/页面/ITSM或状态变更，原服务器/私有配置不改。

见[客户评论V11结构证据](ticket-comments.md)。


## 2026-10-02 客户可见评论授权仓储

新增16真实PG首轮通过：本人客户/当前租户内部角色可见性、通用作者标签无身份字段、V10备注隔离、原文/hash、父版本不变、CAS/幂等/回滚/并发/分页/最大Unicode、只读/单连接/连接池失败关闭。新增idempotentAuthorized在缓存收据前同事务执行资源授权，原键不会跳过当前父范围核验；现有幂等入口保持兼容。根658=645执行含188PG+13skip/6MinIO排除、30契约通过，V1–V11冻结；此时已提交合成评论测试记录，schema阶段0行是历史核验。公开API/页面/ITSM/完整M5-7/M8-7仍待。

见[客户评论仓储](ticket-comments.md)。


## 2026-10-02 客户可见评论API0.17（非整体验收）

公开GET/POST comments和单条GET已接真实PG，客户仅本人、内部仅当前租户，重放前重新核验父资源。DTO作者固定报障人/工程师，无结构化内部身份；私有备注独立，父状态/SLA/版本不变，ITSM明确未请求/未应用。10控制器+1就绪，根669=656执行含188PG+13skip/6MinIO排除、32契约/298Web/类型构建通过；首轮84真实HTTP逐响应契约通过，主父14评论/14成功审计、同键并发2收据1行，其他客户/租户404，CSRF/正文/分页/不可变与只读审计边界已验。首次测试方法签名/ready样例失败均留痕后修正；评论UI/DOM/通知/外部同步仍待，V1–V11冻结不改，M5-7/M8-7非DONE。

见[客户可见评论API](ticket-comments.md)。


## 2026-10-02 客户评论页面/实际客户端

公开评论独立面板已接线，明确公开提示、固定公共作者标签、原文SHA256/严格字段/客户本人父关系验证，不与私有备注共享草稿或回执。明确父版本基准；刷新只读，未知结果保留原body/key/版本，显式重试，409不覆盖；晚到跨上下文结果丢弃，旧目标标题及恢复入口加租户/客户本人守卫。23新Web共321、类型构建、32契约通过；实际模块39HTTP，报障人4POST尝试（2原请求+2重放）/工程师模块1POST/独立内部追加1条，最终4评论4审计且1私有备注未混入。故意丢两次200非物理断网，父DTO/版本不变。Java/迁移未改，不重计先前根测试；DOM/390px/B03、通知/附件/ITSM和正式闭环仍待，不标DONE。

见[客户可见评论页面](ticket-comments.md)。


## 2026-10-03 Q08工作日历草稿核心（非生效日历）

依据Q08明确可推进范围，新增不可变草稿/仓储接口及显式试算：原始工作窗口保留，计算单独归并，覆盖声明不等于客户批准。无默认工作周/时段/节假日，不把次日补24小时；未知起点/时长/覆盖返回未解决，scenarioDeadline与始终null的effectiveDeadline严格分开。10纯测试及根679=666执行（188已有PG）+13skip/6MinIO排除通过；日历真实PG/API/UI尚未执行，未建V12，不能沿用既有PG证明日历。API0.17/32契约不变，V1–V11冻结，正式SLA与完整M5-D04/D08非DONE。

见[工作日历草稿核心](working-calendar-drafts.md)。


## 2026-10-03 V12工作日历草稿结构（已执行冻结）

新增独立草稿头与不可变修订表，原始窗口JSON随修订保存，复合租户/作者/前版FK、延迟当前修订FK及精确列级ACL限制写入。头表仅更新版本/更新时间，历史只追加；DRAFT、UNVERIFIED、effective=false、Asia/Shanghai与MANUAL_CALENDAR_DRAFT固定，不能借schema发布日历。输入纯SQL函数不读业务表、不以定义者权限运行、固定pg_catalog搜索路径，PUBLIC无执行权限；独立CLI校验其实际函数体SHA256，拒绝结构相同但函数体漂移。

V12已独立check0→apply1→重复0→check0；旧评论apply0。函数体指纹校验加入后又check0/重复apply0。V12 SHA256 e6e0412e8a96936a6f4e09dc9f96431cd6551dd9c15ebaf7d2ed414467aedb1a，V1–V12全部字节冻结，后续结构更正必须V13+；API/Worker无自动迁移。

10新增真实PG schema测试、13迁移策略、50 Python守卫通过；最终根690=677执行（含198真实PG）+13历史fixture跳过，6MinIO排除，0失败错误。schema测试全部回滚，测试后只读两新表均0行，Q08两项配置value仍null。首次执行前补强previous_version非空CHECK，执行后SQL未再改；函数指纹仅增强Java校验。API仍0.17，未新增日历HTTP、业务仓储、页面、正式SLA或客户批准，完整合同仍未DONE。继续下一步真实事务仓储，不因B01/B02/B03/B04暂停无关开发。

见[结构证据](evidence/niko-working-calendar-schema-20261003.json)。


## 2026-10-03 工作日历草稿真实事务仓储

PostgresWorkingCalendarRepository已实现当前租户padmin草稿创建、CAS更新、有界列表/修订历史和只读查询。读授权、数据、前后快照均在单一repeatable-read连接；写动作在既有受保护幂等事务中重新核验当前成员/用户/权限版本，锁定授权行并对更新锁定头记录。缓存回执前仍核验当前配置资格和对象租户，跨租户404，不能从普通无幂等事务写入。原始窗口/文本保存、前后历史只追加、审计与幂等收据共同提交或回滚；不改Q08登记值、正式SLA或客户确认。

14新增真实PG覆盖原文/重叠窗口、不可变版本历史、资格/失效权限/跨租户、独立连接、无事务拒绝、同键重放与payload冲突、重放前资源门禁、CAS冲突、回滚后原键重试、不同键并发CAS一胜一冲突、同键并发单修订、分页/只读无审计、Unicode上限/显式空/不完整，以及单连接/已关闭真实连接池503无回退。关闭的是独立测试连接池，不是PG服务器故障。测试已提交明确合成草稿；上一schema阶段两表0行只是当时历史证据。

初次13仓储用例有2个JSON比较断言失败：Java long节点与PG JSON反序列化int节点数值相同但JsonNode类型不同。改成双方经实际序列化/解析后比较，并新增同键并发，未放宽业务断言。最终根704=691执行（含212真实PG）+13历史fixture跳过、6MinIO排除，0失败错误；32契约与生成一致性通过；独立迁移check0、V1–V12字节未改。API仍0.17，日历HTTP/页面、浏览器与正式生效仍待，完整合同未DONE。

见[仓储证据](evidence/niko-working-calendar-repository-20261003.json)。


## 2026-10-03 API0.18日历草稿与只读试算

公开GET/POST /working-calendars、GET/PATCH /{id}、GET /{id}/history与POST /{id}/preview已接真实PG，仅当前租户padmin配置权限。输入精确白名单，262144字节/1000窗口/Unicode与毫秒精度边界、严格重复JSON键/嵌套/尾随数据检查；时区/租户/身份/生效/周期默认覆盖均不接受。写入保留CSRF、原始请求规范哈希、幂等、重放前当前授权、CAS与事务审计；窗口数组顺序不归一，嵌套对象键顺序不影响请求哈希。列表/历史至多100+1取数，所有查询参数拒绝重复及覆盖。

只读试算使用POST承载明确expected_version/start/required_seconds，仍要求CSRF；不要求幂等键，不创建收据或成功审计。缺起点/时长保持UNRESOLVED，显式0只是数学值；覆盖不完整/不足不返回scenario_deadline。结果绑定当前草稿版本，版本变化409；effective_deadline始终null、effective=false、customer_confirmation=UNVERIFIED。未实现正式SLA、生效发布、周期工作周、节假日或次日默认。就绪新增实际日历schema/ACL/校验器指纹探测；liveness不探测，不自动迁移。

11控制器+1就绪新增用例；最终根716=703执行（含212真实PG）+13历史fixture跳过、6MinIO排除通过，34契约/321Web/类型构建通过。首轮契约测试把全部POST视为写入，单个只读preview无Idempotency-Key断言失败；修为只针对该确切只读路由豁免幂等头，仍强制CSRF，并增加未知/正式截止分离契约用例，未放宽其他写操作。

实际栈正常重启加载新JAR并完成6身份OIDC后，首轮84HTTP逐响应契约通过：主日历12修订/12成功审计、10+2历史、第二不完整Unicode上限草稿、重放/同键并发单修订/异键CAS一胜一冲突、客户/工程师403、跨租户GET/PATCH/preview404与租户恢复、无CSRF/未知字段/坏JSON/精度/窗口/分页拒绝、原始窗口重叠合并试算、未知/零/不足/不完整、只读查询及preview前后审计尾序不变。没有物理断网、角色在线撤销或浏览器DOM验证。V1–V12仍冻结，API/Worker不迁移，完整D04/D08和128项未DONE；下一步日历管理/试算页面。

见[真实HTTP证据](evidence/niko-working-calendar-api-http-20261003.json)与[API回归](evidence/niko-working-calendar-api-20261003.json)。


## 2026-10-03 工作日历只读查询与试算页面切片

配置页新增独立工作日历抽屉，支持15条列表/历史分页、明确选定版本、原始覆盖/窗口/说明、未生效与未核验标签、显式起点/秒数试算。起点与时长不预填；空值保持未知，0显式显示。结果分别展示草稿数学截止和始终null的正式SLA截止，并注明实际提交起点/时长；修改输入清除旧试算。此切片无创建/编辑日历入口，不把API写能力当成完整管理UI。

实际前端reader严格检查DTO白名单、当前租户、版本、UTC/Unicode边界、列表游标/顺序、前后历史连续性；详情与历史不是同一版本时整组拒绝显示，失败不是空列表。试算绑定所选ID/version与明确输入，并用BigInt毫秒复核显式工作窗口并集、消耗区间与剩余时长；不补正式规则。409清除试算不自动重试/重定基准；切换租户/身份/权限或关闭面板中止读取，晚到详情不会继续请求历史或回填旧范围。查询刷新也不保存任何草稿。

21新增Web共342项、类型/构建、34契约与生成一致性通过。实际reader+ApiClient完成47HTTP，其中reader22请求（7次只读preview POST，其他GET），独立准备18次明确合成草稿写入；15+2修订分页、版本冲突409、未知/零/不足与窗口并集复核、丢弃一次成功history GET后清空/显式只读恢复、客户403、上下文清理均通过。最终草稿仍v18且18成功审计，reader只读窗口审计尾序不变；丢弃响应不是物理断网。Java/迁移未改，不重复计算上一根回归。V1–V12冻结、API0.18不变。

桌面与390px CSS已实现，但浏览器B03仍未解除、DOM/截图NOT_RUN；下一步草稿创建/编辑面板及原请求恢复，客户正式生效、SLA和完整D04/D08/128合同仍未DONE。

见[实际客户端证据](evidence/niko-working-calendar-reader-http-20261003.json)与[页面回归](evidence/niko-working-calendar-reader-web-20261003.json)。


## 2026-10-03 工作日历草稿编辑与原请求恢复页面

只读面板接入独立创建/编辑抽屉：名称、明确覆盖起止、已知窗口声明、完整性选择与可空说明。新建不预填时段或完整性，窗口每页20行、最多1000技术上限，可明确空列表/不完整。固定编辑ID/version与原文独立保存；刷新不替换输入或重定版本，已知新版本阻止旧基准提交，必须明确重新读取并确认替换未发送编辑。显式offset规范化UTC不修改Unicode原文。

实际writer对原body/key/目标/基准作不可变请求快照；未知结果锁住目标，只能显式原请求重试。成功回执核验当前租户/作者、期望版本、原始窗口/文本/声明及时间；回执后GET失败仍保留已确认成功，不重复写。409拒绝后不自动重试/新键覆盖；晚到跨上下文响应不回填。收起保留本页编辑，浏览器刷新/路由离开有未保存提醒及未知结果拦截。

日历编辑器新增主动切租户/退出守卫，未知保存阻止切换，未发送内容需用户确认舍弃。App在守卫等待期间不卸载编辑器；确认前不取消请求，切换与退出全程互斥，失败关闭。强制会话失效仍立即清除保护数据，不以未保存草稿阻止授权失效；未承诺跨身份/浏览器刷新后的原请求恢复。当前守卫仅日历编辑器注册，其他历史写面板的主动上下文切换接入将作为独立后续功能继续验证。

20writer+6守卫+5实际session-store单元共31新增，Web373/类型构建、34契约与生成一致性通过。实际writer+ApiClient22HTTP，6前端写尝试=3逻辑保存（创建/更新各含一次丢弃200后的原键重放，以及一次409拒绝和后续明确新基准保存），独立并发更新1次，最终v4/4历史/4成功审计。只读刷新不写、不重定基准；丢弃两次成功写响应及一次GET非物理断网。客户403、显式空/不完整/null、历史原文与offset规范化均通过。另用实际session store/ApiClient/守卫执行3HTTP（1会话GET与2获准切租户并恢复），拒绝/等待守卫不发送切换/退出请求，退出HTTP未执行；守卫决策由测试控制，不是DOM点击。

Java/迁移未改，API0.18及V1–V12冻结。日历创建/编辑/查询/试算页面代码已接线，但B03浏览器/390px/截图验收NOT_RUN；客户正式确认、生产许可、工作日历生效/SLA及完整合同仍未DONE。

见[writer实际HTTP](evidence/niko-working-calendar-writer-http-20261003.json)、[上下文守卫实际HTTP](evidence/niko-context-guard-http-20261003.json)及[页面回归](evidence/niko-working-calendar-editor-web-20261003.json)。


## 2026-10-03 既有写面板主动上下文保护

在工时、资产依赖、资产资料、内部备注、客户评论、导入源、客户报障表单、登记详情照片8处接入现有主动切租户/退出守卫。守卫每次读取实时未确认状态，原请求未处理完成不允许发出上下文切换请求；组件释放先注销守卫，互不移除其他组件。报障已受理但尚未核验到耐久登记、照片未完成或追加未知也保持保护，已完成后自动允许继续。强制会话失效清理保持，不用此守卫阻止授权失效。

8项组件接线源码检查及3项实时状态/多面板/登记组合规则测试新增11，Web384/类型构建、34契约与生成一致性通过。实际客户评论workspace+ApiClient+session store+守卫完成16HTTP：1合成父登记，丢弃一次成功POST后切租户/退出均被阻止且零HTTP，普通只读refresh不放行；原key/body重试后仅1评论/1成功审计，随后实际切租户并恢复。2评论POST尝试共享原请求，未把丢弃响应说成物理断网。组件Vue接线由源码测试验证，不是DOM交互；真实OSS、照片/导入源存储故障此次未测。

Java/迁移/API未改，V1–V12冻结、API0.18不变。配置修订提案及旧简易值草稿编辑的未知结果恢复/主动切换门禁仍需单独完善；本切片不声称所有历史写页面已覆盖。该代码缺口已记录B06，下一步优先处理，其他合同仍持续推进，完整128项未DONE。

见[实际未确认评论上下文保护](evidence/niko-write-context-guard-http-20261003.json)与[8入口检查](evidence/niko-write-panel-context-guards-20261003.json)。


## 2026-10-03 B06配置提案/审批原请求恢复修复

ConfigurationRevisions已改用独立configurationRevisionWriter保存不可变原body/key/动作路径/目标/基准。未知结果冻结输入及版本，显式原请求重试不依赖当前回读流程仍允许原动作（已提交/已审核后仍能取原收据）；不得修改正文后换key。409已知拒绝不自动重试，需明确回读与准备新基准。创建、提交、另一管理员审核、撤回均校验当前租户/配置键/ID/version、原提案/证据、不可变历史前缀、动作人及意见、effective_value=null/UNVERIFIED。

主面板modelValue变化不再reset命令；未知写对话框关闭/替换目标受保护，配置页打开其他key先检查原面板未确认状态，路由/主动切租户/退出及beforeunload都有保护。普通列表/详情刷新严格核验范围/流程历史，不重定原请求；成功写入先保留原收据，再独立GET，即使后读失败也不将成功误判为需再次写入。强制授权上下文失效仍清除保护数据；未承诺跨身份或浏览器刷新后恢复。

16新Web共400项、类型构建、34契约与生成一致性通过。实际writer+ApiClient30HTTP、12前端POST尝试及1独立并发提交：3合成提案最终版本3/2/3，共8成功变更及8审计；创建/提交/审核/撤回各丢弃一次成功200后以原key/body重放，只有原历史/审计一次。已知409不重试，明确核对新基准后撤回成功；只读审计尾不变，设计登记、旧建议草稿与生效值完整前后相等。审批仅合成内部测试意见/资料引用，不是客户确认。丢弃响应不是物理断网。

B06的提案/审批部分代码与真实HTTP已修；旧简易值草稿编辑尚未覆盖，继续下一独立功能。API/Java/V1–V12未改，不重复计入上一根回归。DOM/390px/实际控件验收仍随B03未执行，客户真实性/正式配置生效和完整D04/128项未DONE。

见[实际原请求恢复HTTP](evidence/niko-configuration-revision-writer-http-20261003.json)及[页面回归](evidence/niko-configuration-revision-recovery-web-20261003.json)。


## 2026-10-03 B06旧设计登记草稿原请求恢复修复

旧“编辑草稿”入口已接独立configurationDraftWriter，明确标识全局设计登记建议草稿，不能冒充当前租户运行配置。此范围来自既有API实际按配置键存储事实；契约仅补x-mop-scope/说明，路径、输入输出行为及API0.18版本不变。当前租户提案/审批仍使用独立revisions接口，设计value/registered_value/effective_value及客户确认不改变。

保存时冻结原配置键、期望版本、proposed_value和幂等键；未知结果阻止换目标/改正文重发，显式原请求重试。只读整份登记回查不重定基准；已知新版本阻止旧基准提交，采用新基准必须明确确认且保留当前填写值。回执需与原值/目标/版本增量相符，所有不可写登记元数据保持一致；成功回执后GET失败仍保留已确认成功。409不重试，双击防重，过时授权上下文响应丢弃。主动切租户/退出、路由离开与beforeunload保护未知或未发送编辑；强制会话失效仍清理。

18新Web共418项、类型构建、35契约与生成一致性通过。实际writer+ApiClient22HTTP，4前端POST尝试（丢弃一次成功写响应后的原键重放、一次409、一次明确新基准保存）对应2逻辑保存；另2独立变更为并发草稿和最终恢复，合计4变更/4成功审计，版本增加4。测试只选择niko库已被JUnit CAS用例写入31/32的设计草稿；finally按版本/本次值边界核对并恢复原draft_value，原登记/生效/元数据前后相等，保留真实版本与审计，不删除证据。没有更改Keycloak配置、运行安全策略、原服务器或客户正式配置。

另丢弃一次后续GET，已确认回执仍保留；客户403、只读审计尾不变、原body/key/目标/版本均核验。响应丢弃不是物理断网。B06提案/审批及旧草稿的代码/真实模块HTTP缺口已修；其DOM/390px控件仍随B03未验，不把代码完成记作完整合同验收。Java/API实现/V1–V12未改，正式运行定义、客户真实性与发布继续保留门禁。

见[旧草稿实际HTTP](evidence/niko-configuration-draft-writer-http-20261003.json)及[页面回归](evidence/niko-configuration-draft-recovery-web-20261003.json)。


## 2026-10-03 M5-6手工来源事实核心（未接仓储/API）

按合同M5-6原文，先定义标题/明确严重级别/关联资产及所见资产版本的不可变事实。五个合同等级为灾难/严重/一般/警告/信息，代码沿用已核对原型语义disaster/high/average/warning/info；不把none、unknown或监控NORMAL推成默认严重级。标题保留原Unicode及空格、至多200 code point是技术保护，不是新增客户规则；关联资产须完整UUID、版本为明确安全正整数。

source_state恒MANUAL、verification_status=SELF_REPORTED、registration_status=REGISTERED_ONLY；monitor_state/disposition_state/priority/observed_at/recovered_at均null。技术登记时间不是实际故障/恢复时间。监控恢复许可为false、不会自动创建工单；人工恢复确认、正式事件五态和工单关联尚未实现，不能靠监控正常推断恢复或把本类型当已建单。内部当前成员/权限版本及同租户资产门禁定义在纯规则中，生产Q09尚未批准；客户与未知角色拒绝。仓储接口要求后续在幂等事务中重查成员/资产/CAS/审计，重放前再授权。

10纯规则测试及完整根726=713执行（含212既有真实PG）+13历史fixture跳过、6MinIO排除通过，35契约不变。首次把测试放在无JUnit依赖的domain模块导致测试编译失败；按仓库既有约定移至api测试模块，保持domain无测试依赖，完整重跑通过。212PG是已有模块回归，不是手工事件PG证据；尚无V13、业务仓储、API或页面，也没有真实监控回调/恢复测试。

下一步独立V13与真实PG登记，再接严格来源投影和列表/表单。V1–V12字节冻结、API0.18不变；完整M5-6及D06/D07/128项未DONE。

见[手工来源登记](manual-incidents.md)。


## 2026-10-03 V13不可变手工事件登记结构与真实PG

在niko独立库显式执行V13一次，重复执行/只读check/旧V12模式均0。20列与18项CHECK、主键/同租户资产和作者组合外键、登记排序索引及运行角色仅SELECT/INSERT已验证；PUBLIC无授权，无列级UPDATE。原登记不可覆盖，后续人工恢复事实或工单关联需独立证据。MANUAL/REGISTERED_ONLY/SELF_REPORTED固定，监控/正式处置/优先级/实际发生和恢复时间保持null，自动建单false；数据库拒绝把监控正常当人工恢复。

8项新增真实PG schema测试全部事务回滚，涵盖原Unicode/200 code point、五等级、跨租户外键、版本安全范围、来源/未知投影、ACL及时间/快照边界。测试后只读确认登记表0行、Q08两项value仍null。完整根736=723执行（含220真实PG）+13历史fixture跳过，6MinIO排除，15迁移策略/51Python保护/35契约通过，无失败。CLI改为显式版本/目标矩阵，仍要求连续已验前置历史、独立单功能执行，不自动跨越迁移；纯矩阵测试覆盖所有模式与已验状态。

V1–V13已字节冻结，V13 SHA256为80734d0a0dcd436a3008ed76af0b7da3e81956f516a25d778c0717df79665700；后续结构修正V14+，API/Worker启动不迁移，API0.18不变。结构通过不等于当前角色授权、资产实际版本CAS、幂等/审计仓储或API/UI已验。下一步实现同租户当前内部成员的登记仓储、原请求重放前再授权及真实PG并发；人工恢复/正式事件状态/建单闭环与完整M5-6/D06/D07/128项仍未DONE。

见[手工登记结构证据](evidence/niko-manual-incident-schema-20261003.json)。


## 2026-10-03 手工事件登记仓储与真实PG事务

PostgresManualIncidentRepository已实现同租户内部成员的有界列表/详情/不可变登记。读取在同一个只读REPEATABLE READ快照重查当前角色及权限版本；写事务锁定当前成员/用户与关联资产，检查所见资产版本，并从数据库保存当时资产名，不信客户端名称。只允许受保护幂等事务执行；回执查询之前再次检查当前成员与关联资产可见性。原请求重放保留原名称/版本事实，即使资产后续改名；新请求使用旧版本409，跨租户资产/登记404。

14项新增真实PG通过：当前内部两角色/客户拒绝、过时成员、跨租户、Unicode与五级严重度、分页、原键与变体冲突、原请求在资料修订后恢复、回执之前再授权、下游失败全量回滚、同键并发仅一次登记/审计、不同请求并发独立事实、持有资产更新锁时提交后重查版本。登记不改资产、不自动建工单；未知监控/正式处置/优先级/恢复时间保持null。审计和回执与登记原子提交。

完整根750=737执行（含234真实PG）+13历史fixture跳过、6MinIO排除，35契约及生成一致性通过。两次全量运行均通过，最后一遍包括新增资产更新竞争测试。测试已提交专用合成资产/登记；此前schema阶段0行只表示当时回滚证据。API0.18和V1–V13字节未改；尚无手工登记公开API/CSRF/页面证据，人工恢复、正式处置/建单/通知/SLA及完整M5-6/128项继续未DONE。

见[手工登记仓储证据](evidence/niko-manual-incident-repository-20261003.json)。


## 2026-10-03 API0.19手工来源登记与108真实HTTP

新增GET/POST /api/v1/manual-incidents及GET /api/v1/manual-incidents/{id}，仅当前租户内部msp/padmin，以manual_incidents:read/write明确能力投影。POST只接受title/severity/asset_id/expected_asset_version；8192字节、严格JSON重复键/深度/尾随内容、字段白名单、原Unicode与五等级、安全正整数/完整UUID。请求不得覆盖租户/作者/资产名快照/监控/正式状态/优先级/发生恢复时间；CSRF与幂等键必需。字段顺序规范化摘要，原始值仍有意义。200明确仅REGISTERED_ONLY。列表技术单页10/15/25/50/100，成对登记时间/ID keyset，当前请求快照而非跨页冻结。

11controller与1ready新增测试，完整根762=749执行（含234真实PG）+13历史fixture跳过、6MinIO排除；37契约、418Web与类型构建通过。首次契约验证中的旧就绪成功样本缺新增必需manual_incidents，已补实际字段并完整重跑37通过，未放松生产校验。真实拥有栈正常退出后以新固定只读JAR启动，刷新6个实际OIDC身份后108HTTP首遍通过，全部响应按API0.19契约验证。

14合成登记/14成功审计；同键并发/JSON换序/后续资产改名均重放原收据，无重复审计；不同明确来源独立登记，新请求旧版本409；五级与200 emoji标题通过，客户403/匿名401/CSRF403，跨租户资产和登记404并恢复原租户，越权字段/未知值/过限/坏分页400。原始资产名/所见版本快照保持；登记不更改资产，后续资料改名是独立测试动作。只读列表/详情不新增成功审计。

ready检查真实手工表/ACL并声明MANUAL、REGISTERED_ONLY、监控恢复false/自动建单false，live不探测不迁移。V1–V13未改，存储仍关闭。下一步页面的明确手工标记、只读列表与原请求恢复表单；该模块尚无UI/DOM验收、人工恢复或正式事件处置/建单/SLA联调。完整M5-6/D06/D07/128项仍未DONE。

见[API与回归证据](evidence/niko-manual-incident-api-20261003.json)及[真实HTTP证据](evidence/niko-manual-incident-api-http-20261003.json)。


## 2026-10-03 手工来源只读页面与真实前端模块

新增内部能力受控的“手工来源登记”路由及桌面/移动导航；列表与详情明确手工标记、原文标题/资产名快照/所见资产版本、五级严重度。技术登记时间与实际发生时间分开，监控/正式处置/优先级/实际发生恢复仍显示未知；不提供恢复或正式建单按钮。15条有界keyset，严格24字段/租户/身份/级别标签/固定来源/未知投影/时间排序和游标验证，拒绝把监控NORMAL或RESOLVED当作有效手工回读。

15新增Web共433项、类型与构建、37契约通过。使用实际manualIncidentReader+ApiClient连接真实PG/OIDC，15次实际GET（其中reader8次），只读取既有合成登记，没有准备或业务写入；GET分页无重复、原始详情不变、客户列表/详情403、只读成功审计尾不变。刻意丢弃一次已完成GET响应后清除旧详情，再读恢复；挂起一次已完成响应跨授权上下文重置后丢弃。故障注入不是物理断网，页面源码接线/响应式CSS测试不是DOM或390px视觉验收。

API0.19/V1–V13未改。当前只读页功能已接线，创建表单和原请求恢复继续下一独立功能；人工恢复/正式处置/事件建单闭环与全部128项未DONE，B03浏览器路径及其他外部依赖仍按范围保留。

见[只读页面回归](evidence/niko-manual-incident-reader-web-20261003.json)和[真实模块HTTP](evidence/niko-manual-incident-reader-http-20261003.json)。


## 2026-10-03 手工登记表单、真实资产选择与原请求恢复

只读页已接新建手工登记面板。标题/严重级别不预填；从当前租户真实资产有界搜索/分页选择，并GET重新读取资产后固定所见版本与名称。表单不能填写租户/作者/监控/正式处置/优先级/发生恢复时间。提交冻结title/severity/asset_id/expected_asset_version与幂等键；未知结果锁定输入/目标/新请求，只允许完全相同原请求重试。只读资产刷新不重定基准或重发；已知新版本阻止旧基准的新提交，明确采用须绑定确认时显示的资产ID/名称/版本，之后仍需明确提交。

20新增Web共453项、类型构建、37契约通过。实际writer+ApiClient最终38HTTP，5个前端POST尝试对应3条独立合成登记/3成功审计；另4个准备写为1资产创建与3次独立资料修订，最终资产v4。丢弃一次成功POST响应后，资产已改名/升级仍用原内容/键重放原v1收据；又丢弃详情GET后成功回执仍保留。验证本地已知版本冲突零写入、实际409不自动重试、明确采用新基准、持有已完成写响应时双击/忙时重试不新增请求、客户提交403、登记不改资产、只读审计尾不变。

另保留此前独立首遍38HTTP证据，每遍使用不同合成资产/登记，最终证据针对新增“确认所见版本绑定”保护后的代码；不累计成一组登记。模块故障注入是丢弃/挂起已完成响应，不是物理断网。面板收起保留当前状态，路由/主动租户切换/退出注册未确认结果守卫，未发送编辑需确认，beforeunload提醒，强制会话失效清除原范围输入；目前这些是源码接线/单测，实际控件操作仍随B03未验。

API0.19/V1–V13不变。后续继续手工来源人工恢复证据与D06/D07闭环，不把此登记表单标为完整M5-6、正式建单/处置/SLA或全部128项完成。

见[表单与回归证据](evidence/niko-manual-incident-writer-web-20261003.json)和[最终实际模块HTTP](evidence/niko-manual-incident-writer-http-final-20261003.json)。


## 2026-10-03 人工恢复确认纯核心（未接结构/仓储/API）

按M5-6明确“恢复须人工确认、不得由监控正常自动判定”，新增独立ManualRecoveryConfirmation及仓储接口。原文说明/UTF8摘要保留，2000 code point是技术保护；reportedRecoveredAt允许null，不能用技术记录时间补实际恢复时间。显式时间须毫秒精度/有效范围且不晚于服务端确认时间，允许早于原登记的历史恢复时间。状态HUMAN_CONFIRMED仍SELF_REPORTED，不冒充独立物理核验；monitor/disposition保持null，工单状态/SLA不变。

首份确认采用独立0→1技术版本（0仅表示没有确认记录），要求当前内部成员、同租户MANUAL原登记、重放之前再授权、同事务确认/审计/回执；这是后续仓储必须实现的接口约束，并非当前已执行。不同新请求的旧版本冲突409，已有确认不允许覆盖；更正/撤回流程尚未实现。确认者/父登记/技术时间必须完整。原V13登记和原幂等收据保持不变。

9纯规则及完整根771=758执行（含234既有真实PG回归）+13历史fixture跳过、6MinIO排除，37契约通过。234PG不包含人工恢复新业务；尚无V14、人工恢复仓储、公开接口、页面或监控回调实测。API0.19/V1–V13未改。下一步独立V14及真实PG事务；恢复当前投影必须与不可变原登记分开，届时页面不能继续把原收据中的NOT_CONFIRMED或硬编码徽标当当前恢复状态。Q29正式处置映射/工单闭环/客户岗位与128项整体未DONE。

见[人工恢复纯核心证据](evidence/niko-manual-recovery-core-20261003.json)。


## 2026-10-03 V14人工恢复独立证据结构与真实PG

显式独立执行V14一次；此前只读check0，重复执行/之后check/旧V13模式均0。mop_manual_recovery_confirmation共17列、16项CHECK，租户+原登记唯一保证一份不可变确认，外键只指向V13手工登记和同租户作者。原说明及UTF8摘要、可null的reported_recovered_at、单独recorded_at保留；已填恢复时间不得晚于确认时间。HUMAN_CONFIRMED仍SELF_REPORTED，监控/处置null、自动监控恢复/工单状态/SLA变更false。运行角色只SELECT/INSERT，无PUBLIC授权/列级UPDATE，也没有为原V13父表扩张UPDATE授权。

8新增真实PG schema测试涵盖原文/hash/未知时间、原登记指纹不变、跨租户外键、唯一确认与版本、Unicode/空白/控制符/错误摘要、未来恢复时间、声明伪造及ACL。测试原登记和确认全部事务回滚，随后只读确认恢复表0行、Q08两项value仍null。16迁移策略/52Python保护、完整根780=767执行（含242真实PG）+13历史fixture跳过、6MinIO排除及37契约通过。

V1–V14全部字节冻结，V14 SHA256为39be55d8759de87c6e2b8f85b3958c60be24ae9e6ece2deb5d849c225050ed72；后续结构修正V15+，API/Worker不自动迁移，API0.19不变。下一步当前成员与同租户手工父登记授权、确认CAS/审计/回执事务及真实并发；父表不可变且无UPDATE权限，不以额外授权换取显式父表FOR SHARE锁。结构测试不算业务恢复、公开HTTP、UI、监控回调或正式工单闭环验收。

见[人工恢复结构证据](evidence/niko-manual-recovery-schema-20261003.json)。


## 2026-10-03 人工恢复仓储、原子确认与真实PG并发

PostgresManualRecoveryRepository已实现独立当前确认读取/首次人工确认。只读授权与数据在同一REPEATABLE READ快照；写时锁当前成员/用户，重新核验同租户MANUAL父登记，重放前也检查可见性。V13原登记不可变且无UPDATE权限，使用普通读取与组合外键保护，不扩张父表授权。按租户+原登记的事务advisory锁与唯一约束序列化首份确认；0→1技术CAS，新键旧版本409，已确认不能覆盖，原键仍返回原证据。

确认、成功审计及原回执同事务；技术记录时间来自PG，reportedRecoveredAt可null，已填不得在未来。说明/hash保留，HUMAN_CONFIRMED仍SELF_REPORTED，监控/正式处置null，不变工单/SLA，也不改原登记及其原收据。14新增真实PG覆盖当前角色/客户拒绝/跨租户/过时权限、回执前再授权、原键/变体、下游回滚、相同键并发一次写入、不同人/不同键一成功一409、原登记/工单数量不变、历史时间/未来拒绝及最大Unicode说明。

初次测试编译因局部var复合声明不合法失败，拆为两个声明后完整重跑；随后补强ready对V13父表SELECT的依赖检查，再完整执行通过。最终根794=781执行（含256真实PG）+13历史fixture跳过、6MinIO排除，37契约通过。业务测试已提交专用合成人工确认，schema时点0行仅历史证据。

同时纠正旧只读页的“恢复未确认”徽标：原V13字段始终是原登记快照，不应在已有独立确认后被当成当前状态。现明确“原始快照/原登记时恢复状态”，当前人工确认暂未加载，不能推断；契约只补该字段描述，响应结构/API0.19不变，新增1页面源码断言后454Web/类型构建通过。当前仍未提供人工恢复公开API或操作UI，下一步接独立当前投影与确认接口；撤回/更正、正式事件五态/建单/SLA、DOM及128项整体继续未DONE。V1–V14未改。

见[人工恢复仓储证据](evidence/niko-manual-recovery-repository-20261003.json)。


## 2026-10-03 API0.20独立当前人工恢复查询/确认与94HTTP

新增GET/POST /api/v1/manual-incidents/{incidentId}/recovery，当前内部manual_recovery:read/confirm能力。返回独立当前包装：无确认version0/confirmation=null/NOT_CONFIRMED；有不可变证据version1/HUMAN_CONFIRMED，仍SELF_REPORTED。原GET手工登记及原创建回执完全不变，不再把原登记快照当成当前恢复。POST仅expected_version/note/reported_recovered_at（必须显式填写null或时间），32768字节/严格重复JSON、层深、尾随内容、Unicode和时间精度；确认人/记录时间/监控/正式状态字段禁止覆盖。CSRF、幂等、当前父登记重放前再授权及审计保留，实际未来恢复时间拒绝。

11controller/1ready新增，完整根806=793执行（含256真实PG）+13历史fixture跳过、6MinIO排除；39契约/454Web/类型构建通过。正常退出旧拥有栈，使用新固定JAR启动并刷新6个真实OIDC身份后，94实际HTTP首遍通过。4份合成人工确认/4成功审计；同键并发仅一次确认，不同人/不同键一成功一409；原键/JSON换序重放原回执，同键改文409，新键旧版本409、已确认不覆盖422。客户403/匿名401/CSRF403、跨租户与不存在父登记404、输入/查询覆盖400，切租户测试最终恢复。历史显式offset归一UTC、2000 emoji说明、未知实际恢复时间null都按真实契约验证。

恢复GET与原登记GET独立：人工确认之后，原登记原收据及原创建键重放仍不变，当前恢复不会被原创建回执重放重置。只读审计尾与资产不变。ready核验实际恢复结构/权限并明确不触发监控恢复/工单/SLA；live不探测/不迁移。V1–V14未改。下一步页面独立当前恢复显示和确认表单，原快照不能代替当前读取。实际控件DOM、监控回调、撤回/更正、正式处置/事件建单/SLA与全部128项仍未DONE。

见[API与回归证据](evidence/niko-manual-recovery-api-20261003.json)和[真实HTTP证据](evidence/niko-manual-recovery-api-http-20261003.json)。


## 2026-10-03 独立当前人工恢复面板与真实前端请求

手工详情已接独立当前人工恢复面板，原登记仍标为原始快照。当前读取严格区分version0/null无确认、version1不可变人类证据；验证租户/父登记/固定来源、原文UTF8 SHA256、毫秒时间及未知恢复时间，不把监控NORMAL、正式处置或工单/SLA变更当成有效确认。说明保留原文，实际恢复时间可留空null，不默认当前时间；人工核验复选框初始false且提交处理必须明确勾选。已确认后不能覆盖或创建第二份。

未知POST清除旧current显示并冻结原文/时间/父登记/版本/键，阻止换事件或改文重发。只读发现已有确认仍不能代替原请求回执；必须原键重试核验。成功回执在后续GET失败时保留，GET失败不能显示“无确认”，曾读到的不可变确认也不能在失败后降回无记录。收起保留输入与原请求，路由/主动切租户退出守卫、beforeunload提醒已接线，强制失效清空受保护上下文。

20新增Web共474、类型构建/39契约通过。实际manualRecoveryWorkspace+ApiClient 32HTTP首遍通过，4前端POST尝试=2份本方确认，另1独立管理员确认，合计3确认/3成功审计；5独立准备写为1资产、3原登记与1管理员确认。丢成功POST后只读发现version1仍保持原请求未知，原键重放后再丢GET仍保留已确认回执；另一人先确认造成实际409，无自动重试，刷新显示其证据且阻止再写。2000 emoji说明/历史offset、未知时间null、客户读取403且无POST、上下文重置丢弃挂起GET、原登记/资产与只读审计尾不变均实测。

丢弃/挂起已完成响应不等于物理断网，复选框/抽屉/路由/移动控件仍只有源码与单测接线证据，DOM/390px依旧B03。API0.20/V1–V14不变；实际监控回调、撤回/更正、正式事件处置/工单闭环/SLA和完整128项继续未DONE。后续继续故障持久性与其他不依赖外部阻塞的合同范围。

见[人工恢复面板回归](evidence/niko-manual-recovery-panel-web-20261003.json)与[实际模块HTTP](evidence/niko-manual-recovery-workspace-http-20261003.json)。


## 2026-10-03 V13/V14已提交数据的真实API崩溃恢复

新增独立显式--verify-owned-v14-api-crash场景，先检查本轮证据路径不存在，保留此前11表与V10的14表证据。必须核验本次父进程直接启动的API进程、当前用户、精确Java/JAR命令、固定只读工件SHA256和回环8080监听，再SIGKILL。真实wait退出137、连接ECONNREFUSED已验证；用同一固定工件重启，PG和Keycloak在本次API故障期间未重启。

新身份登录前，比对20张明确白名单业务表的同快照只读数量/等值SHA256，全部不变；范围为原14表加客户评论、日历头/修订、手工登记、人工恢复确认和设计配置表，不等同整库或外部真实性证明。V1–V14前后独立只读schema check均执行0次迁移，未自动迁移。随后重新取得6个真实OIDC会话，故障前10/后12实际HTTP全部按API0.20契约验证。

两条合成手工原登记（其一已独立人工确认，其一尚无确认）在重启后均原样回读；原手工创建键仍返回原登记快照，原人工确认键仍返回原确认，原创建重放不重置当前恢复。登记与确认的原成功审计各仍1条/原ID，资产和未知实际恢复时间null保持。56Python进程/证据保护测试及39契约通过；Bash/Node语法检查通过。场景结束自动清理本次拥有组件，随后正常启动新的持续QA栈。

这是已提交后终止API，不是事务进行中被杀，不覆盖PG/WAL、Worker、OSS、浏览器/390px或原指定服务器故障验收。V1–V14和API0.20未改，其他合同阻塞仍保留，不把此场景标为所有恢复验收或128项完成。

见[真实V14 API崩溃证据](evidence/niko-api-v14-crash-recovery-20261003.json)。


## 2026-10-03 M5-19 SLA四时间点对照纯核心

新增SlaComplianceScenario，使用明确传入的冻结快照引用和四个实际/截止时间做合同固定关系比较：响应实际≤响应截止且恢复实际≤恢复截止才达标，等于边界可达标。缺失时间不填0、不填当前时间、不填4小时；缺快照不拿最新档位替代。某一已知维度确实超时，即使另一维度未知也已确定不达标，并保留缺失原因，不把未知伪装为已知。所有结果只属显式对照场景，effective=false/正式达成率null。

P1–P3且未获准排除为适用范围；P4不计入SLA适用分母。排除申请未批准或已拒绝仍在适用集合，不能靠申请排除隐藏超时；声明已批准还必须有显式引用，否则适用性未知。引用仅是本场景输入，不证明真实服务经理/客户已批准，未来接真实数据时须单独核验授权与证据。未知优先级/排除状态、未知完整样本或未定时间判定不会得到虚假比率；零适用分母不输出0%或100%。对照比率仅返回精确分子/分母，不选择未定财务舍入或Q26补偿公式，补偿金额始终null。

12新增纯规则及完整根818=805执行（含256既有真实PG回归）+13历史fixture跳过、6MinIO排除，39契约通过。10条合成输入/3超时得7/10；重复ID不能重复计数，原输入与结果不可变，新版本输入不改变旧结果。没有硬编码M5-20可配置时限或租户档位，没有调用当前人工恢复记录去填SLA实际时间。API0.20/V1–V14未改；尚无该模块公开接口/页面、真实SLA业务来源或正式达成率验收，不把既有PG回归当SLA联调。M5-19金额/正式月报与128项整体未DONE。

见[SLA对照说明](sla-compliance-scenarios.md)。


## 2026-10-03 补充维保V15结构切片

独立V15执行1次，重复/只读/旧目标0次；新增可空起始/供应商及明确声明标记，旧默认写入在历史已声明后失败关闭，原列/回执/审计20表投影指纹不变。10新真实PG结构、17迁移策略、60Python保护、根935（922执行含283真实PG）/49契约通过；结构测试回滚后补充声明0行。V1–V15冻结，下一迁移V16+。仓储/HTTP兼容/UI、供应商核验/送达/DOM仍未完成，不标M3-10或128项DONE。见[结构与边界](manual-warranty-details.md)。


## 2026-10-03 维保资料仓储切片

12新真实PG+5纯规则、根952（939执行含295真实PG）/49契约通过。旧基础请求保留补充字段，显式清空保留声明；原回执、before快照、CAS并发/当前授权/异常全回滚有证。新公开维保HTTP/UI未执行，供应商/覆盖未核验、通知和完整M3-10/128项未DONE。V1–V15冻结不变。见[维护边界](manual-warranty-details.md)。


## 2026-10-03 独立维保API0.26

10控制器/根962（949执行含295真实PG）/51契约/554Web/类型构建和97真实HTTP通过；1合成资产11修订11维护审计。旧体保留补充字段/显式清空/旧新原回执/当前资源重放授权/两角色混合并发200+409/10+1历史分页有证。旧基础DTO不增列，供应商及覆盖未核验；页面/DOM、隐患送达导出仍待。V1–V15不改、不自动迁移，M3-10与128项非DONE。见[维护说明](manual-warranty-details.md)。

## 2026-10-03 Windows 指定库 V15 接续

本地已补齐独立迁移脚本的V7–V15功能开关，默认只读，校验冻结源码/构建资源、进程停止及显式前置版本。指定PG17.9项目库由V6逐功能升级到V15，各1次/重复0次/最终check0；46项隔离保护通过并接入CI。原库恢复副本仅保存在Git忽略目录，未做恢复演练。API0.26不变、冻结SQL未改；这是环境接续和结构证据，完整HTTP/Worker/UI另行记录，128项未DONE。见[本地接续说明](local-schema-continuation.md)。


## 2026-10-03 维保维护页面切片

20新Web共574/51契约/类型构建、实际模块54HTTP（5写尝试/16修订16维护审计）通过；只读刷新不重发、收起不丢未知写入、旧回执不覆盖新当前、显式note/版本/15+1分页有证。控件/390px/导航仅源码/单测，DOM B03未解除；供应商/覆盖未核验，隐患/送达/季度导出继续。API0.26/V1–V15不改，M3-10/128项非DONE。见[维护页面边界](manual-warranty-details.md)。

## 2026-10-03 本地完整组件与导入恢复

原指定PG/MinIO后端最终955项实际执行通过（295PG、6MinIO），13历史fixture跳过；首次时区断言失败已独立修复并续跑剩余模块，51契约通过。六真实OIDC身份与97维保HTTP通过。修复Windows就绪1秒误判及系统conhost阻断安全停止；14归属保护、真实待执行导入任务API/Worker重启和存储断链RETRY_WAIT自然恢复已验。这里只恢复本工程本地进程，原服务器组件未重启。API0.26/V1–V15不变，128项仍未DONE。见[本地运行验证](local-runtime-validation-20261003.md)。

## 2026-10-03 Proxy缓冲纯核心

8纯测试/根970（957执行含295既有PG）/51契约通过。四个显式乘数、未知输出null、一次DECIMAL128舍入/bytes单位；无实际Proxy、断网/接管或PoC证据，不加采购默认。容量估算API/UI和实测基准继续，M4-15/M10-7及128项非DONE。见[计算边界](capacity-estimate-core.md)。


## 2026-10-03 显式容量估算API0.27

12控制器/根982（969执行含295既有PG）/53契约/574Web/类型构建、72真实HTTP通过。只读padmin数学入口含九输出与Proxy，十进制字符串保精度、未知不补默认，成功只读审计不变；capacity:estimate目录21行实际核对，无角色绑定/场景/业务写入。测量、批准池上限和PoC仍未知/NOT_RUN，API UI和真实组件压测继续，M10-7/128项非DONE。见[估算边界](capacity-estimate-core.md)。

## 2026-10-03 本地浏览器补齐

修复手工资料折叠控件注册和390px步骤条优先级/末步骤内联宽度。原服务器真实OIDC/MinIO/PG/独立解析器下，模板浏览器下载、XLSX隐藏工作表/第2表头、重复列明确映射并回读、搜索导航、桌面和手机维保资料保存/null清空、viewer入口权限已验；574Web/类型构建通过。其他六页面仅入口冒烟，不能当全部控件通过。详见[本地浏览器](local-browser-validation-20261003.md)，128项未DONE。

## 2026-10-03 最新API0.27原服务器复验

已合并云端9dbf576容量/Proxy接口，最新API/两个Worker在原指定组件上启动；六新OIDC、46容量/能力定向测试、53契约及72真实HTTP通过，21项目录实读，无业务/场景写入。原955项组件回归有自身基线，不与定向重叠计数累加。现场PoC/实际Proxy及客户规则仍待。见[本地交付](local-delivery-20261003.md)。


## 2026-10-03 容量与缓冲页面切片

18新Web共592/53契约/类型构建、实际模块18HTTP（15只读POST/0业务写入）通过。8空白输入、明确系数、BigInt独立响应核验/十进制输出，编辑清旧/迟到丢弃/权限与卸载/真实403有证；PoC未执行、场景未保存、增长曲线和基准关联待，DOM/390px仍未验。API0.27/V1–V15不改，M10-7/128项非DONE。见[估算页面边界](capacity-estimate-core.md)。


## 2026-10-03 容量场景草稿纯核心

9纯规则/根991（978执行含295既有PG）/53契约通过。有界显式计算与固定版本快照、明确名称说明、可空基准引用仅为未验真引用；DRAFT不生效，未知不补值。真实场景结构/持久化/API/UI后续，当前不宣称已保存，M10-7/128项非DONE。见[草稿边界](capacity-estimate-core.md)。


## 2026-10-03 容量场景V16结构切片

仅niko项目库独立V16执行1次、重复/只读/旧目标0次，V1–V16冻结。12真实PG结构/18迁移策略/63Python、根1004（991执行含307PG）/53契约通过；20表旧值指纹不变、测试回滚后场景表0行。Windows显式入口已补，49PowerShell保护待CI，本机未执行；原服务器V16未验。仓储/公开保存/UI与完整M10-7/128项非DONE。见[结构边界](capacity-estimate-core.md)。


## 2026-10-03 容量场景仓储切片

15新真实PG+5纯codec/JSON规则、根1024（1011执行含322PG）/53契约通过。当前padmin授权/跨租户404、创建基准0→不可变v1、同键竞态只1行1审计、精确字符串输入/重算快照/回执、全回滚和分页有证。V16 CI另确认49PowerShell保护通过；原服务器V16未验。公开保存API/UI/真实基准/PoC后续，M10-7/128项非DONE。见[仓储边界](capacity-estimate-core.md)。


## 2026-10-03 容量方案API0.28

POST /api/v1/capacity/scenarios 明确创建不可变DRAFT（expected_version=0→1），GET列表/详情真实PG回读、每次验证输入摘要和固定公式重算结果。仅当前租户启用padmin，CSRF、幂等和审计；原收据返回前重新校验当前授权。严格五字段 label/note/calculation/benchmark_reference/expected_version，引用显式null或未核验资料标识；全部数量/小数输入输出为十进制字符串。嵌套字段顺序不改请求摘要，原文和数值字符串保持有意义。新键可建同标签独立方案，不提供覆盖、删除或生效操作。

列表标签ICU小写字面匹配，不搜索说明；10/15/25/50/100分页，paired before_recorded_at/id；每页一致快照，不是冻结导出。跨租户详情404；unknown/duplicate query拒绝。就绪纳入V16真实schema/ACL检查，API/Worker仍不自动迁移。模型/基准/实测/PoC均未核验，保存不改变这些门禁。原容量估算接口语义未变。

11新增控制器+1就绪测试，根1036=1023执行（322真实PG）+13历史fixture跳过，6MinIO排除；55契约、592Web、类型/构建通过。停止旧自有API0.27并从新构建启动0.28，六个新真实OIDC会话；71真实HTTP覆盖原键/嵌套顺序重放、改体409、并发同键、11个独立合成方案、10+1游标、权限/CSRF/跨租户404、数值精度/未知/引用门禁、只读审计尾不变。23项代码声明真实GET摘要验证，不改人员绑定。源码接口测试不是浏览器DOM或客户验收。

[完整回归](evidence/niko-capacity-scenario-api-20261003.json)；[真实HTTP](evidence/niko-capacity-scenario-api-http-20261003.json)。下一步保存页面/原请求不确定结果恢复与真实前端模块；原指定服务器V16未验、OSS、实际基准、增长曲线和长期PoC仍待补，128项不标DONE。


### 2026-10-03 容量方案前端状态机（页面尚未接入）

实际reader严格核对租户/原文/版本/未核验门禁，复用BigInt有理数校验固定公式输出，输入无浮点转换。JSONB摘要仅检查格式，由服务端真实仓储校验一致性，不作为签名或客户端独立摘要证明。列表15条keyset顺序/重复ID/cursor边界验证，读取失败清旧显示而保留已确认原收据。

首次提交生成固定原文/幂等键；响应丢失保留uncertain，只读刷新不解决原请求，不自动重发。原请求重试即使收到真实403，仍不能证明先前未提交，必须继续保留原不确定状态/阻止新建。新成功收据核对当前租户/创建人和全部原始输入；上下文强制失效或卸载清理并拒绝迟到回包；页面尚未注册，因此路由/离开guard还不能当已完成。

13新增Web共605、类型构建、55契约通过。最终19实际前端模块HTTP/9次POST尝试/5条合成方案；含真实成功响应丢弃/迟到、原键恢复、实际MSP403重试保护、重复点击抑制、只读审计尾不变。此前16HTTP/4条为独立早期批次，不叠加作新覆盖。API d247b91 CI37122927576 success已回读。

证据：[前端状态机](evidence/niko-capacity-scenario-client-20261003.json)、[最终真实HTTP](evidence/niko-capacity-scenario-client-http-20261003-retry-guard.json)。下一步直接接入页面和离开保护；无浏览器DOM/生产批准/PoC结论，V1–V16不变。


### 2026-10-03 容量方案页面接入

现有容量估算页新增按capability控制的“以当前输入准备保存方案”和“只读查看已保存方案”入口，不新增另一套估算公式。面板显式复制当前输入快照，主页面后续修改不会悄悄修改本次方案。独立名称/说明/可选未核验基准引用，空白和未启用组保留null；成功回执与只读列表/详情分开。15条分页/标签查询、原始输入/9结果/Proxy缓冲/文档引用和全部未核验门禁可读；低于1倍率警告保留。

未确认写入锁定新建、主动路由/租户切换和退出，beforeunload警告；抽屉收起不撤销已发送请求或丢失原键，查询不重发或确认未知写入。草稿替换/离开需明确确认，强制会话失效清空保护上下文。移动样式44px控件/单列长数字换行已编译，未当真实移动浏览器验收。

新增8测试，其中7项运行实际Vue SFC处理器（自定义内存renderer、stub控件、固定jsdom26.1.0），1项源码接线检查；完整Web613/55契约/类型构建通过。它们不是浏览器DOM、真实Element Plus控件、截图或网络联调。此前同一未改前端状态机的19实际HTTP证据继续有效，未冒充新的面板HTTP批次。模块3880444 CI37123480879 success已核验；证据[页面接入](evidence/niko-capacity-scenario-panel-20261003.json)。

原指定服务器V16、容量面板实际桌面/移动浏览器、增长曲线、真实基准/PoC仍未验，完整合同仍未DONE；继续不依赖这些阻塞的开发。


### 2026-10-03 显式主机数增长对照

现有容量页新增2至20个明确递增主机数的离散对照；不沿用原型500–20000档位或增长率，不猜未来日期/主机增长。只替换上方容量hosts，其他模型参数固定，Proxy不参与。顺序调用既有只读POST /capacity/estimates，逐点验证真实响应的BigInt算术；全部成功后展示，失败/编辑/取消/权限变化清除，绝不显示部分成功曲线或以本地计算冒充API。未知参数保留UNRESOLVED且不绘图，0主机是明确0。

历史量（不含余量）、趋势量（不含余量）、单节点总量（含余量）共用可视比例尺，非堆叠；旁边保留完整bytes十进制字符串。图宽使用BigInt计算至万分比，只影响绘制，不改变原始精度。原型增长图标题“含余量”与堆叠算式未含余量矛盾，已显式拆分三系列，不复制误导标签。无测量、采购、时间预测或PoC结论，不保存增长场景。

10新Web共623、55契约和类型构建通过；39实际前端HTTP/35只读估算POST/0业务写入，包括20点上限、0/超安全整数、未知系数、第二成功响应丢失不展示部分曲线、迟到首点不继续发请求、重复点击、真实MSP403、成功只读审计尾不变。此为真实接口与实际模块验证，尚无真实浏览器图形/移动可视验收。保存面板bc31963 CI37124086660 success已回读。证据：[增长对照](evidence/niko-capacity-growth-20261003.json)、[实际模块HTTP](evidence/niko-capacity-growth-client-http-20261003.json)。

M10-7现有计算/保存/读取/增长代码切片已补齐，原服务器V16、真实浏览器、实际基准与12,500NVPS×24小时/2倍峰值30分钟PoC仍未验，TC和128项不标DONE。下一步继续其余不依赖外部口径/组件的合同。


## 2026-10-03 原请求重试被拒绝保护修复

11既有writer新增55个4xx分支先复现再修复，修后634Web/55契约/构建通过；31实际前端HTTP验证工时创建/PATCH与配置提案原200丢失→实际403→保留uncertain→原键200。首次明确拒绝语义保持，其他上传路径继续审查。见[修复说明](uncertain-write-retry.md)，非全部128项或浏览器验收。


## 2026-10-03 上传与报障原请求保护补齐

导入来源/解析/映射、照片、附件和报障6入口原不确定重试保护补齐；5存储状态机修前均复现，25个4xx分支+4报障分类/接线测试后643Web/55契约/构建通过。6真实报障helper HTTP验证202丢失→403→同原键202→同登记v1；存储仍关闭，上传链路本补丁真实联调NOT_RUN，未用fixture冒充。见[说明](uncertain-write-retry.md)。


## 2026-10-03 13:23 工作区切换与恢复准备

原niko源码/工具链及QA15927不可访问，exec返回executor key changed；最新002a89d/CI37125698984 success已保留远端。新增同一private仓库的短期HEAD源码恢复归档：8项隔离测试校验只含普通tracked文件、排除private/生成物/历史、逐blob清单、archive SHA256和tree、拒绝覆盖/符号链接/超限。恢复尚待实际CI产物/下载校验，不当已恢复或新组件验收，见[B09](current-development-blockers.md)。



## 2026-10-03 工作区丢失后的源码恢复与新组件基线

13:23 executor切换后，原MOP工作区/工具链/测试进程不可访问。通过同private仓库短期CI artifact恢复993个tracked文件，逐blob/整tree/原commit09b5854完全相符；Git仅恢复shallow HEAD，不带历史私有配置/未跟踪文件。13张旧截图全部原hash恢复，B08物化限制解除；仅查看其中3张历史移动截图，不等于新容量页面或实时浏览器验收。恢复导出新增原HEAD commit bytes并校验SHA，避免GitAPI日期丢失原+0800头信息；仍不导出祖先历史或秘密路径。

用户13:40明确批准重新建立PG/Keycloak隔离测试账号/随机凭据。新增窄范围prepare-private.py --create-approved-database-identity-credentials，只创建PG/Keycloak/OIDC6类凭据和6个测试用户，不创建MinIO/照片/导入签名凭据，不覆盖既有配置；2新增保护测试。凭据只在.local/niko-test/private，未进Git/提示/公共日志。

官方Node22.22.1/Maven3.9.9/Temurin17.0.20.1+1校验和恢复；PostgreSQL17.11官方Debian签名索引+3deb SHA256核验后仅解包工作区，Keycloak26.2.4官方发行SHA1核验。新PG/SCRAM/migrator-runtime分离、KC独立PG库/S256 PKCE/禁应用password grant，均回环；不是旧数据库备份恢复或原指定服务器操作。新库V1–V4显式执行4，V5–V16各独立执行1且立即重复0，最终只读check0；原SQL字节不改。API/Worker没有自动迁移。

新实例真实PG完整根1036=1023执行（322真实PG）+13历史fixture跳过，6MinIO排除；Web643/55契约/类型构建/Python79通过。另冷启动纯Java1042=701执行+341跳过（322PG+13历史+6存储），不和真实批次叠加。正常启动拥有的固定API JAR并验证回环监听，6个新OIDC授权码登录、34真实HTTP核对读写/原键重放/23能力/跨租户404/只读审计尾不变；4条合成业务记录不是旧数据。存储显式关闭，上传组件重测仍NOT_RUN。

[源码恢复](evidence/niko-workspace-recovery-20261003.json)、[截图物化](evidence/niko-repository-binary-materialization-recovered-20261003.json)、[新组件](evidence/niko-rebuilt-components-20261003.json)、[新HTTP](evidence/niko-rebuilt-environment-http-20261003.json)。B09源码与新PG/OIDC/API可用性已解决，但旧临时数据库未恢复；B01原服务器入口/私有连接、B02 OSS、B03新环境浏览器验收、客户口径/监控组件仍分别保留，继续独立开发。


2026-10-03 14:20：新环境实际浏览器仍受B03限制（官方下载返回错误HTML，专属云Chrome打开回环页ERR_BLOCKED_BY_CLIENT）；Vite直接HTTP200不等于UI验收。5dfd7cd双CI及含原HEAD bytes的恢复artifact完整验证，继续真实PG/API和其他独立功能。见niko-rebuilt-browser-blocker与niko-rebuilt-source-ci证据。


## 2026-10-03 显式经营指标核心

新增样本中位数与活跃租户/FTE纯算术，14测试通过，完整根1050=1037执行含既有322真实PG+13历史跳过，6MinIO排除；55契约/生成一致性通过。空/未知/0样本和0分母分开，精确大数与最终DECIMAL128，不选Q23/Q27/Q30业务口径。API仍0.28，没有新数据源/接口/页面或正式指标结论，M10-5/10-10/10-12仍未DONE。见[边界与证据](explicit-operational-metrics.md)。


## 2026-10-03 显式经营对照API0.29

新增只读POST、padmin当前授权/CSRF/有界规范字符串输入，样本中位数/FTE精确返回且Q23/Q27/Q30门禁保持。11控制器+57契约，根1061报告/1048执行含322既有真实PG、643Web/构建通过；64新环境真实HTTP含10000样本、角色/租户/目录24项、成功只读审计尾不变。首轮租户切换测试缺幂等键400，修脚本后完整通过；无业务写入、迁移或新凭据。核心eaa7d48 CI37130724964 success；本API无页面/DOM/真实经营数据或合同DONE结论，见[对照边界](explicit-operational-metrics.md)。


## 2026-10-03 经营样本与人力对照页面

新增独立只读页面/双导航与显式样本模式，BigInt复核服务端完整结果，未知/0不混用，编辑/错误/权限变化/迟到均清旧且不自动请求。15新增Web共658、57契约和类型构建通过；实际模块23HTTP/20只读POST尝试/0业务写入，含极值、丢成功响应/迟到和真实MSP403、只读审计尾不变。API20ca9c8 CI37131675736 success已回读；DOM受B03限制，正式经营数据与合同验收仍待。见[实现边界](explicit-operational-metrics.md)。


## 2026-10-03 客户联系人纯核心

M2-9联系人三角色/P级偏好/明确静音窗口与最后启用决策人计数守卫已完成14纯测试，完整根1075=1062执行含既有322真实PG+13历史跳过，6MinIO排除。没有创建联系人库表/接口/绑定或派发；并发保护仍需实际PG串行化验证，通知Q14/渠道未完成。V1–V16不变，API0.29不变；经营页面54b578c CI37132364878 success已回读，见[联系人边界](customer-contacts.md)。


## 2026-10-03 V17联系人结构与最后决策人约束

V17新增头/不可变历史/租户guard，独立执行1、重复0/check0，21张已有业务表全列不变。12真实PG结构（10迁移角色回滚隔离样本+2实际runtime ACL/触发器），根1088=1075执行含334PG、19迁移策略/82Python/57契约通过，新3表回滚后0行；双连接竞争/仓储/API/页面/送达待，未改账号或自动迁移。V1–V17冻结、后续V18+；纯核心afd078d CI37132914339 success。见[结构边界](customer-contacts.md)。


## 2026-10-03 联系人真实仓储与并发

创建/修改/归档/明确恢复、同租户管理员授权、CAS/原收据/审计和只读分页历史已接入；17新真实PG（均未跳过），两连接同时归档仅有两名决策人实际200/409剩1，同版本修改200/409。根1105=1092执行含351PG、57契约通过；仅既有测试身份/租户，身份及成员表指纹不变，自建合成记录归档保历史，未新增授权/账号。V1–V17不改；90d23d0 CI37134157209 success且52PowerShell保护已验。公开API仍0.29、联系人HTTP/UI/导入/通知待，见[仓储证据](customer-contacts.md)。


## 2026-10-03 联系人API0.30

新增当前租户管理员/平台管理员创建、维护、归档、完整恢复和分页历史接口；14控制器+1就绪，根1120=1107执行含351PG/61契约/658Web与构建通过。新固定JAR/6OIDC后136真实HTTP，含两组200/409并发、最后决策人、原键历史与当前分离、11条分页、403/404及只读审计不变。14新合成联系人均归档保历史，自有合成基线已恢复；目录26项不改人员绑定。仓储eabfa2c CI37135557637 success，V1–V17不动，联系人UI/导入/通知仍待，见[API边界](customer-contacts.md)。


## 2026-10-03 联系人实际前端状态模块

新增严格DTO/分页历史reader与四动作原请求writer，原unknown重试4xx不降级；读取不确认写入，原收据与当前事实分开，重复提交/迟到/上下文清理保护已验。22新Web共680/61契约/类型构建；实际模块99HTTP/62变更尝试/18合成联系人（全部归档），4原成功响应丢失后实际MSP403再原键确认，列表15+1/历史15+2及只读审计不变。API0e63b52 CI37137805250 success已回读；页面、主动离开控件、DOM/导入/通知未验，见[模块边界](customer-contacts.md)。


## 2026-10-03 联系人页面与离开保护

联系人列表/详情/历史、新建/修改/确认归档/明确恢复及双导航已接入；未知原请求阻止主动离开，脏输入需确认，强制失效清理与迟到提示隔离，保留CAS基线和原收据/当前事实分离。11新测试（10 Vue事件处理器+1接线）共691Web、61契约、类型构建通过；内存renderer不等于DOM，B03桌面/390px实际页面验收待。底层e8687c0 CI37139475036 success与99HTTP独立，不新增页面HTTP声明。API0.30/V1–V17不变，批量导入/通知/Q14及完整M2-9仍待，见[页面边界](customer-contacts.md)。


## 2026-10-03 联系人导入格式预览核心

新增有界1–100行纯格式预览、逐行错误/精确相同行警告，保留原文及未知含义，不自动去重/写入，canImport恒false。12新纯测试，根1132=1119执行含351既有真实PG+13历史跳过、6MinIO排除；61契约/生成一致性通过。无导入HTTP/文件解析/异步作业/页面，API0.30/V1–V17不变；页面9703faf CI37141262397 success已验。见[边界及证据](customer-contacts.md)。


## 2026-10-03 API0.31联系人导入格式预览

只读POST接入当前管理员/CSRF，1–100原始行逐行诊断，严格根/JSON边界，EXPLICIT_INPUT无联系人id/version，固定不可导入。12控制器、根1144报告/1131执行含351PG，64契约/691Web/类型构建通过；新固定API/6OIDC后51真实HTTP，39条联系人DTO快照及只读审计尾不变，无联系人新增/作业/身份变更。纯核心caa0247 CI37141522285 success，V1–V17不动；导入页面/文件解析/异步批量写入和通知待，见[完整边界](customer-contacts.md)。


## 2026-10-03 联系人导入格式预览页面

空输入只读JSON预览页、严格原始JSON边界/重复键、逐行独立核对与未保存文本离开保护已接入。23新Web（16解析状态、6 Vue处理器、1接线）共714/64契约/类型构建通过；实际编译模块20HTTP/17只读POST，100行及未知含义、丢包/迟到/实际MSP403、审计尾不变，无业务写入。API7c5c417 CI37141975725 success；API0.31/V1–V17不改，B03实际DOM/390px仍待，文件解析/异步导入/通知未完成，见[边界](customer-contacts.md)。


2026-10-03 18:00：联系人原始JSON预览先检数值词法，阻止浮点舍入/指数下溢后伪装整数；旧编译模块复现2例，新模块5类拒绝和2真实HTTP验证通过。714Web/64契约/类型构建通过，API0.31/V1–V17不变。657c316 CI37142471886 success已验；没有新增业务写入或批量导入完成结论，见联系人边界。


## 2026-10-03 联系人内部原子批次

新增受保护幂等事务内1–100条create-only批次，保序不合并、共同租户锁/现有最后决策人规则，savepoint防外层捕获异常后半批提交。8新真实PG使仓储25项零跳过；根1152=1139执行含359PG，64契约通过。第二条INSERT前真实SELECT 1/0验证半批/审计回滚，并发同键仅建2目标行且同收据；自建样本归档，身份不变。API0.31/V1–V17不动，无202导入受理/Worker/新HTTP，can_import仍false，见[仓储边界](customer-contacts.md)。


## 2026-10-03 导入作业纯状态机

新增严格队列/租约/终态形态，显式毫秒时间与技术租期、过期重领fence、旧执行者/起租前/到期提交拒绝，成功必须整批，重试仅明确未来时间。16纯测试、根1168=1155执行含359既有PG，64契约通过；初始测试var声明编译失败已修。批次ffa7cdc CI37143247462 success；无耐久队列/实际领取/重启/HTTP，API0.31/V1–V17不变，见[边界](customer-contacts.md)。


## 2026-10-03 联系人耐久导入结构V18/V19

V18队列/不可变行结果结构显式1/重复0/check0且26表不变，但13实测6项暴露BEFORE生成hash比较错误。保留V18，新增V19基字段比较修正独立1/重复0/check0且28表不变；最终13真实runtime PG零跳过通过、所有样本回滚新表0行。根1183=1170执行含372PG、21策略87Python64契约通过，PowerShell58项待CI；API0.31正确ready端点200。V1–V19冻结/后续V20+，还没有202受理或Worker/重启验收，见[真实故障与修复](customer-contacts.md)。


## 2026-10-03 联系人导入耐久仓储

受理不可变字节/原权限版本，creator-only回读、PG专用队列领取/fence、当前授权后整批处理/结果/审计/幂等收据同事务已实现。18新真实PG+8纯codec，根1209=1196执行含390PG、64契约通过；实际SQL中途失败整批回滚并等待5秒退避恢复，同目标领取/同claim并发只执行一次，真实租约到期旧fence无效，100行通过。自建作业终态保留/联系人归档，末次非终态及未归档导入记录0；无新身份/删除。结构8259d7 CI37145624801与58项PowerShell已验；API0.31/V1–V19不动，公开202/独立Worker/进程重启仍待，见[边界](customer-contacts.md)。


## 2026-10-03 联系人独立Worker及真实重启

独立contact-imports JAR、精确remote_test/显式flag/受限PG验证、仅专用队列/无存储或身份秘密已接入。15Java Worker单元+7Python artifact/PID保护，根1224=1211执行含390PG、94Python64契约通过。实际3次JVM：正常3条；真实PG guard锁观察在途等待后仅杀自有Worker(-9)，零部分行；同JAR重启到期fence2完整3条，原受理收据仍PENDING。6自建联系人归档，5无关队列/身份表指纹不变，API/PG/KC不停止，V1–V19/API0.31不变。公开202/导入页面未接，见[实际进程证据](customer-contacts.md)。


## 2026-10-03 联系人公开耐久导入API0.32

严格CREATE_ONLY整批202、显式重复行确认、creator+tenant回读/键集分页、原回执与当前Worker状态分离、V19结构ready门禁已接。12控制器+1ready+2真实PG新增，根1239=1226执行含392PG、67契约/714Web/typecheck/build通过。388实际HTTP+独立Worker，19作业119合成联系人均v1读取后归档，100行整批/幂等/授权404/原回执/只读审计已验；5无关表指纹不变，Worker停止，无新凭据/迁移/存储/通知。页面提交/进度/恢复与DOM待，M2-9不标DONE，见[边界与证据](customer-contacts.md)。


## 2026-10-03 联系人导入前端状态模块

完整CREATE_ONLY输入/重复行确认、严格作业/结果/分页校验、原幂等请求恢复、回执与当前状态分离、强制上下文清除已接。20新测试/734Web构建/67契约通过；实际模块72HTTP与Worker6批7条均归档，丢失202后MSP403不能消除未知、原键恢复无重复，4实际响应延迟防旧上下文污染；五无关表不变，Worker停止。API0.32/V1–V19不变，页面与真实DOM仍待，见[证据](customer-contacts.md)。


## 2026-10-03 联系人批量新建页面

/contact-imports及双导航/单条页/格式页链接已接，显式预览与确认受理、重复行确认、未知请求离开保护、原回执/当前结果分离、creator分页、可选2秒GET进度且终态/失败停轮询。13 SFC处理器+1源检查，全748Web构建/67契约通过；真实DOM/390px仍B03，未重算前模块72HTTP，通知与CSV/XLSX未完成。见[页面边界](customer-contacts.md)。


## 2026-10-03 Worker角色匹配回归

实际三JAR复现旧资产通配符同时选中contact-imports，已改启动/停止共享三族互斥判别并补直接联系人入口迁移拒绝；实际资产启用flag混入也已补拒绝。新增1 Java/根1240报告1227执行含392PG通过，固定JVM混合flags真实退出1且未读秘密/连库。PowerShell21项待新增Windows CI实际运行，未冒充本地或原服务器执行，见[回归边界](customer-contacts.md)。


## 2026-10-03 Windows可选联系人Worker启动器

新增显式WithContactImportWorker、V19 ready/JAR门禁、最小已有PG环境、每次独立日志和精确已登记停止归属；默认不启用、不迁移/建凭据。27隔离PowerShell用例待本次CI，实际Windows/原服务器启动NOT_RUN。前角色修复7587fa8 CI37152864074/Windows21项已实证通过，真实进程操作0，见docs/contact-import-runtime.md。


## 2026-10-03 D07成员资格纯守卫补正

先增2回归复现非正权限版本可能放行、空租户未先401，再修正requireCustomer成员完整性；16纯规则全通过，根1242=1229执行含392既有PG/13skip/6MinIO排除。正式客户关单/重开仍未接，不把此守卫当资格授权。API0.32/V1–V19不变。另回读c33cc11 CI37153152496 success，Windows job111290992448实际27隔离检查/真实进程操作0，补齐启动器待CI，仍无原Windows启动或迁移证明。


## 2026-10-03 D06/Q06阈值谓词纯诊断

显式高/低方向、GT/GE/LT/LE、BigDecimal单位一致/严格子集/恢复交集/非负持续时间检查已实现。未知null、已知冲突不自动修正；数学CONSISTENT仍不可发布，物理范围/时序/客户批准均未核验。20纯测试含一项1728分段向量、根1262=1249执行含392既有PG、67契约通过；未接新API/监控/仓储/页面，见docs/threshold-condition-diagnostics.md。


## 2026-10-03 API0.33显式阈值诊断

仅已有平台配置读取权限的严格只读POST/CSRF入口已接，未知null/冲突不修正/数学通过不发布。10控制器、根1272报告1259执行含392既有PG、70契约748Web构建通过；75真实HTTP通过，375条配置DTO摘要/26能力目录/六身份权限不变、3单独只读重复审计尾不变。无监控请求/规则保存/发布/新身份/迁移；前端与DOM待，见[边界](threshold-condition-diagnostics.md)。


## 2026-10-03 阈值诊断前端状态模块

严格原始JSON与显式输入、BigInt精确关系/完整响应核对、错误清旧结果/取消上下文已接，18新测试/766Web构建70契约通过。实际模块34HTTP/29只读POST/21向量与Java一致，1真实响应丢弃4挂起、MSP403、375登记摘要及只读审计不变已验；无新页面/DOM/监控发布。构建有>500kB chunk建议，非浏览器性能证明，见[边界](threshold-condition-diagnostics.md)。


## 2026-10-03 阈值候选页面

/threshold-diagnostics及双导航已接，默认空输入、显式只读诊断、原比较符/小数/单位回显、冲突/未定和固定不可发布边界、离开确认/强制清除已接。9处理器+1接线新增测试，全776Web/构建70契约通过；首轮执行未获汇总，2并发完整复跑取证，不冒充初轮通过。DOM/390px仍B03，>500kB打包建议不等于性能实测，见docs/threshold-condition-diagnostics.md。


## 2026-10-03 Zabbix版本协议纯核心

组件/新账号仍等待单次许可，先实现固定apiinfo.version空数组/无auth请求与严格有界RPC响应、回环URL语法校验；版本字符串不代表监控/Agent/权限/租户映射就绪。15纯单元、根1287=1274执行含392既有PG与70契约通过；无HTTP传输/安装/新库账号/采集/API改变/迁移。见docs/monitoring-version-protocol.md。

## 2026-10-03 无凭据版本传输器

限回环显式版本探测传输代码已完成，禁止代理/重定向/Cookie/auth，整毫秒超时和流式16KiB限制、失败脱敏，未自动接入API/就绪/采集。20隔离单元、根1307=1294执行含392既有PG与70契约通过；无真实Zabbix请求/账号/安装，实际网络及取消语义未验收，D06未DONE。前纯协议ae695121 CI37157472477 success已核。见docs/monitoring-version-protocol.md。

## 2026-10-03 备份run/attempt纯事实模型

初次+两重试/10分钟、未知结果保持在途并阻止盲重试、成功版本引用同租户设备与时间边界、不可变attempt快照/逻辑版本已实现。20纯单元、根1327=1314执行含392既有PG与70契约通过；Q22口径null/自动P3 CONFIG_PENDING，无备份真实SSH/存储/仓储/API/调度，见docs/backup-run-state.md。

## 2026-10-03 备份差异安全核心

复用java-diff-utils4.17线性空间算法，严格同租户设备/版本、原换行空白保留、全正文隐藏且秘密值变化不漏报、协作预算失败返回未知。21纯测试含200对向量，坐标溢出先红后绿；根1348=1335执行含392既有PG、70契约及许可资源JAR回读通过。实际设备/对象/权限API/UI/P3待，见docs/backup-redacted-diff.md。

## 2026-10-03 时间回读校验缺陷修复

报障operation/配置诊断/手工资产资料改用严格UTC日历往返与BigInt纳秒顺序，5缺陷回归先红；16新增纳入792Web/typecheck/build/70契约通过。实际模块43只读HTTP，60时间/10亚毫秒原文保留、415登记本轮前后摘要相同，无业务写/DOM/迁移。见docs/strict-utc-readback.md。

## 2026-10-03 原请求展示快照别名修复

四个旧模块getter/回调浅复制允许外部改写内部attempt，8回归先红后绿；统一业务快照深复制，Error原型保留。12新增纳入804Web构建70契约；16真实HTTP中1合成提案实际200丢弃后原path/body/key重放只创建1次，随后撤回，另三模块只读快照隔离通过。无生效配置/迁移/DOM，见docs/workspace-snapshot-isolation.md。

## 2026-10-03 另外七个写模块快照修复

内部备注/客户评论/工时/日历/人工恢复/维保/手工事件原attempt共享问题，14回归先红后绿；全818Web构建70契约通过，原path/method/key/body不受展示副本污染。此轮没有新HTTP/业务写/组件/DOM，前16HTTP不重计，见docs/workspace-snapshot-isolation.md。

## 2026-10-03 只读快照与审计游标隔离

七个只读模块业务getter/回调和配置对照输入源数组已脱离内部已验快照；15缺陷回归先红后绿，18新增纳入836Web构建70契约。16真实GET覆盖已有列表/详情/对照/operation及末20条局部审计链VALID，无业务写/新样本，非全库或外部锚定，见docs/workspace-snapshot-isolation.md。

## 2026-10-03 导入原请求与事实只读ref

上传/解析/映射attempt及来源/解析/政策/File/格式/busy改为公开Vue深只读视图，明确selection/mappingDraft仍编辑；4回归先红后绿，全840Web构建70契约。实际生产Vue模块3GET仅验证政策与本地文件引用，0上传/存储/解析，OSS/新DOM仍未验，见docs/workspace-snapshot-isolation.md。

## 2026-10-03 表格按路由加载

ElTable/AppTable及11个列组件局部绑定，入口静态JS501639→424970 bytes（-15.28%），登录-15.20%，资产路由联合JS+927bytes（+0.14%），CSS不变；最终最大JS424970、原500kB警告自然消失。13编译器/结构新增纳入853Web构建70契约。仅静态产物，非DOM/首屏/FPS证明，见docs/web-table-loading-boundary.md。

## 2026-10-04 响应Date与样本年龄基础

新增GET-only requestWithClock，严格Date/no-store/无Age及单调时间观察，不改旧request写契约；年龄只估计、不补样本或判正常。22新增纳入875Web构建70契约，18真实HTTP含8时钟观察/5空样本保持NO_SAMPLE/只读POST/403/旧上下文丢弃，0业务写。无页面/采集/心跳/SLA/DOM完成声明，见docs/http-response-clock.md。

## 2026-10-04 资产详情采样/读取时间面板

详情接GET时钟和显式只读刷新，原样本/响应时间/估计年龄分开；可见性、bfcache、对象变化使旧估计失效，无样本停止本地计时，卸载清理。未来样本不能仅靠等待变有效的回归先红后绿；12新增纳入887Web构建70契约，含10jsdom组件模拟，非实际浏览器或新HTTP，见docs/http-response-clock.md。

## 2026-10-04 全128项追踪一致性索引

新增确定性身份/元数据/TC状态/本地链接存在性检查，17新增及111项Python检查通过；128项正式状态仍NOT_RUN，30行含局部进度说明不等于30项完成。CI绿色只代表索引一致，所有完成证明仍需逐项真实验收；无API/迁移/组件变更，见docs/contract-progress.md与niko-contract-progress-20261004.json。

## 2026-10-04 M8受理/送达与回执历史

24纯测试覆盖受理不计送达、重复/乱序、未知禁止盲重试、死信新请求保留去重键、迟到送达只计1、404/版本/资源边界；根1372报告1359执行含392既有PG、13历史跳过、6MinIO排除，70契约通过。无新通知/验签/P95/持久化/UI验证，Q14/Q18保持未确认，见docs/notification-receipt-state.md；M8-3非DONE。

## 2026-10-04 台账/分页/修订时间精度修复

6缺陷回归先红后绿，7新增纳入894Web构建/70契约；严格UTC避免台账日期归一，纳秒保留于资料成对游标/修订先后校验。真实7GET覆盖30资料/2页、15空采样、1原撤回修订，0业务写；本批实际资料无亚毫秒时间，边界仅隔离验证，初次session失败与刷新后通过分别留证。浏览器仍未验，见docs/strict-utc-readback.md及niko-remaining-utc-20261004.json。

## 2026-10-04 M2授权租户登记资源仓储

复用已有PG同快照校验当前及每目标padmin成员，keyset分页与三个真实登记计数；6纯/7新增PG，根1385报告1372执行含399PG/13跳过/6MinIO排除，70契约通过。无新租户/身份/授权/迁移/HTTP/UI，监控/配额仍未验证；见docs/tenant-inventory.md，M2-1非DONE。

## 2026-10-04 M2租户登记资源API0.34

GET-only精确字符串计数/null未知字段、当前及每目标padmin授权；15控制器/73契约、根1400报告1387执行399PG通过。自己的固定API新构建启动后35真实GET（17成功/13参数拒绝/1匿名/4角色拒绝），仅2现有租户无真实has_more真分支，0业务写/新身份/迁移。UI/监控/配额仍待，见docs/tenant-inventory.md，非完整验收。

## 2026-10-04 本地承接60个Dot提交与V19

从28fa9fe同步到3eb1d42（API0.34）；原指定服务器V16–V19明确独立执行各1次/重复0次/check0，冻结SQL未变。58项Windows迁移保护、27项进程归属保护、894Web/类型构建通过。该批尚不证明联系人Worker、浏览器或全部128项；真实组件和页面验证继续。[原库证据](evidence/local-schema-v19-20261004.json)。
## 2026-10-04 M2租户12列只读页面

padmin限定菜单/路由/上下文，列表与本次快照详情共用12字段，精确计数/null未知、keyset前后页、读取取消与快照隔离；33新增含8jsdom纳入927Web构建/73契约。生产读取器7真实GET/2既有租户及伪填本地角色实际403通过，0写；实际浏览器、配额色阶和完整验收待，见docs/tenant-inventory.md。

## 2026-10-04 M3台账16列XLSX编码

独立普通库复用POI5.5.1，15模板列已含到期日，仅追加维保状态；显式null/原文字符串、租户声明一致/完整性门禁、资源上限、固定生成时间与ZIP时间、不可变bytes/hash。17纯编码测试、根1417报告1404执行含399既有PG/13跳过/6MinIO排除、73契约通过。无新增数据库/API/实际导出授权/存储/下载/通知，M3-7非DONE，见docs/asset-ledger-export-encoding.md。

## 2026-10-04 M3已有资产快照导出投影

9纯投影+6真实PG读取至XLSX检查，初3错误因新测试mapper漏SNAKE_CASE，修正测试配置后根1432报告1419执行405PG/13跳过/6MinIO排除、73契约通过；同一真实行坏编解码503/正确读取有证。维保状态仍null，核心资料漂移拒绝，0新增样本/业务写；不含整份筛选/异步存储/链接/通知，见docs/asset-ledger-export-encoding.md，非DONE。

## 2026-10-04 本地实际DOM与短屏侧栏修复

原组件真实联系人四动作/手机历史、批量202丢失原请求恢复15项、容量增长和草稿保存、阈值冲突/离开保护、租户12列同快照详情、空采样只读刷新、最新XLSX只读状态全链路均已验。修复1366×768侧栏压缩和底部截断，927Web/类型构建通过。所测页面无文档水平溢出/运行错误/Vue警告；其他完整控件与物理设备仍待，128项未DONE。[范围与截图](local-browser-validation-20261004.md)。

## 2026-10-04 原组件完整回归与联系人恢复保留

原组件根Maven一次全过：1406报告、1393执行/13历史跳过，399真实PG与6MinIO；73契约、927Web/类型构建通过。新增联系人Worker实际Windows启动/五进程重启；修复旧恢复脚本遗漏原已启用联系人Worker，重启后原202回放不重复、新1行任务实际完成。同期Dot新增cc67711导出库已另跑32项（含6真实PG），不与原根计数混同，也不冒充完整导出接口。[运行证据](local-contact-validation-20261004.md)。

## 2026-10-04 M3异步导出命令与状态边界

31纯测试覆盖命令指纹/当前创建人范围版本/租约fence/未知存储/900秒窗口；准备后接管盲传回归先红后绿，接管强制核查并保留原编码。根1463报告1450执行含405既有PG/13跳过/6MinIO排除、73契约通过；无新增真实任务/迁移/Worker/存储/链接/通知，纯模型不当实际成功。见docs/asset-export-job-state.md，M3-7非DONE。

## 2026-10-04 导出命令规范恢复与持久化身份绑定准备

21纯编解码测试与31状态测试通过；必填null导致NPE回归先红后绿，原黄金指纹不变。严格UTF-8/长度/规范形式及不可变受理身份/hash绑定；当前成员仍须未来真实查询，摘要不当授权。根1484报告1471执行含405既有PG/13跳过/6MinIO排除、73契约通过。无新迁移/持久化任务/Worker/OSS/下载/通知；见docs/asset-export-job-state.md与独立编解码证据，M3-7非DONE。

## 2026-10-04 完整台账筛选的同事务导出快照

新增同连接readonly REPEATABLE READ筛选/成员版本角色/资料快照，保持主台账字面过滤和顺序，超过1000整体413不截断。13新增真实PG（含2筛选到XLSX）和7隔离边界通过；真实整租户4393行上限拒绝，首次元数据测试误用全量筛选失败已保留并改为既有资料名称范围。根1504报告1491执行含418PG/13跳过/6MinIO排除，73契约。无新API/迁移/作业/Worker/OSS/下载/通知，见docs/asset-export-snapshot.md，M3-7非DONE。

## 2026-10-04 V20异步导出结构独立执行

V20首次候选CASE语法失败完整回滚，只读确认仍V19/无新表函数，修正未应用资源后独立执行1次、重复0/只读0；28既有表等值指纹不变。V1–V20冻结，16新增PG结构回滚测试和2迁移策略通过，根1522报告1509执行含434PG/13跳过/6MinIO排除，73契约。无应用导出受理/Worker/OSS/下载/通知，原服务器V20未执行；见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 V20跨平台冻结清单遗漏修正

c40bf37的CI37175873116中Java/Web/Windows进程检查通过，但PowerShell迁移策略仍只列V1–V19，拒绝新V20源码集合。补齐V20清单、独立asset-exports选择及导出核验器新旧检查；保留只读默认与单功能执行，V1–V20 SQL字节不动。新增跨入口清单回归对原提交先红，113Python/73契约通过；niko无pwsh，PowerShell原生执行留给既有CI，不冒充本地通过。见docs/evidence/niko-export-migration-policy-20261004.json。

## 2026-10-04 导出仓储受理、原回执与范围读取

20新增真实PG覆盖QUEUED受理/审计/原响应同事务、同key并发唯一写、改body409、当前成员回放前检查、外租户/非创建人404及独立只读get/list；错误命令或审计失败整体回滚，DTO无原始bytes/对象地址。根1542报告1529执行含454PG/13跳过/6MinIO排除、73契约。尚无实际HTTP/Worker/OSS/下载/通知，见docs/asset-export-jobs.md，M3-7非DONE。此前V20脚本修复9b95a8b的CI37176283202全过，原生PowerShell67隔离检查通过且数据库操作0。

## 2026-10-04 导出排队任务正常停启与新JVM回放

受理提交a38c84a的CI全过。实际停止/重启仅本runner PG/KC/API，PG启动时间改变；26排队任务、0领取及原幂等/审计四表相同。新JVM读回26任务/回放26原收据，接受回调0、四表再比相同；随后6真实OIDC登录通过。首次不必要的Flyway历史runtime读取被拒，移除该探针且未提权。9新增纯门禁/122Python/73契约通过；不当Worker崩溃或OSS完成。见docs/asset-export-jobs.md。

## 2026-10-04 导出实际领取与租约竞争

独立export事务作用域、FOR UPDATE SKIP LOCKED、当前成员角色/版本共享锁复核后取PG时钟；租约/递增fence/历史/审计同事务，过期已准备任务强制存储核查。11新增PG+2隔离边界，根1555报告1542执行含465PG/13跳过/6MinIO排除、73契约通过。保留原收据，授权不符不造租约；合成错版本/测试bytes只证DB边界，未启动实际Worker/OSS/下载/通知。见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 导出准备bytes的持久化与精确恢复

内部loadWork/prepare按job/租户/创建人后锁定并复核当前成员、worker/fence/时刻；文件与描述只写一次，审计异常原子回滚；响应不明后相同结果不追加写入地恢复，不同内容409，新fence仍保留存储核查标记。12新增PG+7纯检查，根1574报告1561执行含477PG/13跳过/6MinIO排除、73契约通过。此为不透明测试bytes，真实XLSX处理器/Worker/OSS/下载仍待，V20不改。见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 真实台账筛选到XLSX及PG准备链路

处理器先检查Claim，再取完整有界筛选，重查当前租约/PG时钟后编码并原子保存实际XLSX；存在原文件则不再读源/编码。5新增实际PG链路、10纯指纹/阶段检查，根1589报告1576执行含482PG/13跳过/6MinIO排除，73契约。覆盖空表头、租约中途到期及审计异常回滚；独立Worker/HTTP/OSS/下载/通知仍待，见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 独立导出准备Worker实际进程与故障恢复

新独立模块只处理显式单job，不启动其他队列/Web/迁移/存储客户端。4个真实自有JVM验证：实际XLSX准备、源备注变化后保留原文件、新PG审计等待点SIGKILL(-9)无半文件、过期后fence2恢复；原收据QUEUEDv0保留，7无关表等值指纹不变，Worker全部结束。15Java/9新增Python门禁，根1604报告1591执行含482PG/13跳过/6MinIO排除，131Python/73契约通过。仍未发布或调用OSS/下载/通知，见docs/export-preparation-worker.md，M3-7非DONE。

## 2026-10-04 D07报障登记列表/详情防御性回读

完整响应/当前范围/客户本人/详情ID/未知null/连续历史/READY附件/纳秒排序验证及深拷贝接入TicketsPage；旧响应代次保护保持，异常清旧显示并显示未知计数，复位清评论目标。56新增前端/983全量Web、类型/构建、74契约通过。最终40实际GET覆盖6身份分页/详情/筛选/刷新、外租户及他人404，业务写0；照片0张、OSS和真实浏览器未验。API0.34不变，OpenAPI补消费者边界说明；M5-1仅部分进度，正式状态/5KPI/SLA及128正式验收不冒充完成，见docs/ticket-read-hardening.md。

## 2026-10-04 M9-7数据驱动巡检模板与执行单纯核心

设备类型/分组/检查项由数据定义，冻结完整模板与长度分隔SHA；第五设备类型无需改Java。显式三结果/备注/未核验证据引用，未填null、按模板排序、summary仅由答案自动计算；修改/清项生成新对象，原模板和执行单不变。20纯测试、根1624报告1611执行含482既有PG/13跳过/6MinIO排除、74契约通过。尚无巡检迁移/仓储/API/UI/季度汇总/发布；API0.34保持，Q23/Q24模板及发布门禁不解除，M9-7仅基础非DONE，见docs/inspection-templates.md。

## 2026-10-04 V21未发布巡检模板结构与真实PG

独立执行V21一次、重复0/只读0，30既有表等值指纹不变；V1–V21冻结。新模板头/修订/有序项、延迟完整性与Java同值UTF-8摘要、仅未批准状态、归档保原内容及恢复门禁；历史runtime不可改删。14实际runtime结构测试回滚后3新表0行，根1640报告1627执行含496PG/13跳过/6MinIO排除、25迁移策略/132Python/74契约通过。跨平台冻结清单/新核验器新旧保护同步；PowerShell原生运行待CI。尚无巡检仓储/API/UI/季度报告，原服务器未迁移，M9-7仍非DONE，见docs/inspection-templates.md。

## 2026-10-04 真实未发布巡检模板仓储

当前padmin/租户/权限版本保护在回放前复核；模板头锁与显式CAS，修订/项/审计/收据原子提交，旧收据与当前归档分开，读快照重新核验内容指纹。19真实PG含双连接同键唯一提交/同版本200与409、故障回滚、角色范围及只读通过；根1659报告1646执行含515PG/13跳过/6MinIO排除、74契约。75自建合成模板归档保历史；最终聚焦身份指纹不变，首次跨两轮全根的过宽窗口比对失败已据实保留（旧测试写合成身份/权限版本），不冒充全根零写。V21 CI与76项原生PowerShell检查全过。尚无巡检公开HTTP/UI/季度报告，M9-7非DONE，见docs/inspection-templates.md。

## 2026-10-04 API0.35未发布巡检模板与117真实HTTP

新增当前padmin模板创建/列表/详情/修改/历史/归档/恢复，完整有序定义、CAS、CSRF/幂等及重放前授权；新能力分类已同步，模板批准/发布/扫描保持未实现。18新增Java、根1677报告1664执行含515PG/13跳过/6MinIO排除、80契约/983Web/类型构建通过。初轮目录12错误及ready契约漏contact_imports均修复留证；最终117真实HTTP覆盖并发、角色/CSRF/租户404、原回执、10+1分页，18合成模板归档，28无关表等值；另1实际目录GET通过。V1–V21不动、原服务器未操作、页面/执行单/季度报告未完成，M9-7非DONE，见docs/inspection-templates.md。

## 2026-10-04 未发布巡检模板前端请求模块

严格范围/未知事实/有序项与WebCrypto内容指纹验证，固定原命令并保护异步散列前后上下文；普通GET不确认未知写，当前状态与原回执分离。59新增前端/1042Web/类型构建/80契约通过；99真实HTTP含四类提交成功响应受控丢弃、实际MSP403后原请求恢复、15+1列表/15+2历史、双击与迟到回读保护。18合成模板归档，成功只读审计尾不变；无物理网络故障或DOM验收，模板页面/执行单/季度报告仍待，M9-7非DONE，见docs/inspection-templates.md。

## 2026-10-04 未发布巡检模板维护页面

新增padmin/能力门禁的懒加载页和双导航，有序项编辑/null与空说明/重复key独立UI行、修订/归档恢复、冻结历史及原收据/当前状态分区。未知写/未保存输入离开保护、旧上下文确认阻断、409保输入不自动升级版本。17新SFC/jsdom DOM、1059Web/类型构建/80契约通过；现有Chromium保留沙箱重试仍EPERM、打开页0，真实桌面/移动验收B03仍NOT_RUN。未执行用户电脑或委派任务，继续独立执行单开发；M9-7仍非DONE，见docs/inspection-templates.md。

## 2026-10-04 巡检执行单内容指纹与自述时间核心

规范UTF-8绑定资产/租户/冻结模板/自述时间/有序答案；未知时间和缺项保持null/未填，不替代为记录时间或正常。15新增纯测试，根1692报告1679执行含515既有PG/13跳过/6MinIO排除、80契约通过；执行单表/仓储/API仍未实现，V1–V21/API0.35不变。原终端73514断连后平台不再识别，根请求日志缺失不计已执行；按原脚本恢复已有PG/KC/API，PG报告自动WAL恢复，40HTTP核对36既有模板版本/归档/摘要后完成回归。没有故障前全库即时摘要，不冒充全库等值或受控在途崩溃证据。见docs/inspection-worksheets.md。

## 2026-10-04 V22人工巡检执行单草稿结构

独立执行V22一次、重复/只读0，33旧表迁移前后完整行指纹等值；V1–V22冻结。捕获当前模板/资产名称，完整JSON答案和Java同值摘要，自动已填数量、未知时间null、归档保内容/显式恢复、历史不可改删；无证据文件绑定。18真实runtime结构回滚测试、27迁移策略、133Python、80契约通过，根1712报告1699执行含533真实PG/13跳过/6MinIO排除；两新表回归后0行。原生PowerShell待提交CI，执行单仓储/API/UI/季度报告尚未实现；M9-7完整验收仍NOT_RUN。见docs/inspection-worksheets.md及独立结构证据。

## 2026-10-04 人工巡检执行单真实仓储

新增租户内padmin人工草稿保存/读取/修改/历史/归档/恢复，创建捕获真实资产名称/当前模板，旧快照不受模板更新影响；写保护幂等/当前授权/CAS/审计原子化，PG实际时钟校验自述时间，未知null，证据绑定拒绝。3纯输入+21真实PG测试通过含双连接唯一提交与200/409、真实语句故障注入回滚；根1736报告1723执行含554PG/13跳过/6MinIO排除，80契约通过。聚焦身份指纹等值；50合成执行单归档保历史。先前V22 CI37194955848/85原生PowerShell策略通过；API0.35/冻结V22不变，执行单HTTP/UI/季度报告仍未实现，见docs/inspection-worksheets.md。

## 2026-10-04 本地Windows技术租期修正

指定PG17.9首轮新增回归发现100行联系人批量30秒到期整体回滚，以及导出准备500ms测试租约在SSH往返中过期。Windows现在校验并透传私有显式联系人租期；生产租约/fencing规则未放松。100行用例明确使用5分钟，导出用例按数据库实际lease_until等待接管。两个纠正后的真实PG套件32项、29Windows隔离环境/进程保护通过；首轮失败保留，完整其他组件及实际Worker/浏览器单列证据。API0.35、冻结V1–V22未改，不标完整合同DONE。
## 2026-10-04 API0.36人工巡检执行单与124真实HTTP

新增padmin租户内创建/摘要分页/详情/修改/历史/归档/恢复，固定资产/模板版本、未知自述时间null、显式三结果及自动数量；CSRF/幂等前授权、CAS/原子审计，能力目录和ready同步。16控制器+1ready新增、根1753报告1740执行含554PG/13跳过/6MinIO排除、86契约、1059Web/类型构建通过。124实际HTTP与独立指纹验证通过，14执行单/2模板归档、28无关表等值；原组件正常重启前后9表等值涵盖75旧执行单，无新迁移/账号。执行单UI/真实浏览器/OSS证据/季度汇总/发布仍未完成，M9-7非DONE，见docs/inspection-worksheets.md。

## 2026-10-04 原指定PG的Windows导出准备Worker

单作业显式PS/Node入口、私有PG环境投影、独立只读SHA产物、强杀前完整用户/进程/目标/字节核验已接，12隔离守卫通过。原PG17.9实际16列XLSX、源变化后保留旧文件、源读取受阻时只杀已核验JVM、fence4到期后新JVM fence5恢复通过。共14次尝试，前次有效正常/保留步骤与最终故障恢复窗口分列，失败日志保留；两原请求未替换、七表最终窗口等值、发布/存储/下载/通知0。不是prepared写事务中途或PG灾备验收，M3-7非DONE，见[Windows入口](export-preparation-worker.md)及[独立证据](evidence/local-export-preparation-worker-20261004.json)。
## 2026-10-04 执行单前端模块与105真实HTTP恢复

严格作用域/完整字段/计数/UTC与双摘要核验，连续冻结历史，创建源快照、原请求及回执/当前状态分离；未知后拒绝换键/升级版本，异步代际与独立拷贝防晚到污染。67新增测试（34校验33状态），全Web1126/68文件/类型构建、86契约通过；生产模块对真实PG/OIDC做105HTTP/62写尝试，四类成功响应丢弃后经真实403再原请求恢复，实际15+1列表/15+2历史、只读审计尾不变；18执行单1模板归档。不是物理网络故障/浏览器DOM，页面/季度报告仍未完成，见docs/inspection-worksheets.md。

## 2026-10-04 人工巡检执行单填写页面

新增角色/能力/remote_test受控路由及双导航、实际资产/模板只读选择和版本复核、冻结项显式三结果/未填/备注、自述UTC未知null、当前/原回执/历史分离及未知请求锁定/原请求重试。6纯表单+24Vue/jsdom测试，全Web1156/70文件/类型构建、86契约通过；11真实来源HTTP/只读审计尾不变、1模板归档，执行单写0。11:34保留沙箱复测Chromium仍EPERM，页面打开0，实际浏览器/移动/微信未验收。季度汇总/发布仍未实现，M9-7非DONE，见docs/inspection-worksheets.md。


## 2026-10-04 Windows V22就绪与100行实际导入

修正5秒就绪超时（原指定路径实测8.253秒），请求上限30秒/总窗口180秒，实际五角色启动和六OIDC通过。220实际HTTP验证100行整批成功/100不同v1/原202回放，全部归档；明确300000ms测试租期，不作性能/SLA或通知/账号绑定声明。见[运行记录](contact-import-runtime.md)和[100行证据](evidence/local-contact-maximum-worker-20261004.json)。

## 2026-10-04 原指定组件巡检和实际浏览器补齐

API0.36的模板117/执行单124实际HTTP、生产请求模块99/105和明确来源11、工单39只读GET在原指定PG/OIDC完成；所有本轮合成候选与执行单归档保留历史。Edge154.0.4258.53保留沙箱的桌面/390px模板15、执行单13检查通过，未知时间/未填不当正常、原请求恢复、真实409、来源v1冻结与历史均有DOM证据；截图逐张查看，无横向溢出/页面错误。首次确认框选择器与资产页长脚本错误保留；通知、证据绑定、季度归集/发布、客户批准仍待，不标M9-7或128项DONE，见[本地巡检验证](local-inspection-validation-20261004.md)。
## 2026-10-04 巡检报告来源不可变值模型

新增InspectionSourceSnapshot/Manifest，绑定完整冻结模板/执行单及修订记录元数据，稳定有序来源摘要、明确同设备多来源/未知时间/未填/归档诊断；不自动取最新，不把编码版本当指标口径，正式报告门禁仍CONFIG_PENDING、规则/时区/口径/库存基数null。20新增纯测试、55聚焦、根1773报告1760执行含554既有PG/13跳过/6MinIO排除、86契约通过；全模板内容计入16MiB技术预算。没有新增来源数据库捕获或月报/季度持久化报告，不标M9-2/M9-7/M9-9完成，见docs/inspection-report-sources.md。


## 2026-10-04 按明确版本捕获真实巡检来源

只读REPEATABLE READ内重查当前租户padmin/权限版本，整批解析明确历史修订、冻结模板/正文并验hash；缺失/跨租户404，重复400，未压缩完整正文保守预算超限413，损坏hash503。13真实PG+3纯输入、57聚焦、根1789报告1776执行含567PG/13跳过/6MinIO排除、86契约/17追踪测试通过；真实双连接证明授权快照之后的新修订不混入，21大模板修订在正文读取前拒绝。旧头/模板更新不重算已选来源，多来源与未知时间原样保留。schema只读检查0迁移；API0.36/冻结V22不变，无新HTTP/报告表/发布，季度规则待答，正式128项仍NOT_RUN。终端丢失后复用原组件恢复，PG记录WAL恢复，不冒充全库等值；见docs/inspection-report-sources.md及独立证据。

## 2026-10-04 最终本地来源快照接回

接回73a3b15的来源冻结/清单与真实捕获库：原指定PG13捕获、3选择与20清单共36项全部执行通过，API0.36无新路由、V22无新迁移。基线1746实际执行含554PG/6MinIO，加这批36为分阶段覆盖1782实际用例/567PG，不重复累计。追踪索引128项、37条局部说明、正式NOT_RUN128；Windows追踪检查16执行/1因符号链接创建权限1314明确跳过，没有改变宿主机权限。最终记录见[本地接续](local-continuation-20261004.md)和[来源证据](evidence/local-inspection-source-capture-20261004.json)。


## 2026-10-04 API0.37巡检来源按版本追溯

新增当前租户padmin GET明确修订来源，完整冻结模板/内容/来源三hash、不投影可变头时间；缺失/跨租户404、非法或隐式版本400，无写审计/收据/附件grant。6新增控制器/42聚焦、根1795报告1782执行含567PG/13跳过/6MinIO排除、89契约通过；原86路径/191schema保持。44真实HTTP核对更新/归档后旧来源不变、独立三个摘要、未知值、角色/租户及只读审计尾。普通组件发布重启9表等值含287已有执行单；仅1合成执行单/1模板归档，V1–V22不改/无迁移或OSS。来源下钻UI、报告独立持久化/季度规则/发布仍未完成，见docs/inspection-report-sources.md。


## 2026-10-04 并行原服务器证据接回与索引修复

API0.37提交前接回c0cf514及原服务器来源36项/Edge模板15和执行单13项真实检查，保留上游测试环境路径分离和Windows就绪超时修正。CI37204113832追踪源hash过期导致失败，重生成索引并通过133Python/JS语法检查；所有128正式状态仍NOT_RUN。四PNG远端blob不变，现有仅private/只读短期源码归档刷新用于恢复其本地文件，不访问原服务器或创建Codex/Work任务。实际新CI与物化结果以随后回读为准。


## 2026-10-04 巡检来源三重摘要与只读追溯面板

当前回读/历史均可明确核对选定版本，带已显示内容hash约束；三个摘要全验、异步旧上下文不回填、读取失败不留旧内容，独立面板不确认未知写入。新增34输入/reader/SFC/接入测试，全Web1190/72文件/类型构建、90契约和19真实GET（14来源GET）通过；无新业务记录或写入。会话过期401和安全拒绝审计窗口误判均保留失败记录，核实403/404各记1条DENIED，契约说明改为成功读取无业务审计，既有安全审计不关闭。托管云浏览器存在但回环页面被URL安全策略阻断，新UI真实浏览器仍NOT_RUN；Vite仅本次拥有进程停止，API/PG/OIDC保留，V22冻结/API0.37不变。独立报告快照持久化、季度规则和发布仍待；见docs/inspection-report-sources.md。


## 2026-10-04 不可变巡检来源文档编码

增加未来冻结保存所需的严格UTF-8文档codec：独立租户/文档hash/清单hash、各层完整正文重新验证、规范原字节往返，重复/多余字段/坏UTF8/次序/类型/范围失败关闭。19纯测试及20核心聚焦通过，含1000来源×100项和1185字节独立Python黄金；根1814报告1801执行含567既有PG/13跳过/6MinIO排除、90契约通过。明确32MiB/400万token技术保护，编码格式不是业务指标版本，引用不授文件权。V22/API0.37不变，无冻结表/新HTTP/迁移/正式报告；后续独立落库验证仍待，见docs/inspection-report-sources.md。


## 2026-10-04 V23不可变巡检来源集合结构

独立迁移一次、重复/只读/旧V22模式0，48既有项目表全行指纹一致。两表/三invoker触发器将文档完整正文与真实冻结来源及多层hash对应，引用同租户FK，创建xid封闭成员、每集合单次延迟完整性/提前flush追加拒绝；runtime仅SELECT/INSERT，无PUBLIC。V1–V23冻结，V23 eabc4cf2512cdea920acf97ba1991499fde9d0901c7b91b5a8cc7b51f13599e8。14迁移事务内预校验+2明确未执行，正式后17真实runtime结构/29迁移策略/134Python、根1833报告1820执行含584PG/13跳过/6MinIO排除/90契约通过。两次ACL测试查询拼写失败保留且只修测试；6条合法已提交合成集合保留，其中5非空，hash/数量正确。仓储/API/新集合UI/季度规则/正式报告仍待，不标合同DONE，详见docs/inspection-report-sources.md。


## 2026-10-04 巡检来源集合受保护保存仓储

明确来源ID/修订/预期SHA、保护事务内捕获/文档/引用/审计/小收据，成员校验先于原收据重放；完整正文回读多层hash与时序、元数据只读分页。6输入+18真实PG新增，双连接同键/真实延迟约束及审计SQL失败完整回滚、损坏读失败关闭；根1857报告1844执行含602PG/13跳过/6MinIO排除、90契约/17追踪通过。58合成不可变集合保留，35非空，全未核验且未发布；没有永久删除。V23字节冻结/0新迁移，API0.37契约原字节不变，集合HTTP/UI和正式季度/月报仍待，详见docs/inspection-report-sources.md及仓储独立证据。前结构提交0b2aa的CI37210517201成功，Ubuntu原生pwsh94策略检查/0数据库操作已回读。


## 2026-10-04 API0.38独立来源集合创建与回读

创建/元数据分页/完整详情三操作，当前租户padmin与显式能力目录/CSRF/原请求/来源hash保护，ready要求V23；87旧路径及除ReadinessResult外旧schema等值，新增2路径6schema。73真实HTTP/五层独立SHA/角色与CSRF/跨租户/重放/父记录变化后不重算均通过，保留3合成集合/1执行单1模板均归档。17控制器1ready新增、根1875报告1862执行含602PG/13跳过/6MinIO排除、96契约/1190Web/134Python通过；普通重启11表在登录前等值含88既有集合。初次完整回归暴露漏分类两能力，补显式分类且保留未知拒绝后通过，不掩盖失败。V23/0新迁移，API固定包42f6174a...，集合UI/季度业务规则/正式报告仍待，详见docs/inspection-report-sources.md。前仓储35fea20的CI37211868923已成功。


## 2026-10-04 来源集合前端完整性与原请求模块

生产TypeScript五hash/完整诊断复算、元数据和详情分离、不可变回执比对、原body/key未知重试、异步context取消与新创建门禁已实现。新增47/全Web1237项74文件/类型构建/96契约通过，后端与HTTP契约未变。21真实HTTP含3同键POST只创建1集合；实际成功响应受控丢失、普通刷新仍未知、损坏原重放仍未知、最终原重试成功，5详情篡改/迟到响应清理及真实403/404安全审计已验。不是物理故障，不新增父记录/迁移/存储；页面组件、真实DOM/手机和正式报告仍待，见docs/inspection-report-sources.md及独立客户端证据。


## 2026-10-04 明确来源集合页面

来源ID/版本手动核对加入、明确空选择确认、原请求锁定/恢复、保留拒绝输入、元数据列表与五hash详情分离已接路由及双菜单；23SFC/jsdom单测，全Web1260/75文件/类型构建/96契约通过。额外实际Vue组件在jsdom中通过原生表单/按钮连接真实PG/OIDC，11HTTP/2原POST1集合成功，v1/v3归档保留；这不是真实浏览器或CSS视觉验收，B03仍待。首轮场景等待在途状态过早导致断言失败/客户端取消，只读PG确认已提交，用原key/body恢复同一记录后，独立守卫r2场景成功；首轮与r2两份合成集合均保留，未删除/新增父记录/迁移/存储。源代码及独立证据见docs/inspection-report-sources.md；季度口径/正式报告与128正式验收仍待。


## 2026-10-04 已固定业务时区说明纠正

04配置登记与config-baseline已明确business.timezone=Asia/Shanghai为平台固定约束；此前来源页面把季度时区列为待配置不准确，16:35已告知用户无需再确认时区。现在区分固定平台时区与来源集合未选报告周期（period_time_zone=null）；季度来源选择/未知时间/归档纳入仍待，不自动生成报告。共享显示常量替代原内联同值，渲染行为不变。3Web+1契约新增，全1263Web/76文件、类型构建/97契约通过；仅四处schema描述/扩展新增，路径/字段类型/API0.38/后端/原文档/迁移不变，无新HTTP或业务记录。见docs/inspection-report-sources.md。


## 2026-10-04 M6-5演练自述记录纯核心

不可变设备/版本/执行人/结果/附件引用、自述时间验证与内容指纹；固定上海自然日严格>90对照，无来源保持null、不声称所选是最新或恢复已成功。21新增纯测试/53聚焦，根1896报告1883执行含602既有真实PG，13历史跳过/6MinIO排除；无新增记录仓储、HTTP、设备或存储调用，在线API0.38未重启，V23字节不变。实际最近记录查询、受保护持久化/页面/真实备份版本仍待，M6-5非DONE。见docs/recovery-drill-records.md及独立证据；前时区纠正04a355的CI37218158956成功。


## 2026-10-04 M6-1每日备份计划草稿核心

名称/有序唯一目标/严格HH:mm/可空凭据UUID/启用意向与备注纯模型，实际enabled/effective=false，不建立凭据访问或SSH；每日02:00及2次/10分钟仅合同可配置默认提案。明确日期预览拒绝推算下一执行，上海历史夏令时缺口/重叠保持未知。18新增含1440分钟向量、71聚焦、根1914报告1901执行含602既有真实PG、13历史跳过/6MinIO排除通过。0新迁移/计划存储/API/调度，V23/API0.38未变；后续推进受保护草稿仓储，正式启用和M6-1验收仍NOT_RUN。见docs/backup-plan-drafts.md及独立证据。前演练2f08ef0的CI37219181054成功。


## 2026-10-04 V24备份计划未生效草稿结构

三表/四invoker触发函数、目标同租户FK/连续版本与完整目标、创建事务内固定引用、归档恢复原样保留、固定未启用/未批准字段已在既有nikoPG17.11独立执行一次并冻结；50旧表完整行指纹等值，重复/只读/旧V23模式0迁移。19新增真实PG+31迁移策略/50聚焦，根1935报告1922执行含621PG、13历史跳过/6MinIO排除、135Python/97契约通过。2合成草稿保留未启用；仓储/API/页面未接入，无SSH/存储/凭据配置，在线API0.38未重启。初次Python旧版本上界失败已修正；错误snapshot参数在操作前拒绝后正确执行，均如实留证。原生pwsh待CI，非Windows数据库验收。见docs/backup-plan-drafts.md及结构独立证据；前829ef2c的CI37219698445成功。


## 2026-10-04 备份计划草稿受保护仓储

当前租户padmin/成员权限版本、真实同租户目标、保护事务创建/修改/CAS/归档/恢复/审计/原请求重放和只读分页/历史已实现。21新增真实PG+58聚焦，根1956报告1943执行含642PG，13历史跳过/6MinIO排除通过；两连接同键一个提交、不同键旧版本一个成功一个409，真实审计SQL与目标FK失败五表回滚，受控读取伪造enabled时503且不改数据库。首轮1测试SQL误用审计扁平列，改JSON查询后通过；自有合成草稿全部归档保留。V24不变/0迁移，HTTP/UI未接入，无真实设备/存储/凭据配置，API0.38在线包未重启。见docs/backup-plan-drafts.md与仓储证据；前ead1113的CI37220663039成功，Ubuntu原生pwsh103保护/0DB已验。


## 2026-10-04 API0.39未生效备份计划草稿

新增独立草稿CRUD/历史/归档恢复路径、当前padmin能力/CSRF/原请求/CAS保护，ready实际要求V24但不声称凭据或调度可用。89旧路径及198旧非ready schema等值，新增5路径9schema。18控制器+1ready/50聚焦，根1975报告1962执行含642PG、13跳过/6MinIO排除、103契约1263Web/76文件/类型构建通过。80真实HTTP均预期，2自有草稿全部归档，18历史/36目标/18成功审计/2创建核对；待重试0。正常部署重启53表原行等值先于6OIDC登录，在线包c144ecc0...，0新迁移。初次两旧能力列表期待和测试重读流失败已修正，未放宽校验。页面/设备/OSS/KMS/正式计划执行仍待，见docs/backup-plan-drafts.md；前018cd7d的CI37221509138成功。


## 2026-10-04 备份计划原请求前端模块

严格定义/摘要/详情/连续历史字段核对与不可替换path/key/body/baseline、当前scope清理、迟到响应丢弃已实现。40新增Web/1303总数78文件/类型构建通过；26真实生产模块HTTP，真实创建响应受控丢失、刷新和真实msp拒绝重试403不消除未知、损坏原回执不接受、最终原重试只1创建，真实修改/归档/恢复及5受控GET篡改/迟到响应通过。1自有草稿最终归档，5历史10目标5成功审计/1创建，待处理原请求0；不是物理断链或实际角色撤权/浏览器。初始可空分支与测试字面量类型检查已修正。后端/API0.39/V24未变，页面继续，见docs/backup-plan-drafts.md；前31f4fbc的CI37222968726成功。


## 2026-10-04 每日备份计划草稿页面

受保护路由/双菜单、明确多设备勾选/去重/排序、意向必选、null备注/未核验UUID、原收据与当前/历史分离、原未知请求/离开与ctx保护已实现；目标分组10条/单个历史展开/44px响应式样式。24新SFC/jsdom与1327Web/79文件/类型构建通过；连续勾选在同渲染批次丢选择已用明确当前状态change处理修复，最终保持快速事件测试。实际Vue/native jsdom控件连接真实PG/OIDC完成18HTTP全200，2次同键派发1创建，修改/历史/归档恢复通过，1自有草稿最终归档5历史10目标5成功审计，待处理0。弹窗仅测试显式确认，非真实浏览器/CSS/手机/企微；B03仍待。API0.39/V24不变，无SSH/存储/凭据配置，完整M6-1与128验收仍未完成。见docs/backup-plan-drafts.md；前a145fef的CI37224359516成功。


## 2026-10-04 未审核变更报备草稿纯核心

M6-7明确租户/申请人/设备/摘要/计划开始，可空结束和工单引用；UTC毫秒/固定上海日期、原文顺序与独立243字节SHA金样。21新增纯测试/60聚焦，根1996报告1983执行含642既有真实PG、13历史跳过/6MinIO排除通过。审批与自动P3决定null，工作流/匹配CONFIG_PENDING，不把填齐时间设备当已批准报备或抑制告警；无新SQL/仓储/API/UI/SSH。V24/API0.39和在线包不变，M6-7完整验收NOT_RUN。同步纠正本文顶部旧的“当前”概览为当前实际证据，旧内容保留作历史。见docs/change-notice-drafts.md；前7abd845的CI37226209707成功。
