# D05 SCP/AF 监控采集完善 · 开发文档

> 本文件由 Notion 页面导出（2026-10-18），内容以 Notion 原页面为准：https://app.notion.com/p/ccb1d62fa1294a36941859267c8d787a

> [!NOTE]
> 目标：在 SCP/AF 凭据就绪后，实现持续轮询采集、TimescaleDB/PG 历史存储、首页指标展示。<br>前置条件：① AF 密码已更新；② SCP SDK 权限已开通；③ SCP AK/SK 已核对。

> [!WARNING]
> **代码核查（commit cca471d · 2026-10-17）**：本页部分表名、迁移号、包路径及 JPA / `@Scheduled` 写法与仓库实际不符，开发前先读页尾「七、代码核查补充」，冲突处以该节为准。指标历史复用 Zabbix，AF 地址应为 192.169.11.1。

## 一、数据库迁移 V35

**文件**：`V35__device_metrics_history.sql`
```sql
-- 设备指标历史表
-- 若环境已安装 TimescaleDB，可将其转为 hypertable
CREATE TABLE device_metric_samples (
  id          BIGSERIAL,
  tenant_id   UUID NOT NULL,
  device_id   UUID NOT NULL,   -- 关联资产表设备
  metric_name TEXT NOT NULL,   -- 'cpu_usage','mem_usage','if_in_bps'等
  value       DOUBLE PRECISION NOT NULL,
  collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, collected_at)
);
CREATE INDEX ON device_metric_samples(device_id, metric_name, collected_at DESC);
CREATE INDEX ON device_metric_samples(tenant_id, collected_at DESC);

-- 如果有 TimescaleDB，执行：
-- SELECT create_hypertable('device_metric_samples','collected_at');
-- SELECT add_retention_policy('device_metric_samples', INTERVAL '90 days');

-- 采集任务状态表
CREATE TABLE device_collector_states (
  device_id     UUID PRIMARY KEY,
  last_ok_at    TIMESTAMPTZ,
  last_error    TEXT,
  error_count   INT NOT NULL DEFAULT 0,
  next_run_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
---

## 一补、SCP/AF 错误码处理规范

> [!WARNING]
> 根据代码审查，SCP 当前存在两个已知错误：
> - **192.168.1.179** SCP 返回 `0x020170010002`(无效 AK/SK)、`0x120220010001`(权限缺少)
> - **192.168.1.170** AF 返回 `1010`(密码过期)
> 这两个错误需要客户侧操作，Worker 不应重试而应直接告警。

**文件**：`src/main/java/com/mop/monitor/scp/ScpException.java`
```java
// SCP 特定错误码
public class ScpAuthException     extends RuntimeException {}  // AK/SK 失效
public class ScpPermissionException extends RuntimeException {}  // SDK 权限缺失
public class AfPasswordExpiredException extends RuntimeException {} // AF 密码过期

// 在 ScpClient.extractToken() 中识别错误码
private String extractToken(Map<?, ?> resp) {
    String retCode = (String) resp.get("retCode");
    if ("0x020170010002".equals(retCode))
        throw new ScpAuthException("无效 AK/SK，请客户更新凭据");
    if ("0x120220010001".equals(retCode))
        throw new ScpPermissionException("少 SDK 操作权限，请客户开通权限");
    return (String) ((Map<?, ?>) resp.get("data")).get("token_id");
}

// 在 AfClient.extractToken() 中识别错误码
private String extractAfToken(Map<?, ?> resp) {
    int code = ((Number) resp.get("status")).intValue();
    if (code == 1010)
        throw new AfPasswordExpiredException("密码已过期，请客户在 AF 控制台更新密码");
    return (String) resp.get("token");
}
```
---

## 二、SCP 采集 Worker

### 2.1 SCP 指标项定义

**文件**：`src/main/java/com/mop/monitor/scp/ScpMetricSpec.java`
```java
public enum ScpMetricSpec {
    CPU_USAGE   ("cpu_usage",    "/resource/cpu/usage"),
    MEM_USAGE   ("mem_usage",    "/resource/memory/usage"),
    DISK_USAGE  ("disk_usage",   "/resource/disk/usage"),
    NET_IN_BPS  ("net_in_bps",   "/resource/network/in"),
    NET_OUT_BPS ("net_out_bps",  "/resource/network/out"),
    VM_COUNT    ("vm_count",     "/resource/vm/count");

    public final String metricName;
    public final String apiPath;    // SCP OpenAPI 相对路径
}
```

### 2.2 ScpCollectorWorker

**文件**：`src/main/java/com/mop/monitor/scp/ScpCollectorWorker.java`
```java
@Component @Slf4j
public class ScpCollectorWorker {

    private final ScpClient scpClient;
    private final DeviceRepository deviceRepo;
    private final MetricRepository metricRepo;
    private final CollectorStateRepository stateRepo;

    // 每 5 分钟采集一次
    @Scheduled(fixedRate = 300_000)
    public void collect() {
        // 查找所有类型为 SCP 的被管设备
        List<Device> scpDevices = deviceRepo.findAllByType(DeviceType.SCP);
        for (Device device : scpDevices) {
            try {
                String token = scpClient.login(
                    device.getMgmtIp(),
                    device.getCredentialUsername(),
                    device.getCredentialPassword()
                );
                collectScpMetrics(device, token);
                markSuccess(device.getId());
            } catch (Exception e) {
                markError(device.getId(), e.getMessage());
                log.error("SCP collect failed device={}", device.getId(), e);
            }
        }
    }

    private void collectScpMetrics(Device device, String token) {
        for (ScpMetricSpec spec : ScpMetricSpec.values()) {
            try {
                double value = scpClient.getMetric(
                    device.getMgmtIp(), token, spec.apiPath);
                metricRepo.save(DeviceMetricSample.of(
                    device.getTenantId(),
                    device.getId(),
                    spec.metricName,
                    value
                ));
            } catch (Exception e) {
                log.warn("Metric {} failed for device={}: {}",
                    spec.metricName, device.getId(), e.getMessage());
            }
        }
    }

    private void markSuccess(UUID deviceId) {
        stateRepo.upsert(deviceId, Instant.now(), null, 0,
            Instant.now().plus(5, MINUTES));
    }

    private void markError(UUID deviceId, String error) {
        CollectorState s = stateRepo.findById(deviceId).orElse(new CollectorState(deviceId));
        s.setLastError(error);
        s.setErrorCount(s.getErrorCount() + 1);
        s.setNextRunAt(Instant.now().plus(
            Math.min(60, s.getErrorCount() * 5), MINUTES)); // 指数退避
        stateRepo.save(s);
    }
}
```

### 2.3 ScpClient HTTP 封装

**文件**：`src/main/java/com/mop/monitor/scp/ScpClient.java`
```java
@Component
public class ScpClient {

    private final RestTemplate http;

    /**
     * 登录并返回 Token
     */
    public String login(String host, String username, String password) {
        var body = Map.of("userName", username, "password", password);
        var resp = http.postForObject(
            "https://" + host + "/controller/v2/tokens",
            body, Map.class);
        return extractToken(resp);
    }

    /**
     * 读取单项指标数值
     */
    public double getMetric(String host, String token, String apiPath) {
        var headers = new HttpHeaders();
        headers.set("X-ACCESS-TOKEN", token);
        var entity = new HttpEntity<>(headers);
        var resp = http.exchange(
            "https://" + host + "/controller/v2" + apiPath,
            HttpMethod.GET, entity, Map.class);
        return extractValue(resp.getBody());
    }

    private String extractToken(Map<?, ?> resp) {
        if (resp == null) throw new ScpClientException("SCP 登录返回空");
        // 实际响应结构按 SCP OpenAPI v6.11.1 文档解析
        return (String) ((Map<?, ?>) resp.get("data")).get("token_id");
    }

    private double extractValue(Map<?, ?> resp) {
        if (resp == null) throw new ScpClientException("SCP 指标返回空");
        // 按实际 API 响应字段提取 double 数值
        return Double.parseDouble(resp.get("value").toString());
    }
}
```
---

## 三、AF 采集 Worker

**文件**：`src/main/java/com/mop/monitor/af/AfCollectorWorker.java`
```java
@Component @Slf4j
public class AfCollectorWorker {

    @Scheduled(fixedRate = 300_000)
    public void collect() {
        List<Device> afDevices = deviceRepo.findAllByType(DeviceType.AF);
        for (Device device : afDevices) {
            try {
                String token = afClient.login(
                    device.getMgmtIp(),
                    device.getCredentialUsername(),
                    device.getCredentialPassword() // 客户更新密码后可用
                );
                collectAfMetrics(device, token);
                markSuccess(device.getId());
            } catch (AfPasswordExpiredException e) {
                // 密码过期不再重试，直接告警
                alertService.raise("AF_PASSWORD_EXPIRED", device.getId());
                markError(device.getId(), "PASSWORD_EXPIRED");
            } catch (Exception e) {
                markError(device.getId(), e.getMessage());
            }
        }
    }

    private void collectAfMetrics(Device device, String token) {
        // 参考 Fortinet FortiGate REST API
        // GET /api/v2/monitor/system/resource/usage
        Map<?, ?> usage = afClient.getResourceUsage(device.getMgmtIp(), token);
        saveMetric(device, "cpu_usage",  extractCpu(usage));
        saveMetric(device, "mem_usage",  extractMem(usage));
        saveMetric(device, "session_count", extractSessions(usage));

        // GET /api/v2/monitor/firewall/policy
        Map<?, ?> policy = afClient.getPolicyStats(device.getMgmtIp(), token);
        saveMetric(device, "policy_hit_count", extractPolicyHits(policy));
    }
}
```
---

## 三补、MetricRepository 时序查询实现

**文件**：`src/main/java/com/mop/monitor/repository/MetricRepository.java`
```java
@Repository
public interface MetricRepository extends JpaRepository<DeviceMetricSample, Long> {

    /**
     * 时序查询——如果安装了 TimescaleDB 就用样本函数降采样。
     * step 示例：'5 minutes','1 hour'
     */
    @Query(value = """
        SELECT
          time_bucket(:step::interval, collected_at) AS bucket,
          AVG(value) AS value
        FROM device_metric_samples
        WHERE device_id  = :deviceId
          AND metric_name = :metric
          AND collected_at BETWEEN :from AND :to
        GROUP BY bucket
        ORDER BY bucket
        """, nativeQuery = true)
    List<MetricPointProjection> queryTimeSeriesTs(
        @Param("deviceId") UUID deviceId,
        @Param("metric")   String metric,
        @Param("from")     Instant from,
        @Param("to")       Instant to,
        @Param("step")     String step
    );

    /**
     * 如果无 TimescaleDB，用标准 PG 日历截断降采样。
     */
    @Query(value = """
        SELECT
          date_trunc(:truncUnit, collected_at) AS bucket,
          AVG(value) AS value
        FROM device_metric_samples
        WHERE device_id  = :deviceId
          AND metric_name = :metric
          AND collected_at BETWEEN :from AND :to
        GROUP BY bucket
        ORDER BY bucket
        """, nativeQuery = true)
    List<MetricPointProjection> queryTimeSeriesPg(
        @Param("deviceId")  UUID deviceId,
        @Param("metric")    String metric,
        @Param("from")      Instant from,
        @Param("to")        Instant to,
        @Param("truncUnit") String truncUnit   // 'minute','hour','day'
    );

    /** 最新一条指标快照 */
    @Query("""
        SELECT s FROM DeviceMetricSample s
        WHERE s.deviceId = :deviceId
          AND s.metricName = :metric
        ORDER BY s.collectedAt DESC
        LIMIT 1
        """)
    Optional<DeviceMetricSample> findLatest(
        @Param("deviceId") UUID deviceId,
        @Param("metric")   String metric
    );

    /** 所有指标最新快照（用于概览页） */
    @Query(value = """
        SELECT DISTINCT ON (device_id, metric_name)
          device_id, metric_name, value, collected_at
        FROM device_metric_samples
        WHERE tenant_id = :tenantId
        ORDER BY device_id, metric_name, collected_at DESC
        """, nativeQuery = true)
    List<LatestMetricProjection> findLatestAllByTenantId(
        @Param("tenantId") UUID tenantId
    );
}

// Projection 接口
public interface MetricPointProjection {
    Instant getBucket();
    Double  getValue();
}

public interface LatestMetricProjection {
    UUID   getDeviceId();
    String getMetricName();
    Double getValue();
    Instant getCollectedAt();
}
```
**步进展转接口：`MetricService.queryTimeSeries()`**
```java
@Service
public class MetricService {

    @Value("${mop.timescale.enabled:false}")
    private boolean timescaleEnabled;

    public List<MetricPoint> queryTimeSeries(
            UUID deviceId, String metric,
            Instant from, Instant to, Duration step) {

        if (timescaleEnabled) {
            return metricRepo
                .queryTimeSeriesTs(deviceId, metric, from, to, toTsStep(step))
                .stream().map(p -> new MetricPoint(p.getBucket(), p.getValue()))
                .toList();
        } else {
            return metricRepo
                .queryTimeSeriesPg(deviceId, metric, from, to, toPgTrunc(step))
                .stream().map(p -> new MetricPoint(p.getBucket(), p.getValue()))
                .toList();
        }
    }

    private String toTsStep(Duration d) {
        if (d.toMinutes() < 60) return d.toMinutes() + " minutes";
        if (d.toHours()   < 24) return d.toHours()   + " hours";
        return d.toDays() + " days";
    }

    private String toPgTrunc(Duration d) {
        if (d.toMinutes() < 60) return "minute";
        if (d.toHours()   < 24) return "hour";
        return "day";
    }
}
```
---

## 四、指标查询 API

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/v1/devices/{id}/metrics?metric=cpu_usage&from=&to=&step=5m` | 指定设备指标历史 |
| `GET` | `/api/v1/devices/{id}/metrics/latest` | 当前最新指标快照 |
| `GET` | `/api/v1/devices/metrics/overview` | 所有设备最新指标表 |

```java
@GetMapping("/devices/{id}/metrics")
public List<MetricPoint> getMetrics(
        @PathVariable UUID id,
        @RequestParam String metric,
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(defaultValue = "5m") String step,
        @AuthenticationPrincipal MopUser actor) {

    // 验证设备属于当前租户
    deviceService.verifyTenantAccess(id, actor.getTenantId());
    return metricRepo.queryTimeSeries(id, metric, from, to, parseDuration(step));
}
```
---

## 五、前端实现

### 5.1 设备详情页指标图表

**文件**：`src/frontend/pages/devices/[id]/metrics.vue`
```javascript
<script setup lang="ts">
const { data: latest } = await deviceApi.getLatestMetrics(deviceId)
const chartData = ref([])

async function loadChart(metric: string, range: '1h' | '24h' | '7d') {
  const { from, to } = rangeToTimestamps(range)
  chartData.value = await deviceApi.getMetrics(deviceId, { metric, from, to })
}
</script>

<template>
  <!-- 最新指标卡片 -->
  <el-row :gutter="16">
    <el-col :span="6" v-for="m in ['cpu_usage','mem_usage','net_in_bps','net_out_bps']">
      <MetricCard :name="m" :value="latest[m]" :unit="metricUnit(m)" />
    </el-col>
  </el-row>

  <!-- 时序图表 -->
  <el-card class="mt-4">
    <template #header>
      <span>指标趋势</span>
      <el-radio-group v-model="range" @change="loadChart(selectedMetric, $event)">
        <el-radio-button value="1h">1小时</el-radio-button>
        <el-radio-button value="24h">24小时</el-radio-button>
        <el-radio-button value="7d">7天</el-radio-button>
      </el-radio-group>
    </template>
    <MetricLineChart :data="chartData" />
  </el-card>
</template>
```

### 5.2 MetricLineChart 组件（基于 ECharts）

**文件**：`src/frontend/components/devices/MetricLineChart.vue`
```javascript
<script setup lang="ts">
import * as echarts from 'echarts'

const props = defineProps<{ data: { time: string; value: number }[] }>()
const chart  = ref<HTMLDivElement>()

watch(() => props.data, (d) => {
  const instance = echarts.getInstanceByDom(chart.value!) ??
    echarts.init(chart.value!)
  instance.setOption({
    xAxis: { type: 'time' },
    yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
    series: [{
      type: 'line',
      data: d.map(p => [p.time, p.value]),
      smooth: true,
      areaStyle: {}
    }],
    tooltip: { trigger: 'axis' }
  })
}, { immediate: true })
</script>

<template>
  <div ref="chart" style="height:300px" />
</template>
```
---

## 六、验收检查项

- [ ] SCP Worker 启动后每 5 分钟写入一条 `device_metric_samples` 记录
- [ ] AF Worker 在密码过期时不重试，直接转告警
- [ ] 指标查询 API 支持 1h/24h/7d 范围过滤
- [ ] 不同租户的指标数据严格隔离
- [ ] 首页设备状态卡片展示最新 CPU/内存指标
- [ ] 基于指标阈值解除所有正常设备的 Zabbix 告警（指标恢复后自动关闭）

## 七、代码核查补充（commit cca471d · 2026-10-17）

> [!WARNING]
> 本节根据仓库实际代码、设备凭据实测记录与合同 M4-1～M4-4、M4-18、M4-19 核对，与前文冲突时以本节为准。重点：项目把 Zabbix 7.0 作为指标历史存储，不新建 TimescaleDB / PG 指标表；AF 采集已在运行；SCP 仍被 403 阻塞。

### 已有实现

| 能力 | 代码 | 现状 |
|---|---|---|
| Zabbix 写入 / 读取 | `server/adapters/.../monitoring/ZabbixHistoryWriter`（trapper 写入）、`ZabbixReadClient` | 测试环境 Zabbix 7.0.31（server / web / agent 已在 compose） |
| AF 防火墙采集 | `adapters/af/AfClient`、`AfCollectorMain`（compose 服务 `af-collector`，约 60 秒一轮写入 Zabbix）、`api/FirewallResourceService` | CPU / 内存已有真实首值；密码过期阻塞已于 2026-10-14 解除 |
| SCP 超融合 | `adapters/scp/ScpClient`（RSA 加密登录换 Token） | 无采集 Worker；版本 / 资源池 GET 返回 403（`0x120220010001`） |
| 基础网络监控 | `api/NetworkMonitoringService`  ＋ `NetworkMonitoringController`（`/api/v1/network-monitoring`：列表、`/{assetId}/history`、`/{assetId}/resources`、`/{assetId}/resources/history`、`/refresh-policy`）；`NetworkMonitoringPage.vue` | 6 项指标：ICMP 可达、丢包、延迟、TCP 22 / 80 / 443；绑定关系由环境 JSON 静态配置 |
| 数值序列 / 端口显示 | `domain/monitoring/MetricSeriesProjection`、`PortUtilizationDisplay` | 纯核心 |
| 阈值诊断 / 厂商映射 | `ThresholdConditionAnalysis`、`VendorMappingCandidate`（YAML 候选） | 未生效 |

### 前文需要更正

| 前文写法 | 应改为 |
|---|---|
| V35 `device_metric_samples` / `device_collector_states`  ＋ TimescaleDB | 指标历史复用 Zabbix（项目「成熟组件复用」原则），PG 只存采集任务状态、绑定和新鲜度；如确需 PG 历史，先走组件评审 |
| AF 地址 192.168.1.170 | AF 防火墙为 192.169.11.1；192.168.1.170 是超融合设备地址（仅 IP 基础监控）；SCP 管理平台 192.168.1.179 |
| `ScpCollectorWorker` / `AfCollectorWorker` 用 `@Scheduled(fixedRate = 300_000)` | 无 `@Scheduled`；采集是独立进程（参照 `af-collector`），周期取 `monitor.sample_interval_sec`（Q20，当前 null；AF 代码里的 60 秒需改为读配置） |
| `com.mop.monitor.*`、`MetricRepository extends JpaRepository` | 包名 `cn.mop.adapters.*`；无 JPA |
| `/api/v1/devices/{id}/metrics*` | 已有 `/api/v1/network-monitoring/{assetId}/resources/history`，在此扩展；资产路径统一 `assets` |
| `src/frontend/components/devices/MetricLineChart.vue`  ＋ ECharts | 前端在 `apps/web/src/pages/NetworkMonitoringPage.vue` 与 `utils/networkMonitoring.ts`、`liveMonitoringWorkspace.ts`；当前不含 ECharts，引入前需评审 |
| 「密码过期时不重试，直接转告警」 | 采集失败应体现为数据新鲜度「未知」（`asset.unknown_detect_max_sec=60`），由 D15 无数据规则产生事件，而不是采集器直接发告警 |

### 合同要求但前文未覆盖

- **M4-1**：断采后首页「正常 / 未知」计数联动；主台账同步失败、配置漂移展示。
- **M4-2**：按设备类型的资源曲线（CPU / 内存 / 磁盘 / 会话数等）与热更新阈值。
- **M4-3**：端口 Up / Down、流量、利用率曲线与阈值配色。
- **M4-4**：多探测点一致性；单点失败不能直接判服务不可用。
- **M4-18 必须补齐项**：外网线路独立探针（ICMP / HTTP，30 秒）、内网 DNS / TCP 拨测（60 秒）；防火墙并发会话、光功率、VPN 隧道、DHCP 池、证书到期（到期前 30 天 P4）、接口错包率、PPPoE；阈值基线写入配置表；告警六字段（rule_code、对象、当前值 vs 阈值、持续时间、影响范围、建议动作）。
- **M4-19 多品牌 OID 映射**：YAML 映射目录热加载，新增文件免发版采集 CPU / 内存；现有 `VendorMappingCandidate` 需从候选转为生效。

### 修订后的实施步骤

1. 把 `NetworkMonitoringService` 的静态 JSON 绑定改为数据库绑定（与 D18 五步接入、凭据托管对接）。
2. SCP：客户授予「SDK 服务：获取 SCP 版本信息」等权限后，新增 `scp-collector` 进程，按 AF 采集器模式写 Zabbix。
3. 采集周期、超时、重试全部改读配置；每个采集器写健康文件 / 新鲜度，供首页「未知」计数。
4. 补 M4-18 专项探针与指标、M4-19 OID 映射生效；阈值通过 D15 规则 / 受控宏下发。
5. 资源曲线、端口流量页面在 `NetworkMonitoringPage.vue` 扩展；首页 Top5 读取同一接口（D18）。

### 补充验收（合同原文摘录）

- [ ] M4-18：随机 10 条告警六类字段齐全无空值；影响范围与 CMDB 拓扑一致；外网探针、DNS、防火墙会话、光功率、VPN、DHCP、证书七类监控有数据并能触发告警
- [ ] M4-19：新增品牌映射文件后免发版采集到 CPU / 内存
- [ ] 断开一台设备采集，1 分钟内首页「未知」+1、「正常」−1（M1-1）
阻塞：B01（已解除，持续采集仍需验证）、B02（SCP 权限 403）、B03（AF / SCP 严格 TLS 校验 verify_code=20，需可信证书链）、B04（华为 / 信锐只读凭据与 MIB 资料）；待确认 Q06（阈值矩阵 / 心跳）、Q20（采样间隔）。
