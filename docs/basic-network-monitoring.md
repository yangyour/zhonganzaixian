# IP基础监控

2026-10-12用户要求先实现无需设备凭据的基础监控，厂商资源指标等拿到文档后继续。复用192.168.1.215上Zabbix7.0.31，独立测试组`MOP LAN basic network test 20261012`仅纳入超融合192.168.1.170、华为交换机192.168.1.2、信锐交换机192.168.1.125。防火墙本日双来源复测仍不通，保持单独阻塞，不修改路由或设备配置。

## 持续采集

三台设备各6项：ICMP响应、3包丢包率、平均响应时间，以及TCP22/80/443握手，共18个simple check。每60秒由Zabbix Server执行，历史保存在已有Zabbix PostgreSQL库，测试保留7天且关闭trends；这些是明确测试配置，不代表正式客户留存/SLA规则。纯TCP检查不登录设备、不验证HTTPS证书或HTTP页面。信锐22/80返回0是探测结果，不自动生成异常告警。

ICMP延迟原单位为秒；无响应时Provider可能返回0，展示时必须结合ICMP/loss解释，不能写成“0ms正常”。每个监控项独立保留时间，旧值不作为新采样。固件、设备身份、CPU/内存/温度/带宽及SNMP授权仍未核验。

入口：`python tools/configure-basic-network-monitoring.py --output <新报告路径>`默认只读计划，加`--apply`才创建本项目组内主机/监控项。重跑先按明确名称/组/标记/配置回读，已存在且一致不重复创建，配置漂移停止而不覆盖。没有扫描、全局配置修改、组件重启或通知规则。SSH凭据只读取原私有文件，通过固定主机信任的SSH通道访问服务器回环API。

依据：[Zabbix 7.0 Simple checks](https://www.zabbix.com/documentation/7.0/en/manual/config/items/itemtypes/simple_checks)、[item.create](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/item/create)。网络探测无需在设备装Agent；详细资源数据仍待设备文档与只读授权。

## 采集切片证据

- [组件前置盘点](evidence/basic-monitoring-components-before-20261012.json)：既有12服务运行、PG17.9/V30、Zabbix7.0.31。
- [本日网络复测](evidence/basic-monitoring-preflight-20261012.json)：三台响应、防火墙未通。
- [只读计划](evidence/basic-monitoring-plan-20261012.json)、[显式创建](evidence/basic-monitoring-provision-20261012.json)、[重复核对](evidence/basic-monitoring-reapply-20261012.json)。
- [真实首值](evidence/basic-monitoring-first-values-20261012.json)：18项均取得Zabbix采样、unsupported为0；后续持续性与MOP/API/UI另行记录。

五项隔离工具测试覆盖仅读计划、同配置重复执行不写、配置漂移拒绝、版本门禁、指标键及类型。初次计划把认证头附带到不需要认证的apiinfo.version而被Provider拒绝，已修复该方法不带认证头后才执行创建。

第一次重复核对因Zabbix回读标签额外带automatic字段而停止，没有执行写入；已按tag/value与非自动标志核验归属，重新核对通过。

本批未新建/迁移MOP数据库结构，V1–V30不变。厂商文档及Q06正式告警规则仍待；全128项合同不因基础采集而标DONE。
