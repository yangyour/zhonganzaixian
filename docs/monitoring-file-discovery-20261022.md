# 目标文件发现与官方采集器隔离验证

本切片采用[Prometheus file_sd](https://prometheus.io/docs/guides/file-sd/)机制：把静态模板参数与经审核的目标清单分开，减少接入同类设备时的配置改动。没有新增前端页面、SQL、生产采集任务或设备扫描。

## 后端工具

```sh
python tools/monitoring_file_sd.py render --config /path/to/reviewed-targets.json --refresh-seconds 5 --output-dir /path/to/new-bundle
python tools/monitoring_file_sd.py check --config /path/to/reviewed-targets.json --refresh-seconds 5 --promtool /path/to/promtool
```

示例中的刷新周期是显式演示参数，不是自动写入的生产默认值。工具配置空位见 deploy/examples/monitoring/collector-tools.env.example。

- 只为enabled目标生成Prometheus发现条目；批量工具生成的disabled配置仍不会开始采集。
- 按租户、预设及实际采集参数分组。直接Exporter的地址变化只改变目标文件；Blackbox/SNMP保留独立的下游目标和固定采集器地址，SNMP认证引用不混组。
- 输出prometheus.json、targets下的JSON清单、Blackbox模块、Zabbix待配置项及manifest.json。Zabbix条目不会被伪装成Prometheus采集任务。
- 文件名由完整SHA256形成，不接受外部路径。目标目录必须全新，现有目录/符号链接不覆盖；清单内容或摘要在写出前变化会失败。
- 输入配置摘要保留原始目标顺序；实际任务布局与发现文件经过稳定排序，不因输入顺序变化而产生无意义更新。
- 配置保持honor_labels=false、honor_timestamps=false、禁止重定向和原有响应/样本上限。设备同名标签不能替代配置注入的租户/资产/目标标签。
- 这些标签仍不是Prometheus查询API的权限边界，不能把底层API直接开放给任意租户。

## 更新预览，不自动发布

```sh
python tools/monitoring_file_sd.py render --config /path/to/next.json --refresh-seconds 5 --previous-config /path/to/previous.json --previous-refresh-seconds 5
```

返回新增/删除/修改文件及configuration_reload_required。旧刷新周期必须明确，不能猜测正在运行的参数。

- 同租户同模板同采集参数新增设备，通常只修改已有发现文件，不改变任务配置。
- 新模板、不同采集器/认证引用、周期、协议或刷新参数可能需要配置重载，报告会区分。
- 这只是两份候选输入的差异，不证明任一配置已在运行。工具没有发布、重载或删除命令。
- 生产发布仍需独立核验资产/白名单、配置期望版本、实际版本、文件完整性和回滚边界；不能把渲染成功当作授权网络访问。

## 分阶段诊断

check只生成自有临时候选文件，再以固定参数调用明确指定的本地promtool。无shell、不输出原始工具日志、15秒上限、不继承stdin；不启动Prometheus，不测试目标。

- configuration / file_sd_layout：本项目严格配置与布局检查。
- official_config_check：PASSED、FAILED或NOT_RUN。
- 未设置/找不到工具明确为NOT_RUN，不冒充通过；工具拒绝、超时、无法启动分别给出安全原因码。
- 配置校验通过仍不意味着采集器依赖、目标连通性、凭据、证书或真实身份已验证。

## 已执行的真实本地进程测试

显式选择经[官方3.15.1发布](https://github.com/prometheus/prometheus/releases/tag/v3.15.1)SHA256核验的二进制，在自有临时目录启动两个loopback合成Exporter和一个Prometheus进程：

1. 官方promtool检查生成配置，确认检查阶段没有请求Exporter。
2. 观察相同目标多次采样，值真实来自两个本地HTTP测试服务。
3. 通过原子替换自有临时发现文件加入第二个目标；没有重启或重载Prometheus配置。
4. 注入与可信租户/资产/目标同名的恶意标签，确认其进入exported_*字段，不能覆盖可信标签。
5. 让一个Exporter返回503，再恢复200；实际观察up从1到0再到1。
6. 终止采集器、关闭Exporter并清除自有临时TSDB，确认没有遗留测试进程。

这是本地合成持续采样证据，不是实际业务设备、SNMP、MOP历史库、告警、身份映射或生产发布证据。没有访问或写入JiangFaMing实际业务库。该租户的真实数据边界和既有审计保持不变。

复现需显式设置MOP_PROMETHEUS_BIN后运行 tools/test_monitoring_prometheus_loopback.py；未设置时用例跳过，不自动下载安装二进制。证据见 evidence/monitoring-file-discovery-20261022/validation.json。

## 后续缺口

- 文件发现尚未成为应用内配置发布器；新资产的登记、可信身份映射、期望/实际配置版本及真实设备启用仍需接线。
- MOP后端读取这条Prometheus数据源的状态/样本、持久历史与告警链路仍未完成。
- 不将持续的本地合成采样等同于正式128项合同TC通过；V1–V50和正式NOT_RUN状态不变。

## 回归与合并边界

本批Python230项全部实际执行通过（含18项新单元测试与1项显式启用的官方引擎测试），Node186项通过。13种预配置中的12个Prometheus目标合并为10个file_sd任务，另有3个Zabbix待配置项；官方promtool已实际检查整套文件发现配置。提交前保留主分支9cebcff6的10项用户前端/文档更新，无文件冲突，不重写其前端代码，也不把其上游页面验收当成本轮证据。
