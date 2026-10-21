# 华为 S5731 MIB 核对与只读 SNMP 联调

## 实际团体字替换后的复测（2026-10-19）

用户已明确提供实际团体字。仅更新Git忽略的`deploy/private/huawei-snmp.json`，不在源码、文档或报告中记录其值；本轮不再使用原文档示例。保持SNMP v2c、192.168.1.2:161及固定主机信任，从指定服务器和既有Zabbix网络命名空间各发一个身份GET，均为2秒`TIMEOUT_NO_RESPONSE`，没有GETNEXT、身份或资源首值。诊断源SHA256核对一致，真实只读证据见[实际参数复测](evidence/huawei-snmp-actual-credential-probe-20261019.json)。

“实际团体字尚未提供”已解除，但设备上的团体权限、MIB视图/ACL及SNMP监听配置仍未核验；不能由无应答推断团体字错误或正确。结合上轮用户截图，Polling v2c启用与目标IP归属Vlanif192/up/up保持已确认。设备monitoring_ready=false。下一步读取设备SNMP统计及协议配置，区分收包、认证/权限/编码拒绝和响应路径；未尝试其他秘密、修改监听/ACL、运行SET或安装替代组件。

## v2c 与目标接口确认后的复测（2026-10-19）

用户最新三张 CLI 截图确认 Polling v1/v2c=enable、v3=disable，Vlanif192 的地址为192.168.1.2/24、Physical/Protocol 均up；不再将“v2c未开启”或“目标IP不属于Vlanif192”作为当前阻塞。此证据来自用户截图，未通过设备SSH或SNMP验证身份/固件；实际生效的SNMP源接口命令仍只有较早网页证据，不能扩写为完整配置已核验。截图指纹与提取事实见[观察记录](evidence/huawei-snmp-v2c-enabled-observations-20261019.json)。

仍用原私有文档示例参数，指定服务器与既有Zabbix网络命名空间各发一个只读GET，二者均2秒`TIMEOUT_NO_RESPONSE`，没有GETNEXT或资源首值。见[本次真实复测](evidence/huawei-snmp-v2c-enabled-probe-20261019.json)。设备monitoring_ready=false；当前需要核对实际只读团体字是否匹配、团体MIB视图与ACL、SNMP监听及请求/响应路径。没有试探其他团体字或改变设备配置。已向用户确认实际团体字与示例的一致性，秘密仍只走私有配置。

设备侧下一步只读检查可使用`display snmp-agent statistics`，查看接收总数、团体名未知/权限错误、ASN.1或BER解码错误、GetRequest/GetResponse等计数；累计值不单独证明本次请求，需对比受控探测前后变化。另可用`display current-configuration | include snmp-agent.*protocol`核对源接口和协议监听命令，不要求输出含秘密的完整配置。统计语义参照[华为SNMP命令说明](https://info.support.huawei.com/enterprise/zh/doc/EDOC1100459421/dab469bd)；不依据其它版本说明宣称现场所有命令/能力已执行。Trap关闭与主动GET的统计分别保留，本轮不要求开启Trap。

## 截图后复测更新（2026-10-19）

用户补充的命令行截图显示拍摄时 Polling 的 v1/v2c/v3 全为 disable；网页截图显示代理 ON、v1/v2c 勾选、源接口选择 Vlanif192，且有“应用”按钮。截图先后及是否应用未知，不能推定网页显示已代表当前运行状态，也不能仅凭 Vlanif192 名称推定其 IP 为 192.168.1.2。依据华为源接口命令说明，SNMP 绑定后仅在对应接口地址接收/响应请求，应先核对目标 IP 的归属接口和当前启用版本。

保持原有固定目标、私有示例参数和只读范围复测：指定服务器与 Zabbix 网络命名空间均为 `TIMEOUT_NO_RESPONSE`（每处2秒、一个GET、没有GETNEXT），不同于先前 errno111 的端口拒绝。超时不能证明端口已开放、代理已经开启或认证已经通过；接口绑定、团体字/只读视图、ACL及路径丢弃均未排除。当前设备 monitoring_ready=false，首值/历史仍未取得，当前阻塞状态为 `SNMP_NO_RESPONSE`。最新证据见[截图后复测](evidence/huawei-snmp-after-ui-screenshot-20261019.json)；下文端口拒绝记录保留原时点，不作为本次最新返回结果。

下一步设备侧先点击网页“应用”并重新查询 `display snmp-agent sys-info version`、`display ip interface brief`，确认 Polling v2c 和192.168.1.2归属接口后，再核对只读团体及来源授权。本轮未远程操作网页或更改交换机配置。技术参照：[华为 SNMP 命令说明](https://info.support.huawei.com/enterprise/zh/doc/EDOC1100459421/dab469bd)，其中源接口绑定及版本查询的语义用于排查，不据此声明设备实际固件已核验。

2026-10-19，用户提供本地 `../doc/doc/华为S5731-MIB库-V200R022`，并明确要求“你帮我查看，按文档实例测试”。本轮据此仅对清单内 `192.168.1.2:161/UDP` 使用文档示例的 SNMP v2c 参数做只读诊断。示例参数单独保存在 Git 忽略的 `deploy/private/huawei-snmp.json`，不是已核验的设备真实凭据；本记录不复制团体字。

**结论：设备基础网络可达，但两个采集来源均得到 UDP 161 不可达/拒绝，尚未读到设备身份或任何 SNMP 指标。** 状态 `UDP_PORT_UNREACHABLE`，设备 `monitoring_ready=false`。不能凭端口错误判定团体字正确或错误，也不能把 ICMP/TCP 成功、MIB 核对或候选解析成功作为 SNMP 接入完成。

## 指定环境与实际请求

复用 LAN 测试服务器 `192.168.1.215`、既有 Zabbix 7.0.31 及其网络命名空间。固定 SSH 主机信任、项目归属、容器归属均核对。前后盘点均为 14 服务运行、PG17.9/V38、应用 ready=true、production_ready=false。应用 ready 是原有依赖的状态，华为 SNMP 详细指标尚未启用；本轮没有把该失败依赖接入全站就绪检查，也没有将其显示为已就绪。

|来源|ICMP|TCP 22/80/443|SNMP v2c GET|
|---|---|---|---|
|指定测试服务器|2/2 回应|均开放|`UDP_PORT_UNREACHABLE`，Linux errno 111|
|既有 Zabbix 网络命名空间|2/2 回应|均开放|`UDP_PORT_UNREACHABLE`，Linux errno 111|

SNMP 首次请求是一个 GET，含 `sysDescr.0`、`sysObjectID.0`、`sysUpTime.0`。未得到有效 SNMP 应答，故没有继续请求厂商私有表，不猜实体索引、不读取首行冒充整机 CPU。第一次诊断只记录通用传输失败，随后补充无秘密的 errno 分类，两轮复核均得到 errno 111；三轮合计六个 UDP GET 请求，GETNEXT 为零。不能据此定位拒绝一定发生在交换机还是中间访问控制，更不能把 UDP 错误等同于 SNMP 认证应答。

服务器没有 snmpget/snmpwalk/snmpbulkwalk 命令。本轮没有安装软件；使用标准库实现的有限 GET/GETNEXT 一次性诊断，仅有固定目标、2 秒 UDP 超时、数据包/字段长度限制、request ID/版本/团体字/OID 校验。它不是生产协议栈、常驻采集器或 Zabbix 原生监控项。第二来源仅借用原容器网络命名空间运行宿主机 Python，不能写成 Zabbix 已采到指标。

原始记录：[初次诊断](evidence/huawei-snmp-example-probe-20261019.json)、[错误分类复核](evidence/huawei-snmp-example-probe-classified-20261019.json)、[最终诊断](evidence/huawei-snmp-example-probe-final-20261019.json)、[基础网络](evidence/huawei-snmp-network-20261019.json)。基础网络报告内的 SNMP 子段仅表示该 ICMP/TCP 工具本身没有发送 SNMP 请求；SNMP 状态以独立最终诊断为准。

## MIB 来源和映射修正

提供的目录含 35 个华为 MIB 和 30 个标准 MIB。README 明确说明来自 LibreNMS 社区镜像，并非华为官方、严格锁定 V200R022C00SPC500 的分发包。目录名和说明中的型号/固件是声明，实际设备尚未通过 sysDescr/sysObjectID 核验。本轮记录 65 文件的 SHA256，逐一核对候选中七个对象的本地定义/赋值链；未执行完整 MIB 编译，未下载或执行包内脚本。

|对象/问题|本地原文核对结果与处理|
|---|---|
|hwEntityCpuUsage / hwEntityMemUsage|0..100 的百分比，按 entPhysicalIndex 区分实体；不是任取第一行|
|hwEntityUpTime|原 MIB UNITS 为 seconds，速查表的 centiseconds 不适用；不与 sysUpTime 的 TimeTicks 混用|
|hwEntityOpticalRxPower / TxPower|原值扩大 100 倍，候选使用 raw × 0.01 得到 dBm，保留负值|
|hwEntityOpticalTemperature / Voltage|原文明确 2147483647 无效；不能显示为真实温度/电压。这两项尚未放入七项数值候选|
|接口流量|优先准备 IF-MIB Counter64 的 ifHCInOctets/ifHCOutOctets，保留精确累计 bytes；速率必须基于同 ifIndex 的相邻实际采样，并检查 uptime/discontinuity，不能把一次读数当 bps|
|ifOperStatus|存在 1..7 的完整枚举，原速查表仅列 1/2 不完整；未知、测试、组件缺失等不能压成正常|
|PoE 整机功率|这份 hwPoePowerInfoDeviceUsedPower/TotalPower/RemainPower 定义未明确单位，不沿用速查表的 W 作为已验证单位；本次不加入数值候选|

[来源指纹及七项核对](evidence/huawei-snmp-mib-review-20261019.json)。原始提供目录保持不变，修正结果写入项目候选和本记录。

复用已有 `VendorMappingYaml`，增加[七项未发布候选](../deploy/device-snmp/huawei-s5731-v200r022-candidate.yaml)：CPU、内存、实体 uptime、接收/发送光功率、64 位入/出字节计数。现有候选解析器的 `effective`、`sourceVerified` 仍为 false；上下界是 MIB/ASN.1 数值范围，不是客户告警阈值。候选格式尚不表达实体选择、索引关联、无效值过滤和计数器差分，不能直接激活。未来接入必须先补齐这些步骤及实际设备样本，不能把此文件当作可直接导入的 Zabbix 模板。

## 复验入口及接口边界

私有文件必须显式包含且只包含 target、version、community，当前工具仅支持已授权的固定地址与 v2c；v3 需要真实参数和成熟采集端配置，不回退为 v2c。工具不会从 README 自动读取或尝试其他凭据。此次私有配置创建由用户明确授权的文档示例产生。

```powershell
py -3 tools/probe-huawei-snmp.py --output docs/evidence/huawei-snmp-retest-<唯一标识>.json
```

输出必须是 evidence 下的新文件，不覆盖既有成功或失败记录。退出 2 表示未取得可验证身份/缺配置，1 表示工具执行失败；即使退出 0，也仅表示两处读取了华为身份，不表示持续采集/业务验收完成。指标 GETNEXT 仅作为每列第一个可返回实例的原始诊断；保留 index，列外结果标为未知，不汇总成整机指标。

本轮实际 API 0.58.0、OpenAPI 和应用运行配置不变；没有新增业务端点、数据库迁移、Zabbix 对象、告警或通知，没有部署/重启、设备 SET 或设备配置命令。诊断脚本的私有输入不是对浏览器开放的接入 API。完整通用接入、持续历史及失败恢复仍按[接入要求](device-monitoring-snmp-api-requirements.md)和[静态差距报告](device-monitoring-compliance-review-20261019.md)保留未完成。

## 验证和解除阻塞

55 项 Python 隔离回归通过（含新增 20 项），覆盖协议响应核对、错误分类、无响应停止、固定目标/凭据边界、无 SET、负数和 Counter64 精度；46 项 Java 候选解析/换算测试通过（含新增 5 项）。这些不算实际设备采集验收。首轮 PowerShell 参数解析错误及一个 BER 溢出测试向量的符号补位错误已修正，记录在[分层验证](evidence/huawei-snmp-validation-20261019.json)，没有把失败计作通过。

需要设备管理员在交换机上核对 SNMP 代理启用状态、协议版本、只读视图及来源 ACL，并确认到 UDP 161 的实际路径/出口；文档示例不能替代实际配置。可先在设备侧执行只读 `display snmp-agent sys-info` 核对，不把含秘密的完整配置粘贴到公共记录。当前没有设备 SSH 登录凭据，也没有本轮设备配置变更授权，故未代为开启 SNMP。真实只读参数仍放入私有配置。接通后先复验身份和固件，再发现实体/接口索引、取得实际首值，最后接入原 Zabbix 持续采集并独立验证历史、故障恢复和租户读取。

正式生产版本适配、告警规则/阈值、客户批准与全部 128 项验收未因本轮资料核对解除。组件复用前后变化核对见[最终汇总](evidence/huawei-snmp-summary-20261019.json)。

技术参照：[Zabbix 7.0 MIB files](https://www.zabbix.com/documentation/7.0/en/manual/config/items/itemtypes/snmp/mibs)，使用数字 OID 的采集不要求为本次诊断修改共享 MIB 搜索路径。
