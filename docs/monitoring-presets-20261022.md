# 可配置监控预设与单次探测（2026-10-22）

## 范围

预设目录 config/monitoring/presets.v1.json 为项目自定义轻量适配目录，不复制第三方整套模板或默认告警规则。包含 Linux Node Exporter、Windows Exporter、网络 SNMP Exporter、HTTP/HTTPS、TCP、ICMP Blackbox、PostgreSQL、MySQL、Redis、cAdvisor，以及 Zabbix Linux / Windows agent 与通用网络 SNMP 共13种。各条目保留官方/维护者来源链接与核对日期。

- Prometheus 分支：为启用且参数完整的目标输出 scrape_configs，Blackbox 输出三个独立模块；按目标租户/资产/接入ID标记，不自动加入 Alertmanager、告警阈值或客户SLA规则。JSON输出是YAML的子集，可作为明确审核后的Prometheus配置输入，不会自动合并或覆盖现有配置。
- Zabbix 分支：保留现有系统，生成明确的模板名称交接信息，模板ID、组ID和凭据引用保持null。尚无实际模板查找/绑定/主机创建消费方，不能以配置生成冒充主机已接入。
- SNMP认证使用已在SNMP Exporter端配置的命名auth profile，不写community/password；缺失时拒绝校验。型号/固件/OID覆盖仍须设备资料和实测确认。
- 示例 deploy/examples/monitoring/targets.disabled.json 全部关闭，采集周期null；建议端口仅用于明确配置，非自动扫描。启用必须显式填写5–3600秒采集周期，单次超时1–10秒且小于周期。技术边界不是客户生产业务默认值。

## 操作

Python3标准库即可运行，不需要浏览器或新云任务。

```
python tools/monitoring_presets.py catalog
python tools/monitoring_presets.py validate --config deploy/examples/monitoring/targets.disabled.json
python tools/monitoring_presets.py render --config <审核后的配置.json> --format prometheus
python tools/monitoring_presets.py render --config <审核后的配置.json> --format blackbox
python tools/monitoring_presets.py test --config <配置.json> --target-id <接入ID> --allow-endpoint <精确配置端点>
```

SNMP/Blackbox测试还需 --allow-target <精确下游目标>。此授权清单由命令调用者显式提供；校验和导出不联网。配置文件必须保存在受限/忽略目录，配置中不接收密码、令牌或额外字段。CLI自身不调用现有LAN私有连接助手，不扫描、不读私有凭据、不写供应商配置，不自动启用或部署。

端点须为显式IP和端口，不进行DNS解析；拒绝URL凭据、查询、片段、非法路径、链路本地/元数据/组播/未指定地址。当前没有FQDN、mTLS或HTTP凭据消费者，这些需求必须单独实现而非关闭证书验证。HTTP下游仅明确IP URL；SNMP/ICMP为IP，TCP为IP:端口。

## 单次测试含义

- Exporter：严格200、允许的文本类型、未压缩且至多2MiB；核验至少一个预设特征指标。PostgreSQL/MySQL/Redis及Blackbox还要求唯一的成功指标等于1。
- 拒绝HTML登录页、重定向、非有限必需值、畸形/重复标签和重复序列、异常/截断响应；设置整体连接/响应期限，无重试。标准OpenMetrics的高级类型若不受当前解析器支持会失败，不静默忽略。
- 返回样本数量和匹配指标名称，不返回标签、原始值、响应正文或底层错误。值为0和失败明确区分；缺失不补0。
- Zabbix agent当前只测试TCP连通，状态明确为 TCP_REACHABLE_PROTOCOL_UNVERIFIED；不是agent认证或采样成功。Zabbix SNMP交接项为NOT_RUN，需实际配置的Provider才能测试，不用TCP代替UDP/SNMP。
- 所有结果 continuous_collection_verified、device_identity_verified、health_verified 均false。SAMPLE_VERIFIED_ONCE只是单次格式/特征指标核验，不证明真实设备身份、持续采集、业务健康、告警或历史入库。

## 验证

37项新Python方法含10项实际回环HTTP/TCP测试，其余为配置/指标解析及子场景；完整工具测试174项通过，Node契约179项通过。首次全量因当前Python缺少既有LAN测试依赖paramiko导入失败，使用已有隔离依赖目录后重跑通过；未运行LAN更新操作，日志中的rollback为既有模拟测试。

有界响应修正中，HTTP连接关闭后错误设置已关闭socket曾导致4项正常响应回归失败；改为先检查HTTPResponse关闭状态，并核验未完成Content-Length，最终重跑通过。没有真实Zabbix、Prometheus、SNMP设备、数据库exporter或Windows主机集成证据；本地响应均合成。promtool和真实厂商服务未运行，不将JSON生成当作这些组件验收通过。

本切片不更改API、数据库、页面、既有监控绑定或服务器。后续仍需前端配置工作区、实际服务侧授权/配置消费、持续采集与历史展示接线；不能把预设目录视为整个核心监控已完成。V1–V50保持不变，合同TC不调整为DONE。

## 主要来源

- Zabbix7.0模板：https://www.zabbix.com/documentation/7.0/en/manual/config/templates_out_of_the_box
- Zabbix网络SNMP：https://www.zabbix.com/fr/integrations/snmp
- Linux指标：https://prometheus.io/docs/guides/node-exporter/
- Windows指标：https://github.com/prometheus-community/windows_exporter/blob/master/docs/collector.cpu.md
- 多目标探测：https://prometheus.io/docs/guides/multi-target-exporter/
- SNMP Exporter：https://github.com/prometheus/snmp_exporter
- 配置格式：https://prometheus.io/docs/prometheus/latest/configuration/configuration/
- 其他Exporter维护者地址逐项见预设目录。模板名只是待核对候选，绝不猜测安装实例中的ID或版本兼容性。
