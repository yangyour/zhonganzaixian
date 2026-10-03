# 手工来源事件登记边界

## 2026-10-03 M5-6手工来源事实核心（未接仓储/API）

按合同M5-6原文，先定义标题/明确严重级别/关联资产及所见资产版本的不可变事实。五个合同等级为灾难/严重/一般/警告/信息，代码沿用已核对原型语义disaster/high/average/warning/info；不把none、unknown或监控NORMAL推成默认严重级。标题保留原Unicode及空格、至多200 code point是技术保护，不是新增客户规则；关联资产须完整UUID、版本为明确安全正整数。

source_state恒MANUAL、verification_status=SELF_REPORTED、registration_status=REGISTERED_ONLY；monitor_state/disposition_state/priority/observed_at/recovered_at均null。技术登记时间不是实际故障/恢复时间。监控恢复许可为false、不会自动创建工单；人工恢复确认、正式事件五态和工单关联尚未实现，不能靠监控正常推断恢复或把本类型当已建单。内部当前成员/权限版本及同租户资产门禁定义在纯规则中，生产Q09尚未批准；客户与未知角色拒绝。仓储接口要求后续在幂等事务中重查成员/资产/CAS/审计，重放前再授权。

10纯规则测试及完整根726=713执行（含212既有真实PG）+13历史fixture跳过、6MinIO排除通过，35契约不变。首次把测试放在无JUnit依赖的domain模块导致测试编译失败；按仓库既有约定移至api测试模块，保持domain无测试依赖，完整重跑通过。212PG是已有模块回归，不是手工事件PG证据；尚无V13、业务仓储、API或页面，也没有真实监控回调/恢复测试。

下一步独立V13与真实PG登记，再接严格来源投影和列表/表单。V1–V12字节冻结、API0.18不变；完整M5-6及D06/D07/128项未DONE。

见[纯核心证据](evidence/niko-manual-incident-core-20261003.json)。


## 2026-10-03 V13不可变手工事件登记结构与真实PG

在niko独立库显式执行V13一次，重复执行/只读check/旧V12模式均0。20列与18项CHECK、主键/同租户资产和作者组合外键、登记排序索引及运行角色仅SELECT/INSERT已验证；PUBLIC无授权，无列级UPDATE。原登记不可覆盖，后续人工恢复事实或工单关联需独立证据。MANUAL/REGISTERED_ONLY/SELF_REPORTED固定，监控/正式处置/优先级/实际发生和恢复时间保持null，自动建单false；数据库拒绝把监控正常当人工恢复。

8项新增真实PG schema测试全部事务回滚，涵盖原Unicode/200 code point、五等级、跨租户外键、版本安全范围、来源/未知投影、ACL及时间/快照边界。测试后只读确认登记表0行、Q08两项value仍null。完整根736=723执行（含220真实PG）+13历史fixture跳过，6MinIO排除，15迁移策略/51Python保护/35契约通过，无失败。CLI改为显式版本/目标矩阵，仍要求连续已验前置历史、独立单功能执行，不自动跨越迁移；纯矩阵测试覆盖所有模式与已验状态。

V1–V13已字节冻结，V13 SHA256为80734d0a0dcd436a3008ed76af0b7da3e81956f516a25d778c0717df79665700；后续结构修正V14+，API/Worker启动不迁移，API0.18不变。结构通过不等于当前角色授权、资产实际版本CAS、幂等/审计仓储或API/UI已验。下一步实现同租户当前内部成员的登记仓储、原请求重放前再授权及真实PG并发；人工恢复/正式事件状态/建单闭环与完整M5-6/D06/D07/128项仍未DONE。

见[手工登记结构证据](evidence/niko-manual-incident-schema-20261003.json)。
