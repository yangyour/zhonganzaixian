# 登记目标采集状态读取，2026-10-22

## 范围与接口

API 0.81.0 增加 GET `/api/v1/monitoring-onboarding/registered-targets/{id}/observation`。仅在 `remote_test` profile 装配，默认关闭。当前会话必须同时具有 `assets:read`、`configurations:read`，无须写权限；请求不能带查询参数、地址、PromQL、租户覆盖或请求体。

后端根据当前租户查找已登记目标，核对当前授权资产及其管理地址，向显式配置的该租户 Prometheus 来源发出一次固定、有界查询。查询后再次检查资产和成员权限。资产变化返回409 `ASSET_CHANGED`；授权丢失不释放查询结果。不直连目标设备，不写业务库、不新增账户、不自动启动采集器，也不改变前端。

返回普通 `data/trace_id` 信封。data 包含当前 `tenant_id/actor_id/permission_version`、`asset_scope_checked:true`、`source:PROMETHEUS_SCRAPE_OBSERVATION` 和 report。report 包含登记目标ID及当前登记配置摘要、状态/安全原因码、查询评价时间、收到响应的检查时间、样本时间与年龄、可空 `scrape_up`、显式时效窗口、是否调用来源。来源地址、原始标签、上游错误正文和凭据不返回。响应 `Cache-Control:no-store`。

## 状态不能互相代替

| observation_status | 含义 | scrape_up |
|---|---|---|
| CONFIG_PENDING | 该租户未配置/未开启读取来源 | null |
| TARGET_DISABLED | 当前登记目标尚未启用 | null |
| UNSUPPORTED_ENGINE | 该目标不是Prometheus引擎，本接口不替代Zabbix读取 | null |
| NO_DATA | 当前配置回看窗口内没有可用样本 | null |
| STALE | 实际样本年龄超过明确配置的有效年龄 | null |
| SCRAPE_SUCCEEDED | 当前有效的Prometheus `up=1` | 1 |
| SCRAPE_FAILED | 当前有效的Prometheus `up=0` | 0 |
| SOURCE_UNAVAILABLE | 超时、HTTP/TLS/连接/大小等来源读取失败 | null |
| SOURCE_INVALID_RESPONSE | 来源格式、时间、范围、结果歧义或注解不满足要求 | null |

HTTP200只表示返回了诊断结果，不能渲染成“设备在线/健康”。`up=1`只表示抓取成功：Blackbox的 `probe_success=0`、数据库导出器的 `pg_up=0` 等目标业务状态仍需独立读取，不能从本接口推断。`continuous_collection_verified`、`active_configuration_verified`、`device_identity_verified`、`health_verified` 始终false。当前配置摘要是登记版本，不证明采集器已应用同一完整配置或采样周期。

未配置、禁用和不支持状态没有来源网络调用；样本与时间字段为空。成功抓取和失败抓取都有当前样本，过期时清空 `scrape_up`，不以旧绿色、缓存成功或补零替代。登记目录本身未配置时为503；身份、参数、资源、并发与变更错误分别保留401/403、400、404、429、409错误信封。

## 配置与兼容

使用 [环境开关示例](../deploy/examples/monitoring/prometheus-read.env.sh.example) 和 [来源占位模板](../deploy/examples/monitoring/prometheus-sources.example.json)。模板中的null是待补依赖，不能直接作为启用配置。

1. `MOP_MONITORING_PRESET_REGISTRY_ENABLED` 独立控制登记目录。未设置时继承原 `MOP_MONITORING_PRESET_TEST_ENABLED`，兼容原部署；二者默认false。显式关闭登记目录却开启探测会在启动时拒绝。
2. 登记目录可以开启而探测保持false。此时目标列表 `test_authorized:false`，即便残留旧探测地址白名单，也不能调用探测。读取状态不会因开启目录而开放设备探测。
3. `MOP_MONITORING_PROMETHEUS_READ_ENABLED` 默认false。来源配置 `MOP_MONITORING_PROMETHEUS_SOURCES` 默认为 `{"version":1,"sources":[]}`，`MOP_MONITORING_PROMETHEUS_ALLOWED_ENDPOINTS` 默认为 `[]`。
4. 每个来源明确提供 `tenant_id,endpoint,max_age_seconds,lookback_seconds,timeout_seconds`。每租户至多一个来源、全局最多64个。年龄1–86400秒，回看窗口不得小于有效年龄且不超过86400秒；超时1–10秒。有效年龄与回看窗口必须由运营者填写，不设虚构业务默认值。
5. endpoint必须是固定 `http(s)://IPv4:port/api/v1/query`，且在独立精确来源白名单中。不接受DNS名称、用户信息、查询参数、片段、非规范端口/路径、元数据服务地址或内联凭据。与目标/下游探测白名单分开，不自动互相授权。
6. 如来源需要认证，当前内联凭据/令牌接入仍未实现，必须留作待补依赖；不能偷偷回退到其他来源或匿名成功。真实来源访问控制、证书、网络隔离与账号授权需另行配置验证。Prometheus标签不是多租户ACL，不能因为MOP有范围检查就把裸Prometheus暴露给所有用户。

登记目标的 `enabled` 必须为true且采样参数有效才查询来源。这里不会自动修改登记文件或导出配置、发布配置、调用reload或批量扫描设备。

## 时间、歧义与传输约束

参考 [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/) 与 [timestamp函数](https://prometheus.io/docs/prometheus/latest/querying/functions/#timestamp)：即时查询的评价时间不是原始样本时间。

- 同一个固定评价时间查询 `up` 与 `timestamp(up)`，通过内部标签区分两种值；固定匹配租户、资产、目标和预设。查询串由服务器构造，用户不能传PromQL。
- 至多取3条结果，从而不能把多来源冲突截断成恰好2条并误判唯一。必须恰好一个状态和一个样本时间，且原始来源标签一致；实例必须匹配登记目标实际地址。重复、混合job、缺失配对或任何非空warnings/infos都不作为成功。
- 必须验证评价时间、实际样本时间、回看窗口、有限数值、当前标签范围和0/1值。时效按响应收到时计算，网络耗时计入年龄；本地时钟回拨、未来时间等不显示成功。
- 共享HTTP传输：禁止重定向和环境代理，TLS验证保留，整个调用有超时，正文最多2MiB，UTF-8与JSON严格校验、拒绝重复键和尾随文档。每进程同时最多一个来源查询，不自动重试或后台轮询。
- 提取共享传输时原单次探测行为与失败原因保持，原探测回归一并运行。

## 验证与交付边界

最终统计见 [验证记录](evidence/monitoring-scrape-observation-20261022/validation.json)。新增单元/HTTP链/来源网络/真实本地Prometheus测试，不调用业务数据库。

真实引擎测试通过显式 `MOP_PROMETHEUS_BIN` 使用已核验官方Prometheus 3.15.1：临时TSDB、loopback导出器和隔离790租户，覆盖成功→503失败→恢复、无数据、第二个job重复同目标导致歧义、引擎停止导致来源不可用，并清理进程/目录。没有配置本地引擎时该项明确跳过，不自动下载软件或访问业务来源。

首次编译暴露了异步HTTP调用中不可能直接抛出的checked异常分支，已修正；首次Node回归有3处旧API版本断言仍停留0.80，已更新到0.81后重跑。未削弱范围、超时、凭据或TLS保护。

本批没有前端改动、SQL迁移、真实设备验收、来源认证上线或业务部署。主干另有前端发布记录，不能作为本批监控代码已部署的证据。V1–V50冻结，正式128个合同TC仍NOT_RUN。
