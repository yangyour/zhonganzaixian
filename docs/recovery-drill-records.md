# M6-5 恢复演练自述记录与日期对照

## 已实现范围

合同M6-5/T00R082及02详细设计要求演练时间、设备、备份版本、执行人、成功/失败/部分成功、备注和附件，以及距上次演练超过90天标红；一期只管理记录，不自动恢复。Q22允许先实现记录，不解除实际设备授权与存储门禁。

本切片只有不可变领域模型和纯日期计算。RecoveryDrillRecord保存明确租户、资产、StoredVersion引用、执行人引用、reportedPerformedAt、reportedConclusion、可空说明/备注及有序附件UUID；结论严格为SUCCESS、FAILED、PARTIAL_SUCCESS，始终SELF_REPORTED_UNVERIFIED。同租户/设备不匹配404，版本保存时间不能晚于自述演练时间；未来写入必须用独立服务器时刻调用requireNotFuture。时间使用UTC整毫秒且UTC和上海业务日期在0001—9999年；没有系统时钟兜底。

文字保留null、空串、空白及原始换行，不trim、不补默认说明；每字段4000 Unicode码点，拒绝孤立代理字符与非换行/制表控制字符。附件最多10个不重复非空UUID，保留顺序并防御复制。这些是拒绝而非截断的技术保护，不是客户配额。引用不授予附件访问权；模型不验证真实对象、加密/KMS、版本存在性或执行人身份。

## 固定时区和90天

BusinessDates集中后端已固定business.timezone=Asia/Shanghai；原WarrantyDateAssessment.DISPLAY_ZONE同值引用，原日期行为不变。该时区来自04配置登记和config-baseline，不是待确认业务值。

RecoveryDrillAgeAssessment只对调用者明确选中的自述记录计算上海自然日差。90天不触发、91天触发，即严格days > 90，不用经过的24小时桶。失败或部分成功的自述仍是有日期的记录，不会成为实际恢复成功证据。无选中来源时日期/天数/警示/hash全为null、UNKNOWN_SOURCE，不代表0天、从未演练或已逾期。未来时间拒绝，跨租户/资产404。

selectedRecordIsLatestStoredDrill始终null；scope=SELECTED_SELF_REPORTED_DRILL_DATE_ONLY。本方法没有查询所有记录，因此不能把选中记录称为“最近一次”。实际列表的最近记录选择、分页、权限和完整性必须由后续真实仓储实现。所有结果actualDrillVerified=false、automaticRestoreAllowed=false、notificationActionThisAssessment=NOT_REQUESTED。

## 确定性指纹

FORMAT=MOP_MANUAL_RECOVERY_DRILL_RECORD_V1。字段编码为大端int32 UTF-8字节长度及原字节；null长度为-1。顺序为格式、租户、资产、备份版本UUID、加密对象SHA、大端int64对象字节数、大端int64保存EpochMillis、执行人UUID、大端int64演练EpochMillis、结论、说明、备注、大端int32附件数及依次UUID。哈希为SHA-256，返回新字节数组。这是内容指纹，不是签名、授权或真实恢复证明。

独立Python struct/UTF-8编码金样333字节，SHA256=0fe1dcc213ac4db094b28bfc0b04b2d03570c0688a6f804c861114a3ada6ca72；具体虚构输入见RecoveryDrillRecordTest。null和空串以及附件顺序均改变指纹。

## 验证与仍待实现

21新增纯测试，联同既有备份运行20、维保日期12共53聚焦测试通过。覆盖身份/对象绑定、未来时间、Unicode/不可变复制、独立金样、严格90/91、上海午夜、闰年/年界、缺来源未知和禁止推断真实结果。完整回归实际计数见[独立证据](evidence/niko-recovery-drill-core-20261004.json)。

无新SQL迁移、HTTP API、持久化、页面、SSH、存储调用或恢复任务；API0.38契约原字节不变，在线固定API包不因构建而切换。未来记录仓储/API仍须真实备份版本/资产/执行人/附件验证、当前成员与权限版本、CSRF、幂等、乐观锁、审计和跨租户404。OSS/设备连接阻塞不以假版本或本机替身冒充完成；现有真实PG回归只证明其他既有仓储未回归。M6-5和全部128合同验收继续NOT_RUN。
