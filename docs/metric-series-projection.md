# 指标点标准化与端口展示基础

## 范围

对应开发设计M4-D02及合同M4-2/M4-3的纯核心。没有新增真实指标API、数据库表、监控绑定、Provider凭据、事件规则、现场设备采集或曲线页面。真实资源池/monitor_object_map和厂商映射审批尚待实现；API仍0.44.0，现有首页仍UNKNOWN，不能用合成输入填充首页或台账。

## MetricSeriesProjection

- 输入显式tenant/asset/平台metric_key、闭区间from/to、参考观察时刻、可空点集以及可空单位映射；from<to<=observed_at。点来自明确适配器输入，但核心不核验授权、来源真实性或映射审批
- 原十进制值、原Unicode单位、来源点标识与来源记录时间保留；明确转换为value×multiplier+offset，使用BigDecimal精确运算，结果规范十进制字符串。拒绝NaN、指数、隐含单位、前导零/空白与负零；不使用double或四舍五入为整数
- 未提供点集null、明确空集合[]、集合内显式未知点分别保留。未知点必须有原因且值为null；来源缺值记录的时间不冒充新采样，sourceRecordedAt保留但sampledAt为null；不复制前一个值填空。原值已知但单位映射缺失时保留原值/原单位/样本时刻，标准值null，不猜恒等转换
- 不同租户/资产/metric混合、跨区间/未来、乱序、重复sourcePointId全部拒绝；不静默排序、去重、丢弃错误点。同一时刻不同来源点保留，供后续确定来源语义，未自行平均
- 最多10000点、原十进制整数/小数各40位是资源安全界限，不是客户配额。超点数413，不自动聚合/截断；聚合口径需后续明确。所有元数据有界，结果点集不可变
- quality=SAMPLE_PRESENT仅表示显式原值经给定映射得到数值，不证明实际监控。source固定EXPLICIT_ADAPTER_INPUT_NOT_VERIFIED_MONITORING，sourceVerified/mappingApprovalVerified/authorizationVerified/writesPerformed=false，monitoringReady/ruleVersion/effectiveRule=null，availability=UNKNOWN

## PortUtilizationDisplay

合同8列顺序固定且不可变：端口、状态、速率/双工、带宽利用率、入/出流量、错误包/丢包、VLAN、描述/对端。颜色只使用合同明确的严格边界：>80为RED，>50为YELLOW，其他为NO_HIGHLIGHT；null为UNKNOWN。50和80分别保持非高亮/黄，不因浮点舍入越界。超过100的明确值不静默截断，仍红；这不是采集真实性验证。没有把非高亮称为“正常”，不改变告警规则或Q06阈值，也不从计数器自动推导速率/利用率分母。

## 验收边界

合成输入纯单测不能替代真实监控或样本历史曲线。部署/数据库/Provider/实际API/浏览器与全部正式合同验收仍NOT_RUN。后续需先完成对象映射授权、版本化单位/OID规则与真实采集接入，再将该核心接入查询；不能把现有元数据或技术种子冒充实时采样。

最终24新增纯单测、Java根2284报告/1503执行/781跳过、125契约、146Python通过。此批未改Web/契约接口，未重跑Web；最近页面1608测试不算本核心实际监控证据。见[核心验证](evidence/niko-metric-series-core-20261005.json)。
