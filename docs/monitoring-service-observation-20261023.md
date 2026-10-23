# 抓取与服务信号联合读取，2026-10-23

API 0.82.0新增 GET `/api/v1/monitoring-onboarding/registered-targets/{id}/service-observation`。旧 `/observation` 的响应和一次up查询保持不变；本批只改后端，不改前端、不部署。

## 能回答什么

对于HTTP/TCP/ICMP、PostgreSQL、MySQL和Redis预设，一次Prometheus查询同时取得 `up`、预设成功信号及两者实际样本时间。因此可以返回“抓取成功，但Redis信号为0”，不再把导出器可抓取当成目标服务成功。

data沿用当前租户、操作者、权限版本和资产范围检查，source为 `PROMETHEUS_SERVICE_OBSERVATION`。report保留原采集状态字段，新增service_signal：

- metric：固定来自预设的probe_success、pg_up、mysql_up或redis_up；不支持时null。
- status：SIGNAL_SUCCESS、SIGNAL_FAILURE、STALE、NO_DATA、INVALID_RESPONSE、OUT_OF_SYNC、BLOCKED_SCRAPE、NOT_CHECKED或NOT_SUPPORTED。
- value：仅SIGNAL_SUCCESS为1、SIGNAL_FAILURE为0，其余必须null。
- sample_at、sample_age_seconds：仅有有效解析样本时返回，不制造时间或样本。
- health_verified始终false；单个成功信号不是设备整体健康、持续监控验收或业务SLA保证。

没有声明成功指标的Linux/Windows节点、容器和SNMP等预设不会猜测指标。Prometheus预设仍可读取抓取状态，服务信号为NOT_SUPPORTED；非Prometheus引擎维持原不支持状态。

## 防止错误拼接

一次固定评价时间的联合查询区分scrape_state/scrape_time/service_state/service_time，至多四条正常结果，limit=5保留冲突哨兵，不能截取前四条伪装唯一。

每个指标独立复用既有范围、实例、唯一配对、数值、评价时间、实际时间和时效验证。两个指标还必须属于同一job和同一次采样时刻。服务指标可有额外维度标签，但必须只有一个明确序列；不能挑多实例的第一条。

- 抓取失败、过期或无数据：服务值被BLOCKED_SCRAPE阻断。
- 服务信号过期：STALE且value=null。
- 服务信号尚在时效内但不属于当前抓取时刻：OUT_OF_SYNC且value=null。
- 不同job、错误指标名或非法服务值：不输出成功值。
- 空结果不补零，来源异常不回退缓存成功，也不推断所有服务均正常。

## 权限、配置与资源边界

沿用现有独立目录/来源开关，默认禁用，精确来源allowlist和明确时效窗口不变。需要assets:read与configurations:read，禁止客户端URL、指标名、PromQL、租户、查询时间和请求体覆盖。当前成员、资产及管理地址在查询前后核对，读取期间撤权不释放结果。

旧与新读取共享同一个并发槽。联合读取仍只有一次网络请求、一个总超时和2MiB正文上限，不为每个指标额外发请求。无代理、无重定向、TLS验证保留、严格UTF-8/JSON；指标名还限制为合法Prometheus标识符，即使未来预设配置写错也不能注入表达式。

本接口不生成凭据、不授予新持久访问、不扫描设备、不发布采集配置，不改变已有业务权限规则。缺少来源认证的情况仍为待配置依赖，不能冒充联通。

## 验证

最终统计见 [validation.json](evidence/monitoring-service-observation-20261023/validation.json)。包括纯判定、异常关联、控制器撤权、实际HTTP安全链及官方本地Prometheus回归。

新真实引擎测试仅在明确配置本地Prometheus二进制时运行：隔离790租户标签、loopback导出器、临时TSDB。覆盖Redis信号1→0但抓取仍成功、HTTP503导致抓取失败与服务阻断、恢复、无数据、重复job和引擎停止，结束后清理进程/目录。这不是对业务Redis实例的验证。

初次联合查询标签重写存在Java字符串转义编译错误，修正后重新验证；未放宽范围或指标检查。演示准备目录只同步API0.82源码锁与离线黄金样例，账号/授权/部署状态不变。

正式128项合同TC仍NOT_RUN，V1–V50未改，真实设备、真实来源认证、前端交互和业务部署未执行。
