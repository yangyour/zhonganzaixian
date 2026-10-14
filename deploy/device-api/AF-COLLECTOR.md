# AF 项目采集 Worker 与资源读取

当前限定深信服 AF `192.169.11.1`、namespace `public`，固件首次核验值 `AF8.0.106.4646`。登录凭据从本机 Git 忽略的 `deploy/private/firewall-credentials.json` 读取；该文件与服务器 `private/af-collector.env` 均不得复制到公共文档或日志。

先只读盘点 Zabbix Web 的实际容器 IP/网关。使用三个精确地址（含所需回环地址）运行默认计划，核对后才显式应用：

```text
python tools/configure-firewall-resources.py --allowed-hosts <实际IPv4地址列表> --output <新计划文件>
python tools/configure-firewall-resources.py --allowed-hosts <实际IPv4地址列表> --apply --output <新应用报告>
```

工具仅创建专用组/主机/三个 trapper 项及两套受限 API 身份，已有项漂移则停止，不覆盖共享配置或添加告警。身份与绑定仅写入 `deploy/private/af-resource-access.json`。原四台基础监控不需要扩权。

Worker 复用已构建 API JAR 中独立入口 `cn.mop.adapters.af.AfCollectorMain`。将已验 SHA256 的 JAR 放到独立不可变发布目录，并设置 `.env` 的 `AF_RELEASE_DIR`。服务器私有 env 必需项：

|项|用途|
|---|---|
|MOP_AF_COLLECTOR_ENABLED|显式 true 才运行|
|MOP_AF_USERNAME / MOP_AF_PASSWORD|专用设备账号及新密码|
|MOP_AF_EXPECTED_VERSION|已实测的完整版本，变化后暂停以便核验|
|MOP_AF_TLS_MODE / MOP_AF_CA_FILE|SYSTEM、CA_FILE 或已授权 TEST_INSECURE；CA_FILE 时提供容器内绝对证书路径并显式只读挂载|
|MOP_AF_ITEMS|cpu/memory/state 到本项目三个 item ID 的严格 JSON 映射|
|MOP_AF_ZABBIX_ENDPOINT|固定回环 HTTP API 地址|
|MOP_AF_ZABBIX_TOKEN|专用 history.push 身份，不使用管理员或读取 token|

在项目部署目录显式启动：

```text
docker compose --profile af-monitoring up -d --pull never --no-deps af-collector
```

周期固定为 60 秒，不重叠执行；项目 trapper 历史配置 7 天。只读取防火墙并管理本次登录/注销会话。失败不推送零值。认证失败、权限/密码/固件门禁以及认证结果不明确时暂停设备请求，持续推送失败状态；修复配置后用同一命令重建或仅重启该项目 Worker。不要通过修改设备策略、全局权限或停止共享服务排查。

MOP API 仅配置 `MOP_AF_RESOURCE_ENABLED=true`、`MOP_AF_RESOURCE_BINDING`、`MOP_AF_RESOURCE_ENDPOINT` 和专用 `MOP_AF_RESOURCE_TOKEN`；不持有防火墙密码或发布 token。binding 包含已授权 tenant_id/asset_id/ip、资源 host_id、三项 items、精确 allowed_hosts 和 tls_mode；源对象仍由当前 PostgreSQL 资产归属/IP与成员权限约束。

三项时刻必须一致且状态 OK 才显示当前值，180 秒后置为过期；失败时两值均 null。启用后 API ready 反映该依赖实际状态。Worker 的 `--health` 只读其本机健康文件，不发设备请求。

更新密码后应先用 `check-device-api.py` 验证真实读接口，再受控更新服务器私有 env 并重建该项目 Worker。部署回退只调整本项目发布指针及原 env，不迁移/删除数据库，不修改源设备。不要删除已经采集的历史来让检查变绿。

完整边界和实测证据见[实现记录](../../docs/firewall-resource-monitoring.md)。
