# 监控对象映射输入核心

2026-10-07，对应 M4-D01/M4-D05 的前置模型。合同要求监控池+对象类型+外部ID唯一，hostid不能脱离资源池解释；本切片只判断显式提供的输入是否一致，不查询数据库或真实监控源。

## 精确键与快照

MonitoringObjectMapping.Key 包含 poolId、HOST/ITEM 和 externalId。外部ID逐字保留，不按整数转换、大小写、名称、IP或事件ID猜测，也不跨池/类型回退查询。技术输入上限为256个UTF-16单元；拒绝空白、首尾空白、控制字符及不完整代理对，合法UTF-8字符保持原样。这个限制不代表所有厂商的实际ID格式都已经验证。

Snapshot包含明确的修订UUID、租户、资产和active输入标志；它是调用方给出的不可变资料，不证明修订当前有效、同租户外键存在或经过批准。Claims字段可以缺失，始终属于未核验声明。SnapshotLookup只定义未来按精确键查询的接口，查无结果用空集合，查询失败必须抛错，不伪装成空集合；目前没有实现。

## 失败关闭与结果

assess不执行I/O；它复制输入列表后检查：多条候选为AMBIGUOUS，不选择第一条、最新修订、active条目或恰好匹配声明的一条；没有候选为NOT_FOUND；返回了其他键为LOOKUP_KEY_MISMATCH；未启用输入为INACTIVE_INPUT。声明缺租户或资产为INCOMPLETE_CLAIMS，不从映射补造来源声明；声明与映射不一致为CLAIMS_MISMATCH。

只有完整声明和唯一、同键、active快照完全一致才为CONSISTENT_INPUT，并可读取该候选快照。其他结果不返回映射对象内容；所有结果的sourceAuthorized、currentMappingVerified、dispatchAllowed、durablyAccepted均为false。结果不提供自动构造RawMonitoringEvent.Binding的快捷方法，不接入已存在的存储协调器；不可将纯计算结果替代来源认证、最新映射查询或耐久回执。

Key/Claims/Snapshot/Result的日志表示均隐藏标识与修订。输入集合后续变化、新修订和调用方重用不会改写旧结果；候选数量在复制后再次检查，避免复制期间出现第二条记录却选择第一条。

## 验证边界

测试覆盖同外部ID的跨池/host-item隔离、逐字标识、缺失/不完整/跨租户/错资产、失效/重复/冲突候选、修订与集合不可变、复制时集合大小改变、非法输入和日志隐藏。具体数量与完整回归见[证据](evidence/niko-monitoring-object-mapping-20261007.json)。

没有新增SQL、公开API、真实池注册、来源签名、数据库查询或映射变更/撤销机制；V1–V30冻结不动。后续真实当前映射仓储必须另行验证同租户关联、唯一性、并发修订/撤销及读取时效。缺失来源签名协议或客户配置不得以任意安全窗口/默认值补齐；M4-2/M4-14和128项正式验收继续NOT_RUN。
