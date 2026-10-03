# 手工来源事件登记边界

## 2026-10-03 M5-6手工来源事实核心（未接仓储/API）

按合同M5-6原文，先定义标题/明确严重级别/关联资产及所见资产版本的不可变事实。五个合同等级为灾难/严重/一般/警告/信息，代码沿用已核对原型语义disaster/high/average/warning/info；不把none、unknown或监控NORMAL推成默认严重级。标题保留原Unicode及空格、至多200 code point是技术保护，不是新增客户规则；关联资产须完整UUID、版本为明确安全正整数。

source_state恒MANUAL、verification_status=SELF_REPORTED、registration_status=REGISTERED_ONLY；monitor_state/disposition_state/priority/observed_at/recovered_at均null。技术登记时间不是实际故障/恢复时间。监控恢复许可为false、不会自动创建工单；人工恢复确认、正式事件五态和工单关联尚未实现，不能靠监控正常推断恢复或把本类型当已建单。内部当前成员/权限版本及同租户资产门禁定义在纯规则中，生产Q09尚未批准；客户与未知角色拒绝。仓储接口要求后续在幂等事务中重查成员/资产/CAS/审计，重放前再授权。

10纯规则测试及完整根726=713执行（含212既有真实PG）+13历史fixture跳过、6MinIO排除通过，35契约不变。首次把测试放在无JUnit依赖的domain模块导致测试编译失败；按仓库既有约定移至api测试模块，保持domain无测试依赖，完整重跑通过。212PG是已有模块回归，不是手工事件PG证据；尚无V13、业务仓储、API或页面，也没有真实监控回调/恢复测试。

下一步独立V13与真实PG登记，再接严格来源投影和列表/表单。V1–V12字节冻结、API0.18不变；完整M5-6及D06/D07/128项未DONE。

见[纯核心证据](evidence/niko-manual-incident-core-20261003.json)。
