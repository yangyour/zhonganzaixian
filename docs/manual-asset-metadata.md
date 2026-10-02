# 手工资产草稿资料维护

对应M3-D03/M3-5。当前已完成输入核心及独立V9结构/约束验证，仓储/API/页面待，不标DONE。

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
