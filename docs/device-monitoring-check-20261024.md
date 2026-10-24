# 四设备监控接续检查（2026-10-24）

用户要求继续检查测试环境的几个设备并推进监控对接；复测后明确选择“暂时不能，先保留已接通的监控和排查记录”。本轮保留现有采集，完成只读验证、复查工具及记录；设备配置、权限、凭据、告警、通知和自动化未修改。

## 仓库与环境

项目为众安在线 MOP，模块为设备接入与监控。规范 origin `https://github.com/yangyour/zhonganzaixian.git`，完整组织路径 `yangyour/zhonganzaixian`。独立目录 `E:\work\wxw9\zhonganzaixian\.worktrees\device-monitoring-20261024`，分支 `codex/device-monitoring-20261024`，创建时上游 `origin/main`、fetch 后基线 `83432839`。依据为实际 Git 配置、设备清单、既有联调及最新发布记录。原主目录三处未提交前端文件保持。

秘密只从原项目 Git 忽略的 `deploy/private` 读取，独立目录用本地 junction 引用，不复制秘密。连接资料没有被作为指令执行。固定 SSH 主机信任和项目归属核验保持。

指定环境 `lan-test-20261008`，服务器 `192.168.1.215`。本轮实际版本：PostgreSQL17.9、Zabbix7.0.31、Redis7.4.9、RabbitMQ4.3.2、MinIO RELEASE.2025-09-07、Java17.0.20.1、Nginx1.31.6、Docker29.1.3。14个服务运行，前后镜像、启动时间、重启数及所记录状态一致；V55、ready=true、production_ready=false。没有部署、迁移、创建组件或修改业务记录。

## 当前真实接入

| 设备 | 地址 | 已验证持续指标 | 本轮详细接入结果 |
| --- | --- | --- | --- |
| 深信服 AF-1000-B1800 防火墙 | 192.169.11.1 | ICMP、丢包、延迟、TCP22/80/443；CPU/内存及内部采集状态 | API认证、版本、资源读取与注销成功；固件AF8.0.106.4646。严格TLS前后仍失败，沿用既有TEST_INSECURE，正式证书信任未完成 |
| 深信服 aServer-P-2200 超融合 | 192.168.1.170 | ICMP、丢包、延迟、TCP22/80/443 | 指定管理平台SCP 192.168.1.179认证成功，版本读取ACCESS_DENIED；未改为直连HCI，详细资源无首值 |
| 华为 S5731-S24P4X | 192.168.1.2 | ICMP、丢包、延迟、TCP22/80/443 | 原私有实际参数的SNMP v2c身份GET，从服务器及原Zabbix网络命名空间均TIMEOUT_NO_RESPONSE；身份/CPU/内存/流量仍无首值 |
| 信锐 aRS6300-50Q-EI-24X | 192.168.1.125 | ICMP、丢包、延迟、TCP22/80/443 | HTTPS可达；未发现该设备专用只读SNMP/API配置，未尝试默认参数或复用其他设备凭据 |

两处网络探测四台均有ICMP回复。防火墙22超时、80/443开放；超融合和华为22/80/443开放；信锐22/80拒绝、443开放。端口不通没有当作整机离线，也未要求开放端口。

24项网络和AF 3项（含内部状态）都有真实新样本。两轮完成时间为 **04:13:12 EDT**、**04:16:38 EDT**；网络项时间各前进180秒，资源项各前进240秒。各项最近600秒均观察到10点，最大相邻间隔60秒。这是有限窗口的采样证据，不是全天连续性、SLA或整机健康证明。

防火墙04:11 EDT单次直读为CPU7%/内存56%；第二轮持久采样15%/57%；随后MOP实际接口读取到04:17:29 EDT的11%/56%。不同时间读数分别保留，不固定为配置。时间仍是COLLECTOR_RECEIPT_TIME，设备原始采样时刻null。

## 可复查工具与接口

新增 `tools/lan_monitoring_diagnostics.py`，通过原固定主机信任SSH和既有两份受限读取token，仅调用 `apiinfo.version/host.get/item.get/history.get`。严格核对四设备、明确主机/项ID、key、类型、单位与周期。不会登录设备、使用Zabbix管理员、创建对象或启用额外采集。

```powershell
py -3.14 tools/lan_monitoring_diagnostics.py --output docs/evidence/device-monitoring-下一次检查.json
```

输出须为尚不存在的 `docs/evidence` 文件。退出0表示本轮网络样本均新鲜且AF同批资源与采集状态一致；退出2表示样本缺失/过期/失败；退出1为传输、配置或运行失败。TCP=0可以是FRESH，含义为“新收到的不通结果”。过期、缺失、未来或非法值保持null；历史重复、乱序、错对象或达到截断上限不冒充完整窗口。180秒/600秒/90秒是工具及既有测试采集的技术界限，不发布客户业务规则。

同时修复华为CLI：私有目录为指向另一个检出的junction时，探测后生成凭据引用原先会抛ValueError；现按解析后的私有根校验，只输出逻辑 `deploy/private/...` 引用。初次真实证据保留该工具错误；其中两个TIMEOUT_NO_RESPONSE是已完成探测。路径回归通过模拟外部私有根验证，未为修报告而重发设备请求。

实际API契约保持，无新增HTTP路由。既有当前值与历史仍在 `/api/v1/network-monitoring`、`/{assetId}/history`、`/{assetId}/resources`、`/{assetId}/resources/history`。

## 验证与证据

- 14项新增纯样本/范围测试及受影响的21项SNMP编解码/CLI测试，共35项通过；替身不作设备联调。
- 两轮真实受限读取均退出0，27项全部前进：[连续性与服务保持](evidence/device-monitoring-20261024/continuity.json)、[第一轮](evidence/device-monitoring-20261024/collection-first.json)、[第二轮](evidence/device-monitoring-20261024/collection-second.json)。
- [实际MOP HTTP](evidence/device-monitoring-20261024/mop-http.json)20项通过：ready、未登录401、既有真实身份、四设备当前值、8个网络历史、AF快照、4个资源历史、未知资产404和带幂等键注销。只存摘要，不存会话/凭据；点数不是测试数。
- 首轮HTTP工具注销漏传既有必填幂等键，收尾失败且未保存报告；补齐工具请求头后重跑上述20项，不放宽服务端要求，首轮不计通过数。
- [两处网络探测](evidence/device-monitoring-20261024/network.json)、[华为SNMP](evidence/device-monitoring-20261024/huawei-snmp.json)、[SCP权限](evidence/device-monitoring-20261024/scp-api.json)、[AF API](evidence/device-monitoring-20261024/firewall-api.json)分开保留。
- [前盘点](evidence/device-monitoring-20261024/components-before.json)、[后盘点](evidence/device-monitoring-20261024/components-after.json)确认14服务及V55保持。用户选择先保留，本轮无设备写入、故障注入、浏览器验收或新部署。

页面入口：[设备网络及资源监控](http://mop.all-online.com.cn/network-monitoring)，使用原真实租户；亦可用[内网入口](http://192.168.1.215:10010/network-monitoring)。本轮以实际API及采集历史为证据，不引用旧截图冒充新浏览器验收。

## 留待设备侧配合

| 依赖 | 已确认事实、影响 | 后续候选与状态 |
| --- | --- | --- |
| 华为SNMP | IP/TCP可达，两处v2c GET无回应；原因未确认，身份及详细指标不可采 | 管理员按实际固件核对只读团体/视图、UDP161监听、SNMP收发/错误计数和来源ACL。服务器本机路由源192.168.1.215；容器设备侧实际源仍需核验NAT/统计。PENDING_DEVICE_ACCESS |
| SCP SDK权限/范围 | 本轮认证通过但版本ACCESS_DENIED；旧记录有资源池403，本轮未冒称重测全部资源接口 | 核对“SDK服务：获取SCP版本信息”“SDK服务：获取资源池列表信息”，再明确pool/project范围并验证首值/历史；不自动授予权限。PENDING_DEVICE_ACCESS |
| 信锐专用只读配置 | 仅基础网络采样可用，未发现详细协议参数 | 经原Git忽略私有配置提供授权协议/只读凭据及固件/OID或API资料；不在聊天或Git写秘密。CONFIG_PENDING |
| 其他资源及正式告警 | 本次CPU/内存不证明会话/VPN/接口等已采，业务阈值未生效 | 按逐指标资料/首值/规则批准推进，未批准替代来源、不扩大通知。NOT_VERIFIED |

华为依据：[S5700/S6700 SNMP命令参考](https://info.support.huawei.com/enterprise/zh/doc/EDOC1100459421/dab469bd)、[团体错误日志核对](https://info.support.huawei.com/network/ptmngsys/Web/tsrev_s/en/content/s/32_SNMP_Interaction_Error/edesk_SNMP_Interaction_Error_edesk003.html)。具体命令以现场固件为准，不照抄示例口令/写配置。历史读取按[Zabbix7.0 history.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/get)明确类型、窗口及clock/ns排序。

本轮完成的是既有监控复核、诊断工具和阻塞记录。详细指标、采集发布/持久映射消费、正式阈值与通知、完整M4/128项合同验收仍未完成，不因有限观测标为DONE。
