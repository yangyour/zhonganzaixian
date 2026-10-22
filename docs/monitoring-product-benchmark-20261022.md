# 监控产品借鉴与后端落地清单

核对日期：2026-10-22。范围是后端设计、接入编排和成熟采集协议，不复制六套产品或其商业功能。用户接手前端；本清单不授权修改其界面。调研不等于已实现，更不等于真实设备验收。

## 借鉴对象与取舍

- **Zabbix**：模板复用、低层发现原型和集成目录。借鉴版本化集成定义、必要参数、前置条件、设备子资源展开；不把展示名/IP猜成资产身份，不自动删除消失资源。[模板](https://www.zabbix.com/documentation/current/en/manual/config/templates)、[低层发现](https://www.zabbix.com/documentation/current/en/manual/discovery/low_level_discovery)、[集成目录](https://www.zabbix.com/integrations)。
- **Prometheus**：目标文件发现、配置/规则校验和独立告警治理。先沿用成熟Exporter与受控目标清单，再补官方工具验证；标签不是租户授权边界，Blackbox/SNMP后端不能成为任意地址代理。[file_sd](https://prometheus.io/docs/guides/file-sd/)、[promtool](https://prometheus.io/docs/prometheus/latest/command-line/promtool/)、[安全模型](https://prometheus.io/docs/operating/security/)。
- **Grafana Alloy / OpenTelemetry**：配置验证、组件健康、接收/处理/导出分段诊断。优先借鉴可解释诊断和明确服务身份，不同时引入两套采集运行时，不凭不可靠标签制造实例ID。[Alloy validate](https://grafana.com/docs/alloy/latest/reference/cli/validate/)、[Collector排障](https://opentelemetry.io/docs/collector/troubleshooting/)、[服务身份](https://opentelemetry.io/docs/specs/semconv/resource/service/)。
- **Checkmk**：批量服务发现、待确认变更及角色/资源范围分离。落地为接入计划、逐项错误、稳定ID、差异预览与独立激活，发现不自动获得写入或扫描权限。[服务发现](https://docs.checkmk.com/latest/en/wato_services.html)、[主机组织](https://docs.checkmk.com/latest/en/hosts_structure.html)、[权限](https://docs.checkmk.com/latest/en/wato_user.html)。
- **Netdata**：低参数配置、采集器调试和配置测试后下发体验。其动态配置管理的当前创建/编辑/测试流程依赖付费Cloud，不能宣称为本项目免费内嵌能力；只借鉴流程设计。[采集器配置](https://learn.netdata.cloud/docs/collecting-metrics/collectors-configuration)、[服务发现](https://learn.netdata.cloud/docs/collecting-metrics/service-discovery)、[动态配置](https://learn.netdata.cloud/docs/netdata-agent/configuration/dynamic-configuration-manager)。
- **LibreNMS**：设备指纹与网络邻居发现。后续优先处理明确输入的sysObjectID/设备声明，生成模板候选；邻居不能递归突破批准范围。[设备识别](https://docs.librenms.org/Developing/os/Initial-Detection/)。

## 实施顺序和当前真实状态

| 批次 | 能力 | 状态与边界 |
|---|---|---|
| 已有 | 13个场景目录、单次受控测试、租户/资产/权限复核 | 已有代码及本地合成测试；真实设备持续采集未验证 |
| 当前 | 清单批量生成、同设备多服务、稳定逻辑ID、只读差异预览 | 本切片实现；离线生成，不创建资产、启用、删除或应用配置 |
| 当前/下一步 | 官方配置校验、可解释就绪诊断、目标文件发现 | 已用官方promtool核验配置；现已实现分组file_sd、差异/重载提示和分阶段诊断，并以官方Prometheus 3.15.1实测loopback持续采样、动态目标、失败恢复与标签边界；生产发布器仍未接线 |
| 当前/后续 | 批量接入REST计划与当前资产核验、受控发现候选 | 已新增目录和准备API，地址来自当前授权资产并复核快照，采集器按租户隔离；仅准备不注册，受控发现候选仍待实现 |
| 后续 | 一条持续采集路径、最后尝试/成功/样本时间、配置期望/实际版本 | 本地官方引擎持续采样已验证；MOP读取接口、真实设备/历史接线和实际配置版本仍待完成 |
| 后续 | 子资源发现、变更确认、stale生命周期 | 待实现；先标记和预览，不自动永久删除 |
| 后续 | 告警分组、路由、维护/静默和抑制适配 | 先复用并核对项目现有事件中心、维护窗口、降噪能力，再补缺口，不重复造轮子；真实通知仍需配置和验收 |
| 后置 | 分站点采集、租约/背压/高可用、日志与链路 | 先完成指标接入闭环，再按实际需求扩展 |

## 工程约束

- 领域模型、编排、模板参数与诊断自行实现，通过协议/API或独立进程复用成熟采集器。
- 如需复制模板、规则、MIB或代码，另行核对具体来源、版本、许可证、NOTICE及依赖，不因某产品开源就假定其UI、Cloud或商业版本可复制。参考[Zabbix许可](https://www.zabbix.com/license)、[Netdata许可说明](https://github.com/netdata/netdata/blob/master/README.md?plain=1)。这不是法律结论。
- 缺少厂商凭据、真实设备、模板ID、来源认证与客户参数时保留未配置/未运行状态，不设置虚构业务默认值。
- 不自动扫描子网、创建长期凭据、替用户批准地址、下发设备配置、触发真实通知或迁移数据库。V1–V50不变，正式128项合同TC仍NOT_RUN。
