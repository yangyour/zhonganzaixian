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
