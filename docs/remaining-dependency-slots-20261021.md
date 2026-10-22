# 剩余业务：依赖槽位与仍需开发的代码

当前核对依据：开发顺序D05—D14、客户待确认Q01—Q31及config-baseline。本文不是完成率或生产就绪证明；已有测试切片不能代替完整模块。

## 补齐的离线模板

以下位于deploy/examples，均是未被运行时消费的依赖形状清单。执行/发布开关false、生产批准false、租户和外部引用null；不是部署到运行配置表的已批准值。

|模板|明确保留的缺失项|主要Q项|
|---|---|---|
|monitoring-onboarding-dependencies.json|设备/租户映射、模板OID、厂商兼容、已有连接及只读凭据引用、TLS信任、采样范围、恢复观察策略、首值回读|Q03/Q06/Q19/Q20/Q21/Q28|
|remote-session-dependencies.json|路线确认、产品许可、设备授权、双审批/readonly例外、已有堡垒机连接、凭据引用、录屏完整性、命令拦截、终止能力|Q01/Q03/Q09/Q10/Q28|
|report-publication-dependencies.json|真实指标快照、重生成策略、服务经理审核、客户文案、字体/品牌许可、渲染验证、加密存储、接收对象及送达许可、首次阅读口径|Q10/Q11/Q23/Q24/Q28|
|operational-finance-dependencies.json|成本来源、分摊基数、实际工时、FTE、工资参考出处、计量定义、SLA补偿公式/舍入与审核|Q23/Q26/Q30|
|tenant-governance-dependencies.json|试点租户、决策人、管理员分配批准、数据库隔离、资源容量、原子配额预留、开通补偿、保留清单/法律保全/删除批准及恢复演练|Q02/Q09/Q10/Q16/Q21/Q28|

既有CMDB、事件拉取、工单生命周期、通知路由、备份依赖模板继续保留。模板不请求、保存或配置真实密码、私钥、API key；不自行创建账号、授予权限、发送通知、建连、收费或删除数据。

## 固定约束与可配置值分开

复核发现备份模板把backup.min_retention_months与backup.failure_ticket_count放入runtime_publications。二者是合同固定约束，现移到fixed_registry_keys，并列明backup.drill_warning_days；其值仍以注册表为准，不当成待填的可编辑值。连续失败的“次”如何计数仍由backup.failure_count_semantics保持null，不因固定次数明确就擅自启用建单。

其余模板同样把固定约束只列为注册表键引用；可配置默认值和待确认参数保持未发布null，而非复制演示默认值。

运行 `python tools/dependency_template_check.py` 只校验6份例子的形状、引用为空、键存在、固定/可配置分组和禁用标志。成功输出shape_valid=true同时固定ready=false、execution_authorized=false；不会验证外部连通性或批准任何配置。后续填入真实引用应走对应安全绑定/审核发布流程，不能把这份例子改成非空就声称运行准备完成。

## 不能归咎于外部依赖的代码缺口

以下仍需实际开发，未改标完成：

1. CMDB：可信引用解析、配额/去重事务、新字段持久化与旧类型转换；15/16类、fw_adc、SSH样例及备注冲突仍须明确映射。
2. 监控：统一接入任务、已发布模板/OID消费、恢复采样观察窗；不同厂商真实覆盖另验。
3. 工单/SLA：正式模型激活、外部建单补偿/复发关联、完整通知与对账审批；配置或供应商缺失只阻塞对应执行，不代表其他代码已经完成。
4. 通知：路由持久化/Worker、回执认证、未知结果查询与死信管理；纯路由计算不是发送。
5. 备份：执行锁/Worker、版本存储、失败工单和演练记录仓储；日期预览不是备份成功。
6. 远程：路线确认前仅接口/授权审计设计、模拟及验证方案；合同明确M7不得正式排期，更不能真实建连。
7. 报告：指标统一快照、月报/体检、PDF与H5同源、正式版本发布和阅读记录；HTML离线草稿不是客户交付。
8. 经营/租户：首页及真实聚合、成本/FTE来源、异步开通与原子配额、物理隔离、保留治理仍未齐；估算不能冒充账单，删除不能因填表自动执行。
9. 验收：真实浏览器矩阵、辅助技术、规模/故障/部署与128项合同TC仍需独立执行，当前正式TC均NOT_RUN。

现有可用入口的恢复/隔离/可读性已持续补充并逐步合main。后续应优先推进有确定来源和可验证结果的切片，不靠无限新增空入口或把全部缺口标“外部阻塞”掩盖未实现代码。

## 备份加密基础增量

[标准JCE加密信封](backup-envelope-encryption-20261021.md)已有可执行代码与独立向量测试；新增5个加密profile/Provider/授权/不可变密钥版本/技术上限引用仍为null，模板不等于运行配置。实际KMS适配、对象写入和版本事务仍是待开发链路，没有用模板数量冒充完成。

[加密对象存储协调与SDK视图](backup-envelope-archive-20261021.md)已有代码及本地协议验证，archive授权和桶治理引用仍为null；桶治理不得由程序擅自关闭版本控制、扩权或设置删除规则。真实云端验收、版本登记及耐久任务仍是独立未完成项。

[原加密信封持久化及追加式归档任务](backup-archive-staging-20261021.md)已补，并通过隔离V1–V48迁移及原子性/并发/未知结果核验；任务发现与消费者、版本元数据、真实授权/供应商证据、容量与保留清理仍待，不自动启用上传。

[V49版本登记与运行完成事务](backup-version-registration-20261021.md)已实现核心和隔离验证；登记授权、采集完成证明及装配仍为null，已有原对象验证依赖须真实实现。不把历史登记视作当前对象健康，不启用设备、保留删除或自动失败工单。

[备份版本分页查询](backup-version-inventory-20261021.md)已有独立授权仓储核心及隔离验证，备份依赖引用现为29项null；新增列表授权不继承草稿权限，服务装配、下载/解密与真实权限适配仍待。

[回执逐条追加日志格式](delivery-receipt-journal-20261021.md)可独立验证原命令和有序历史，尚非数据库仓库。已有receipt_history_repository_reference、receipt_journal_authorizer_reference、receipt_provider_correlation_reference、receipt_callback_authenticator_reference等引用继续null；操作者/权限版本仅记录，不作为授权或验签证明。

[V50回执日志仓库](delivery-receipt-postgres-20261021.md)已有显式构造实现；生产receipt_history_repository_reference和receipt_journal_authorizer_reference仍未配置。仓库要求注入操作授权及可信原请求/回执前置核验，合成测试回调不得移作默认允许；签名回执入口、供应商映射、Worker和真实发送许可仍独立待完成。

[备份版本只读 HTTP 边界](backup-version-inventory-api-20261021.md)接独立授权仓储及安全元数据投影，未装配明确503；增加只读仓储工厂、实际回环HTTP合成验证，真实授权适配及资产详情前端仍待，不开放下载/解密/设备执行。

## 2026-10-22 资产备份只读前端接线

已具备资产详情面板、原页分页恢复、取消及独立授权错误提示。backup_version_inventory_binding_reference 与独立授权引用仍为 null，实际授权适配/锁边界须显式装配；前端 assets:read 可见不授予备份枚举。前端未配置提示不代表此依赖已完成，浏览器/生产联调仍待。

## 2026-10-22 归档单次消费者

[原信封归档单次消费者](backup-archive-dispatch-consumer-20261022.md)已有显式执行与只读核查恢复代码，archive_dispatch_consumer_binding_reference仍null；31项引用槽均空，执行/生产批准false。绑定引用不是授权证明，真实Authority/原对象核验/KMS/存储、调度与版本登记消费者仍需分别装配验证。
