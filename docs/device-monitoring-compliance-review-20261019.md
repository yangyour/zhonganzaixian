# 设备监控接入要求只读检查报告

检查日期：2026-10-19。检查基线：本地分支 `feat/d07-ticket-lifecycle`，HEAD `5907d779f1bb044d8daeb731ac8ed0b5bb368df5`，静态留证时的实际 API 契约版本 `0.58.0`。工作区另有未提交开发改动，本报告基于检查时的本地文件，不将其视为已部署版本。

**结论：项目部分符合 SNMP 与 API 监控接入要求，已有基础网络监控及防火墙专用 API 采集，但尚未形成通用、可配置、可验收的设备接入完整流程。** 技术方向正确，不等于全部功能已完成。

本次依据[接入与 MIB 资料要求](device-monitoring-snmp-api-requirements.md)、既有合同设计、实际源码、OpenAPI 和已保存的测试证据进行静态检查。只新增说明、报告和静态检查记录；不修改业务代码、契约、SQL 或运行配置，不运行迁移、部署、设备认证或采集。已有真实证据保留其原日期，不作为 2026-10-19 的实时可用性证明，也不替代全部 128 项合同验收。

## 符合性矩阵

|要求|检查结论|依据与实际边界|
|---|---|---|
|复用现有采集与历史组件|已有历史验证|基础监控和 AF Worker 复用指定 LAN 测试环境的 Zabbix 7.0.31 及其历史库；本次未重新连接服务器核验版本。见[基础监控](basic-network-monitoring.md)、[AF 交付](firewall-resource-monitoring.md)。|
|支持厂商 API 获取真实指标|部分符合|AF CPU、内存已有持续采集、历史和页面证据；SCP 有内部客户端，但资源读取权限及完整采集接线未完成。|
|交换机 SNMP 详细指标接入|未完成|未找到华为或信锐详细 SNMP 指标的真实首值、历史和失败恢复证据；现有基础六项为 ICMP/TCP，不是 SNMP 指标。|
|MIB 与型号固件适配|部分符合|已有品牌、型号、固件、OID、类型、单位、缩放等 YAML 候选结构；候选始终未生效、未核验，不包含真实华为适配验证。|
|统一接入配置及连接测试页面|未完成|监控控制器为读取接口；私有环境变量提供固定绑定。未找到从选择协议、绑定凭据、选择模板到测试和启用的完整业务链路。|
|凭据托管与轮换|部分符合|现有采集代码使用私有环境变量/秘密引用；实际契约未提供设计中的 `/credentials` 接口。不能把私有文件配置当作完整凭据生命周期模块。|
|模板版本发布及回滚到采集端|未完成|候选解析器无发布接线；实际契约未提供 `/monitoring/templates` 和 `/template-releases`。通用配置中心的发布记录不等于采集端已更新及回读成功。|
|采样和映射配置实际生效|部分符合|页面刷新周期已消费运行配置；采样周期作为容量估算输入不改变真实采集。基础采集检查和 AF Worker 仍按专用 60 秒逻辑运行，OID 映射未接入采集消费。|
|失败不伪造正常及时间语义|已实现限定范围|AF 失败样本不填零；当前值失效时为 null，源采样时刻为 null，接收时间另行说明。已有真实失败恢复证据，但不能推广为所有未来适配器均已验证。|
|租户隔离与受限读取|已实现限定范围|监控 GET 在访问来源前后核验当前成员、资产归属和管理地址，跨租户/未绑定 404；已有 HTTP 证据。尚无完整接入写流程可据此验收。|
|HTTPS 正式证书信任|未完成|既有 AF/SCP 联调使用限定目标的 `TEST_INSECURE` 测试例外，不能按生产严格 TLS 验收。|
|指标到告警的完整验证|未完成|AF 资源快照仍返回 `alert_rule_status=CONFIG_PENDING`。项目其他事件处理代码不能替代这些设备指标的真实规则、触发和恢复验收。|

## 主要差距及影响

### 通用设备接入尚未贯通

[NetworkMonitoringConfiguration](../server/api/src/main/java/cn/mop/api/NetworkMonitoringConfiguration.java) 读取 `MOP_NETWORK_MONITORING_BINDINGS`，通过 Zabbix 只读客户端获取数据。[NetworkMonitoringController](../server/api/src/main/java/cn/mop/api/NetworkMonitoringController.java) 提供设备列表、历史、资源和刷新策略的 GET；该类没有设备接入配置的创建、测试或启用写入口。

[实际 OpenAPI](../contracts/local-api.openapi.json) 未包含设计中的凭据、监控模板、模板发布、站点、Proxy 和监控池端点。此判断结合了源码调用检索，不单凭端点缺失推定全部内部能力不存在。现有监控界面可以展示已绑定设备，不等于新增设备能由业务人员完整配置接入。

### 专用 API 采集不能直接扩展为任意设备配置

[AfClient](../server/adapters/src/main/java/cn/mop/adapters/af/AfClient.java) 的生产代码构造固定测试设备目标；[FirewallResourceService](../server/api/src/main/java/cn/mop/api/FirewallResourceService.java) 绑定单个 AF 目标和固定 CPU、内存、状态项，并核对专用主机标识与历史设置。[AfCollectorMain](../server/adapters/src/main/java/cn/mop/adapters/af/AfCollectorMain.java) 固定 CPU/内存采集集合和 60 秒调度。

这些限制有助于保护已授权测试目标，但说明当前是专用设备切片。要达到新增兼容设备只填配置的目标，仍需设备实例配置、适配器和模板绑定、运行发布、采集消费及回读验证；本次不解除现有限制，也不修改目标地址。

### OID 候选与配置发布尚未连接真实采集

[VendorMappingCandidate](../server/domain/src/main/java/cn/mop/domain/monitoring/VendorMappingCandidate.java) 的 `effective()`、`sourceVerified()` 固定为 false；[VendorMappingYaml](../server/adapters/src/main/java/cn/mop/adapters/monitoring/VendorMappingYaml.java) 只解析候选字节。在 API/Worker 主源码中未找到该解析器的采集运行调用。

[运行配置中心](configurable-development-20261015.md) 已支持校验、批准提案后的发布、禁用、回滚和有效值读取，不能笼统说“项目没有配置能力”。但 [RuntimeConfigurationsPage](../apps/web/src/pages/RuntimeConfigurationsPage.vue) 明确区分“监控界面刷新”“容量估算参数”和“尚未接入消费方”。`device.oid_mapping`、`device.firmware` 在[登记基线](../contracts/config-baseline.json)中仍为 null；`monitor.sample_interval_sec` 的容量估算用途不能替代采集器采用。

### SCP 同时存在外部权限与内部接线缺口

[ScpClient](../server/adapters/src/main/java/cn/mop/adapters/scp/ScpClient.java) 已有认证、版本、资源池及分页读取方法；[DeviceConnectionCheck](../server/adapters/src/main/java/cn/mop/adapters/monitoring/DeviceConnectionCheck.java) 是单次检查工具，明确返回 `integration_ready=false` 和 `background_collection_enabled=false`。在本次检查范围内未找到 SCP 到业务 API、持续 Worker、历史与页面的完整调用链。

[2026-10-14 凭据证据](evidence/scp-password-verification-20261014.json)显示密码认证成功，但版本和资源池读取均被拒绝。应分别处理 SDK 只读权限、实际版本/源资源范围/可信证书，以及 MOP 采集与展示接线；不能仅补权限就宣称全部开发完成，也不能因缺权限暂停所有通用开发。

### 华为 MIB 资料仍有价值但不是唯一缺口

[设备凭据验证记录](device-credential-verification-20261014.md)记载华为、信锐当时未提供凭据；[组件清单](environment/component-inventory.md)保留固件、MIB/OID、单位和样本待核验事项。本次未读取私有配置或重新向设备认证，因此不宣称今天仍绝对缺少某份私有资料，只确认仓库内没有解除这些门禁的验收证据。

应补充准确型号/固件、对应 MIB 或已验证 OID、只读访问参数及“SNMP 不能满足”的具体指标清单。收到资料后仍须逐项验证设备支持与权限。数字 OID 模板已经可靠的项目不必等待 MIB 安装；也不要求新增 MIB 上传功能才能符合接入要求。

## 已有真实成果与历史记录修正

防火墙不是“仍停留在密码过期或所有指标待接入”。[防火墙交付说明](firewall-resource-monitoring.md)及[40 项公开入口 HTTP 检查](evidence/af-resource-http-10010-20261014.json)已记录 CPU/内存当前值、历史和权限检查；[真实失败恢复](evidence/af-resource-fault-recovery-20261014.json)记录失败时值为 null、就绪失败以及真实 Worker 恢复采样。

部分旧文档在开头追加最新结果后，正文仍保存“密码过期”“凭据未提供”“三台基础监控”等阶段描述。评估应按设备和证据时间区分；最新 AF 成功不能解除 SCP 或交换机门禁，旧阶段失败也不能覆盖后来的 AF 成功。本报告保留原始历史文件不改写。

## 后续工作划分

|可以继续开发的部分|需要设备或客户补充的部分|
|---|---|
|统一接入模型、租户/站点/资产绑定及配置页面|实际授权范围与采集位置确认|
|凭据引用、托管、访问审计及轮换流程|受控提供专用只读凭据；不在公开文档中填写秘密|
|模板/OID 候选校验、版本、发布、回滚和消费接线|型号、固件、MIB/OID、真实单位及样本|
|通用连接测试状态、首值/历史及失败显示|SCP SDK 读取权限、平台版本与源对象范围|
|按配置驱动的调度和已确认来源适配|正式采样及告警业务值、可信证书|

优先补齐统一接入和采集消费，再按已有 AF 真实链路扩展受控配置，随后用华为代表设备验证 SNMP、补齐 SCP。具体业务值未知时保留 null；可用明确标识的测试配置验证流程，不能用测试值冒充客户生产规则。本段仅记录建议，不授权本次实施代码或环境变更。

## 本次检查与验证范围

- 已检查合同 M3-D04、M4-D04、M4-D11 至 M4-D18、Q20，以及实际契约、相关 API/适配器/前端配置消费者和上述历史证据。
- 读取 `git status` 确认存在其他开发改动；本次不覆盖、撤销或提交这些改动。
- 静态解析实际 API 路径及配置键，记录关键文件 SHA256；核验 `deploy/private/` 没有被跟踪的文件且 `develop.txt` 被 Git 忽略，不读取其内容。
- 校验本次文档的相对链接、JSON 记录和补丁空白。静态检查结果不标成设备集成测试通过。
- 未运行单元/集成/浏览器测试，未连接测试服务器、设备或外部监控服务，未安装组件、修改配置、启停服务或执行迁移；没有新的实时采集或故障注入证据。

静态检查记录见[本次证据](evidence/device-monitoring-static-review-20261019.json)。本次不把任何未完成合同项改为 DONE。
