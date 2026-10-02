# 手工资产草稿资料维护

对应M3-D03/M3-5。当前已完成输入核心及独立V9结构/约束验证，授权仓储已有12项真实PG证据，API70项/引用28项真实HTTP、页面代码及实际客户端22次HTTP已验；浏览器验收仍待，不标DONE。

## 明确范围

- 只考虑source=manual、lifecycle=DRAFT，且status=unknown、sampled_at=null、availability=UNKNOWN、sync_state=PENDING_ONBOARDING的现有草稿
- 可编辑事实包含名称、既有6类类型、站点、品牌/型号/序列号、位置、负责人引用、业务描述、采购/维保/超期日期、采集方式说明及备注
- 管理IP不在此输入模型中；IP冲突范围/重验策略、CMDB权威字段和监控配置不被本切片改写
- 名称/站点/备注必须明确非空，其他字段显式null保留未知；文本保留原文，不自动大小写/Unicode归一或补“其他”
- 日期仅严格ISO yyyy-MM-dd，0001..9999；没有默认日期、Excel序号或自动推断过期。手填无效日期拒绝，与以后导入的“置空并警告”流程分开
- 采集方式说明只是手工文字，不是已验证协议、凭据、模板或接入配置；业务描述不等于已绑定CMDB业务
- owner UUID语法通过不意味着成员归属或授权已通过；后续仓储必须同租户检查活跃成员/站点，锁定资产重新检查状态，并保留CAS/幂等/前后快照/审计

6项隔离测试覆盖既有类型、显式null/原文、Unicode/文本技术边界、严格日期、UUID语法、手工草稿与监控状态门禁。根515=502执行通过（含既有111真实PG）+13fixture跳过，6MinIO排除；111项是既有功能回归，不是新资料持久化证明。

V9已经独立执行并冻结；后续新增结构需V10等独立新迁移，V1–V9不改；客户资料维护权限/Q09及CMDB/IP规则未确认分支继续明确保留。不会把草稿资料保存当作监控已生效。

## 2026-10-02 V9手工资产资料不可变修订结构

V9新增mop_asset_manual_revision/details，当前指针引用不可变修订；actor/owner/site/asset均有同租户复合FK，修订可链接更早资料版本。before_snapshot中的资产标识/旧版本/IP/手工草稿状态受约束，首次未知metadata须为null；后续仓储仍必须锁定真实资产并构建真实前后快照，schema约束不能替代实际成员授权或来源核验。

可选资料/日期无默认值，SELF_REPORTED/monitoring_applied=false/cmdb_applied=false固定；运行身份只获资产name/type/site_id/version/updated_at/updated_by六列UPDATE，不能改IP、source、tenant或监控状态；历史资料没有UPDATE/DELETE权限。新schema只在niko显式check0→apply1→repeat0→check0，旧worklogs显式apply再次为0。V1–V8字节未改，V9应用后另存冻结hash；API/Worker不迁移，后续结构需新版本。

10项迁移策略、8项新增真实PG结构/ACL回归、30项niko守卫通过；schema测试事务全部回滚，不冒充资料服务持久化。根524=511执行（含119真实PG）+13fixture跳过，6MinIO排除，0失败错误。仓储/API/页面未接线，M3-5非DONE；API仍V0.12。见[结构证据](evidence/niko-asset-metadata-schema-20261002.json)。上一c6f2f25的CI37043222178已回读success。

## 2026-10-02 手工资产资料授权仓储

ManualAssetMetadataRepository/PostgresManualAssetMetadataRepository接入既有幂等事务：当前启用msp/padmin成员与权限版本重新核验，锁定本租户资产，再检查站点/负责人归属和pristine manual DRAFT条件。CAS更新名称/类型/站点及版本，同时写不可变资料、真实前快照、当前指针、审计和重放结果。管理IP、source、创建时间、生命周期及监控字段保留；客户/tadmin维护范围待Q09，不自动授予权限。

读取当前资料/按版本分页历史为只读快照；无资料明确metadata=null，历史不造第一版。指针与最大修订不一致或持久格式非法返回503；当前核心字段与上次资料不同显示drift阻塞，不静默覆盖。改名冲突409、旧版本409、跨租户资产/站点/负责人404；外部/test_seed来源422，失败整笔回滚。

新增12项真实PG回归通过：实际前后值/日期/原文、连续历史、旧键回原收据不回退现值、改名与CAS、角色/引用范围、外部来源门禁、下游回滚、两独立连接并发一胜一409、协议日期边界以及站点最小权限。首轮11项中4失败/6错误，原因是显式SELECT FOR KEY SHARE需要站点UPDATE权；运行身份刻意只有SELECT。修正为普通授权引用查询，并由实际写入的复合FK取得引用锁，未扩大站点权限；新增42501回归确认这一边界，最终全量通过。V9未改。

根536=523执行（含131真实PG）+13fixture跳过，6MinIO排除，0失败错误；断库503和无受保护事务拒写同时通过。公开资料API/页面仍未接线，M3-5非DONE。见[仓储证据](evidence/niko-manual-asset-metadata-repository-20261002.json)。上一V9 dcbca1f的CI37046265831已回读success。


## 2026-10-02 内部资料API V0.13

新增GET/PATCH /api/v1/assets/{id}/manual-metadata与GET .../history，仅当前活跃msp/padmin且assets:write；tadmin/viewer未开放Q09客户维护。当前视图返回asset、可空metadata/metadata_version/recorded_by/recorded_at/verification_status，以及can_edit/edit_blockers；没有资料不会合成已核验数据。修订返回不可变实际before快照及after资料，不提供DELETE。

PATCH严格要求expected_version及全部14个资料字段，可选字段显式null，缺字段/额外字段/重复JSON/尾随JSON/超过32KiB拒绝。IP、tenant、actor、source和监控字段不可输入。既有全局CSRF、当前成员校验和幂等事务接线；版本为JS安全范围，保留原始内容的规范字段排序哈希。历史按资产版本降序，before_version>=2、技术单页10/15/25/50/100。成功读取不追加审计。

ready新增asset_manual_metadata真实schema/ACL检查；缺表或权限返回503，live不探测，API/Worker无自动DDL。V1–V9不改。资料始终SELF_REPORTED、monitoring_applied=false、cmdb_applied=false，管理IP变更保持CONFIG_PENDING。

8项控制器隔离测试+1项就绪测试，根545=532执行（含131真实PG）+13历史fixture跳过，6MinIO排除，0失败错误；契约25项、Web239项及类型/构建通过。70项真实PG/OIDC HTTP均通过响应契约校验，覆盖显式null、日期0001/9999、两种内部角色、跨租户对象/引用404、seed422、权限/CSRF/请求边界、旧键返回旧收据而不回退当前值、实际before历史、并发一200一409和恰好3条成功维护审计。首次执行通过，无存储或浏览器替身。资料页面、引用选择器及完整M3-5验收仍待；见[HTTP证据](evidence/niko-asset-metadata-api-http-20261002.json)与[汇总](evidence/niko-asset-metadata-api-20261002.json)。上一仓储2e39c30的CI37048403534已回读success。


## 2026-10-02 V0.13.1只读引用选择

GET /assets/{id}/manual-metadata/references提供当前租户站点与启用成员标签，复用内部角色/权限版本及资产归属核验后，在同一只读快照查询。仅id/label，不返回身份subject或连接资料；两类各最多100条并独立truncated，q最多100 Unicode code points，以参数化strpos(lower(...),lower(?))作字面子串查询，百分号/下划线/引号不变成通配或SQL。不默认选择当前操作者；default_owner_id=null，实际PATCH重新核验引用。

新增2控制器+2真实PG回归，根549=536执行（含133真实PG）+13fixture跳过，6MinIO排除；26契约检查通过。28项真实HTTP/响应契约覆盖内部两角色、当前租户选择、外租户目录404、客户403、100 emoji/超界/控制符、通配符字面匹配和成功查询不增加审计。没有新建100+身份或站点以冒充生产大目录覆盖；truncated实际大目录、浏览器控件和完整客户验收仍待。上一API93ac0a7的CI37050157504已回读success。见[引用HTTP证据](evidence/niko-asset-reference-http-20261002.json)。


## 2026-10-02 资料页面与真实客户端验证

AssetsPage详情新增内部资料面板，独立保留目标资产，不随详情drawer销毁；另一资产存在未确认写请求时禁止切换目标。面板读取当前资料/不可变前后历史，显式点击“以当前版本编辑”建立基准；刷新不覆盖表单、不会采用新版本自动写。当前版本变化时阻止旧表单提交，需要用户重新明确选取。未知字段/负责人为空，不默认当前人；站点与负责人只用当前租户只读选项，未匹配的旧引用仍显示保留，不静默清空或替换。日期输入严格ISO，不补今天、不推断过期。

保存不确定时保留原key/body/CAS和资产基准，刷新只GET；仅明确“原请求重试”重发。成功回执独立保留，即使之后刷新失败仍显示该次已确认版本；409不自动重试/覆盖。租户/权限/目标变化取消旧请求并清除旧上下文结果；路由退出与浏览器关闭在不确定时提示。IP/source/采样/监控不编辑，非pristine资产明确显示阻塞；页面样式含窄屏单列/44px触控目标，但未有DOM或390px截图验收。

实际assetMetadataWorkspace+ApiClient打包后连接真实PG/OIDC/API：22次HTTP、5次页面写尝试（含2次原键重放和1次409）及1次独立客户端修改，最终3份资料修订/3条成功审计。刻意丢弃两次服务端200响应验证不确定重试；不是物理网络中断或浏览器点击。20项前端模块测试新增，总259项、类型/构建、26契约一致性通过。首次UI类型检查发现闭包中unknown未保持缩窄，提取已验证首修订后修复并重跑通过，不隐去失败。上一引用接口b415854的CI37051351964 success。见[实际客户端证据](evidence/niko-asset-metadata-client-http-20261002.json)。B03及Q09/IP/CMDB完整维护验收仍未解除。


## 2026-10-02 新增资料字段筛选核心/仓储

ManualAssetFilter为14个维护字段提供显式AND筛选：名称/品牌/型号/序列号/位置/业务/采集方式/备注使用字面不区分大小写子串，类型/站点/负责人/三个日期精确匹配；unknown_fields明确筛选可空资料，不能与同字段值冲突。未保存资料的备注也可明确作为未知检索；不把日期推断成维保状态。字段/Unicode/日期边界、空白、重复未知项与未知字段拒绝。

授权仓储search限定当前活跃内部成员与source=manual，采用只读REPEATABLE_READ快照、参数化SQL与枚举列名，各请求至多101条用于技术分页。按不可改created_at/id降序keyset；跨请求不是冻结快照，编辑会改变筛选成员，后续API/页面必须明确刷新语义，不能将多页拼成一致性导出。候选行回读现值、资料指针/修订一致性和来源门禁；不改原12列或客户可见范围。

6项纯核心、3项真实PG新增通过；根558=545执行（含136真实PG）+13历史fixture跳过，6MinIO排除。覆盖全部维护字段联合查询、字面百分号/下划线/引号、显式未知、两页无重复、来源/租户/角色/权限版本与不追加维护审计。首轮纯核心测试放在无JUnit依赖的domain模块导致编译失败，移入已有JUnit的persistence测试模块后通过，未增加依赖或改构建权限。公开查询API/页面筛选尚未接线，本段仅核心/真实仓储证据，不标M3-5 DONE。见[筛选仓储证据](evidence/niko-asset-metadata-filter-repository-20261002.json)。上一页面14e4673的CI37052717490 success。


## 2026-10-02 资料筛选API V0.14

GET /api/v1/assets/manual-metadata新增内部附加视图（静态路由独立于/assets/{id}），不改变原12列台账及客户范围。14字段AND、unknown_fields及page_size/成对before_created_at+before_id为严格白名单；重复/未知参数拒绝，整体编码query<=4096 bytes，过长明确400，不截断执行。名称等字面匹配，类型/站点/负责人/日期精确；返回实际条件、explicit-null语义、当前请求快照边界和各行当前资料/阻塞。

新4控制器单测，根562=549执行（含136真实PG）+13fixture跳过/6MinIO排除；27契约、259Web及类型/构建通过。63项真实HTTP响应契约通过：12资产10+2分页且无重复、全部维护字段联合/分别检索、日期与未知、百分号/下划线/引号、更新后的筛选成员变化、外租户值无匹配、客户403、无会话401、参数和编码长度边界、成功查询不写审计。公开API已接线，页面查询控件仍待；多页结果不冒充冻结导出。上一筛选仓储4f21777的CI37053520486 success，见[筛选HTTP证据](evidence/niko-asset-query-http-20261002.json)。
