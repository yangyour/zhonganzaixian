# 手工资产草稿资料维护

对应M3-D03/M3-5。当前已完成输入核心及独立V9结构/约束验证，授权仓储已有12项真实PG证据，API/页面待，不标DONE。

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
