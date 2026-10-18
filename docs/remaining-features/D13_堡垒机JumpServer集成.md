# D13 堡垒机JumpServer集成 · 开发文档

> 本文件由 Notion 页面导出（2026-10-18），内容以 Notion 原页面为准：https://app.notion.com/p/e2791cb7dd5f44379d29fd6392a4fc75

> [!WARNING]
> 目标：完成 JumpServer 主机注册同步、同步 Guacamole 实现 Web SSH/RDP、双审批工作流、审计录屏存储。<br>前置条件：① JumpServer 已安装并可达；② Guacamole 已部署；③ 审计录屏存储到 MinIO 已确认。

> [!WARNING]
> **代码核查（commit cca471d · 2026-10-17）**：本页部分表名、迁移号、包路径及 JPA / `@Scheduled` 写法与仓库实际不符，开发前先读页尾「七、代码核查补充」，冲突处以该节为准。M7 须经 Q01 / Q03 书面确认后方可排期，本页方案仅为候选。

## 一、数据库迁移 V40

**文件**：`V40__jumpserver_integration.sql`
```sql
-- 1. 堆序机会话表
CREATE TABLE bastion_sessions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  device_id       UUID NOT NULL,
  requester_id    UUID NOT NULL REFERENCES accounts(id),
  approver_id     UUID REFERENCES accounts(id),
  second_approver UUID REFERENCES accounts(id),
  protocol        TEXT NOT NULL CHECK (protocol IN ('SSH','RDP','SFTP')),
  status          TEXT NOT NULL DEFAULT 'PENDING_APPROVAL'
    CHECK (status IN ('PENDING_APPROVAL','APPROVED','CONNECTED',
                      'COMPLETED','REJECTED','EXPIRED')),
  access_reason   TEXT NOT NULL,
  js_session_id   TEXT,          -- JumpServer 返回的 session ID
  guac_token      TEXT,          -- Guacamole 临时认证 token
  recording_url   TEXT,          -- MinIO 录屏文件 URL
  requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at     TIMESTAMPTZ,
  connected_at    TIMESTAMPTZ,
  disconnected_at TIMESTAMPTZ,
  expires_at      TIMESTAMPTZ    -- 审批过期时间
);
CREATE INDEX ON bastion_sessions(tenant_id, device_id);
CREATE INDEX ON bastion_sessions(requester_id, status);
```
---

## 一补、DTO 定义

**文件**：`src/main/java/com/mop/bastion/dto/BastionDto.java`
```java
/** 申请堡垒机会话请求 */
public record SessionRequest(
    @NotNull UUID deviceId,
    @NotNull BastionProtocol protocol,    // SSH / RDP / SFTP
    @NotBlank @Size(max = 500) String accessReason
) {}

/** 拒绝会话请求 */
public record RejectSessionRequest(
    @NotBlank @Size(max = 500) String reason
) {}

/** 会话连接参数响应 */
public record ConnectParamsResponse(
    String  guacamoleUrl,
    String  guacToken,
    String  deviceName,
    String  protocol,
    Instant expiresAt
) {}
```
---

## 一再补、会话过期清理 Worker

```java
@Component @Slf4j
public class BastionSessionExpireWorker {

    /** 每分钟清理已超期但尚未使用的 APPROVED 会话 */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireApproved() {
        int n = sessionRepo.expireByStatus(
            Instant.now(),
            SessionStatus.APPROVED.name(),
            SessionStatus.EXPIRED.name()
        );
        if (n > 0) log.info("Expired {} approved bastion sessions", n);
    }

    /** 每 5 分钟把已连接超过 4 小时的陈旧会话标记 COMPLETED */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void cleanStaleConnected() {
        Instant cutoff = Instant.now().minus(4, ChronoUnit.HOURS);
        sessionRepo.findByStatusAndConnectedAtBefore(SessionStatus.CONNECTED, cutoff)
            .forEach(s -> {
                s.setStatus(SessionStatus.COMPLETED);
                s.setDisconnectedAt(Instant.now());
                sessionRepo.save(s);
            });
    }
}
```
**Repository 新增**：
```java
// BastionSessionRepository.java
@Modifying
@Query(value = """
    UPDATE bastion_sessions
       SET status = :toStatus
     WHERE status     = :fromStatus
       AND expires_at < :now
    """, nativeQuery = true)
int expireByStatus(
    @Param("now")        Instant now,
    @Param("fromStatus") String  fromStatus,
    @Param("toStatus")   String  toStatus
);

List<BastionSession> findByStatusAndConnectedAtBefore(
    SessionStatus status, Instant before
);
```
---

## 二、JumpServer 客户端封装

**文件**：`src/main/java/com/mop/bastion/JumpServerClient.java`
```java
@Component @Slf4j
public class JumpServerClient {

    private final RestTemplate http;

    @Value("${mop.jumpserver.baseUrl}")
    private String baseUrl;

    @Value("${mop.jumpserver.serviceAccountToken}")
    private String serviceToken;

    /**
     * 在 JumpServer 注册一台被管主机
     */
    public String registerAsset(String name, String mgmtIp, String platform) {
        var body = Map.of(
            "name",     name,
            "address",  mgmtIp,
            "platform", platform,   // 'Linux' 或 'Windows'
            "is_active", true
        );
        var resp = post("/api/v1/assets/assets/", body);
        return (String) resp.get("id");   // JumpServer 资产 ID
    }

    /**
     * 添加账号凭据（用户名/密码）
     */
    public void pushCredential(String assetId, String username, String password) {
        var body = Map.of(
            "asset",    assetId,
            "username", username,
            "password", password,
            "name",     username + "@" + assetId
        );
        post("/api/v1/assets/system-users/", body);
    }

    /**
     * 创建一次性登录 token，供 Guacamole 使用
     */
    public String createOneTimeToken(String assetId, String userId) {
        var body = Map.of(
            "user",    userId,
            "assets",  List.of(assetId),
            "actions", List.of("connect")
        );
        var resp = post("/api/v1/authentication/connection-token/", body);
        return (String) resp.get("token");
    }

    private Map<String, Object> post(String path, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(serviceToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(body, headers);
        return http.postForObject(baseUrl + path, entity, Map.class);
    }
}
```
---

## 三、审批流程 API

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `POST` | `/api/v1/bastion/sessions` | msp | 申请堡垒机会话 |
| `POST` | `/api/v1/bastion/sessions/{id}/approve` | padmin | 第一步审批 |
| `POST` | `/api/v1/bastion/sessions/{id}/second-approve` | msp_lead | 第二步审批 |
| `POST` | `/api/v1/bastion/sessions/{id}/reject` | padmin | 拒绝堡垒机请求 |
| `GET` | `/api/v1/bastion/sessions/{id}/connect` | msp | 获取 Guacamole 连接参数 |
| `GET` | `/api/v1/bastion/sessions` | padmin | 会话列表审计 |

### 双审批逻辑实现

```java
// 第一步审批（客户 PADMIN）
@Transactional
public void firstApprove(UUID sessionId, UUID approverId, UUID tenantId) {
    BastionSession s = repo.findByIdAndTenantId(sessionId, tenantId).orElseThrow();
    if (s.getStatus() != SessionStatus.PENDING_APPROVAL)
        throw new IllegalStateException("无法审批");

    s.setApproverId(approverId);
    s.setApprovedAt(Instant.now());

    // 判断是否需要第二步审批（生产设备必须双审）
    boolean isProductionDevice = deviceService.isProduction(s.getDeviceId());
    if (isProductionDevice) {
        s.setStatus(SessionStatus.PENDING_SECOND_APPROVAL);
    } else {
        s.setStatus(SessionStatus.APPROVED);
        issueGuacToken(s);   // 直接发放 token
    }
    repo.save(s);
    outboxService.publish("bastion.session.approved", s.getId(), tenantId);
}

// 第二步审批
@Transactional
public void secondApprove(UUID sessionId, UUID approverId, UUID tenantId) {
    BastionSession s = repo.findByIdAndTenantId(sessionId, tenantId).orElseThrow();
    if (s.getStatus() != SessionStatus.PENDING_SECOND_APPROVAL)
        throw new IllegalStateException("当前不在第二步审批状态");

    s.setSecondApprover(approverId);
    s.setStatus(SessionStatus.APPROVED);
    issueGuacToken(s);
    repo.save(s);
    outboxService.publish("bastion.session.approved", s.getId(), tenantId);
}

// 生成 Guacamole token
private void issueGuacToken(BastionSession s) {
    String jsToken = jumpServerClient.createOneTimeToken(
        deviceService.getJsAssetId(s.getDeviceId()),
        s.getRequesterId().toString()
    );
    s.setGuacToken(jsToken);
    s.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
}
```
---

## 四、录屏存储

**文件**：`src/main/java/com/mop/bastion/RecordingArchiveWorker.java`
```java
@Component @Slf4j
public class RecordingArchiveWorker {

    // 每 30 秒检查已断开的会话
    @Scheduled(fixedDelay = 30_000)
    public void archive() {
        List<BastionSession> completed = repo.findByStatus(SessionStatus.COMPLETED);
        for (BastionSession s : completed) {
            if (s.getRecordingUrl() != null) continue; // 已如存储过

            try {
                // 从 JumpServer 下载录屏
                byte[] recording = jumpServerClient.downloadRecording(s.getJsSessionId());
                if (recording == null || recording.length == 0) continue;

                // 上传到 MinIO
                String key = String.format("recordings/%s/%s.cast",
                    s.getTenantId(), s.getId());
                storageService.upload("mop-audit", key, recording);
                String url = "mop-audit/" + key;

                s.setRecordingUrl(url);
                repo.save(s);
                log.info("录屏已归档 session={} url={}", s.getId(), url);
            } catch (Exception e) {
                log.error("录屏归档失败 session={}", s.getId(), e);
            }
        }
    }
}
```
---

## 五、前端页面

### 5.1 申请堡垒机接入

**文件**：`src/frontend/pages/bastion/request.vue`
```javascript
<template>
  <el-form :model="form" label-width="100px">
    <el-form-item label="设备">
      <el-select v-model="form.deviceId" placeholder="选择设备">
        <el-option v-for="d in devices" :key="d.id"
          :label="d.name" :value="d.id" />
      </el-select>
    </el-form-item>
    <el-form-item label="协议">
      <el-radio-group v-model="form.protocol">
        <el-radio value="SSH">SSH</el-radio>
        <el-radio value="RDP">RDP</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item label="访问原因" required>
      <el-input v-model="form.accessReason" type="textarea" :rows="3" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="submit">申请连接</el-button>
    </el-form-item>
  </el-form>
</template>
```

### 5.2 前端 API 层

**文件**：`src/frontend/api/bastionApi.ts`
```typescript
import { request } from '@/utils/request'

export type Protocol = 'SSH' | 'RDP' | 'SFTP'
export type SessionStatus =
  | 'PENDING_APPROVAL' | 'PENDING_SECOND_APPROVAL'
  | 'APPROVED' | 'CONNECTED' | 'COMPLETED'
  | 'REJECTED' | 'EXPIRED'

export interface SessionSummary {
  id:            string
  deviceName:    string
  protocol:      Protocol
  status:        SessionStatus
  requesterName: string
  approverName:  string | null
  expiresAt:     string | null
  connectedAt:   string | null
  recordingUrl:  string | null
}

export interface ConnectParams {
  guacamoleUrl: string
  guacToken:    string
  deviceName:   string
  protocol:     Protocol
  expiresAt:    string
}

export const bastionApi = {

  /** 申请堡垒机会话 */
  request(deviceId: string, protocol: Protocol, accessReason: string) {
    return request.post<{ id: string }>('/api/v1/bastion/sessions',
      { deviceId, protocol, accessReason })
  },

  /** 第一步审批 */
  approve(sessionId: string) {
    return request.post<void>(`/api/v1/bastion/sessions/${sessionId}/approve`)
  },

  /** 第二步审批（生产设备） */
  secondApprove(sessionId: string) {
    return request.post<void>(
      `/api/v1/bastion/sessions/${sessionId}/second-approve`)
  },

  /** 拒绝堡垒机请求 */
  reject(sessionId: string, reason: string) {
    return request.post<void>(
      `/api/v1/bastion/sessions/${sessionId}/reject`, { reason })
  },

  /** 获取 Guacamole 连接参数 */
  getConnectParams(sessionId: string) {
    return request.get<ConnectParams>(
      `/api/v1/bastion/sessions/${sessionId}/connect`)
  },

  /** 会话列表（审计用） */
  listSessions(params?: {
    status?:     SessionStatus
    deviceId?:   string
    requesterId?: string
    page?:       number
    size?:       number
  }) {
    return request.get<PageResult<SessionSummary>>(
      '/api/v1/bastion/sessions', { params })
  },
}

export interface PageResult<T> {
  content:       T[]
  totalElements: number
  totalPages:    number
}
```

### 5.3 Guacamole 嵌入 Web 终端

**文件**：`src/frontend/pages/bastion/terminal/[id].vue`
```javascript
<script setup lang="ts">
const route    = useRoute()
const sessionId = route.params.id as string

// 获取 Guacamole 连接参数
const { data: conn } = await bastionApi.getConnectParams(sessionId)
// conn.guacamoleUrl + '?token=' + conn.guacToken
const iframeSrc = computed(() =>
  conn.value.guacamoleUrl + '?token=' + conn.value.guacToken
)
</script>

<template>
  <div class="terminal-container">
    <div class="header">
      <span>设备：{{ conn.deviceName }}</span>
      <el-tag type="danger">{{ conn.protocol }}</el-tag>
      <span>会话有效至：{{ formatExpiry(conn.expiresAt) }}</span>
    </div>
    <iframe :src="iframeSrc" class="guac-frame"
      allow="fullscreen" />
  </div>
</template>

<style scoped>
.guac-frame {
  width: 100%;
  height: calc(100vh - 120px);
  border: none;
}
</style>
```

### 5.3 审计日志页（padmin）

```javascript
<el-table :data="sessions">
  <el-table-column label="设备" prop="deviceName" />
  <el-table-column label="操作人" prop="requesterName" />
  <el-table-column label="协议" prop="protocol" />
  <el-table-column label="时间" prop="connectedAt" />
  <el-table-column label="时长" prop="duration" />
  <el-table-column label="录屏">
    <template #default="{row}">
      <el-button v-if="row.recordingUrl"
        @click="playback(row)">
        播放录屏
      </el-button>
      <span v-else>暂无</span>
    </template>
  </el-table-column>
</el-table>
```
---

## 六、验收检查项

- [ ] 申请堡垒机审批后获得 Guacamole token
- [ ] 生产设备必须经过双审批才可连接
- [ ] 非生产设备单审批即可连接
- [ ] Token 超期（1小时）后连接返回 401
- [ ] 会话结束后 30 秒内录屏归档到 MinIO
- [ ] 审计日志页展示录屏播放键

## 七、代码核查补充（commit cca471d · 2026-10-17）

> [!WARNING]
> 本节根据仓库实际代码与合同 M7-0～M7-9、M10-3 核对，与前文冲突时以本节为准。重点：仓库中**没有任何远程会话 / 堡垒机代码**，只在 V1 配置表登记了 `remote.*` / `recording.*` 配置键。合同 M7-0 明确：《远程处置技术路线确认书》经甲方书面确认前，**M7 不得进入排期**。本页的 JumpServer + Guacamole 方案只能作为候选方案提交评审。

### 当前代码现状

| 项 | 现状 |
|---|---|
| 远程会话 / 授权清单 / 录屏 / 命令日志 | 无表、无接口、无页面（契约 `contracts/local-api.openapi.json` 中也没有） |
| 配置键（V1 `mop_configuration`） | 已固定：`remote.approval_expiry_min=30`、`remote.default_session_min=60`、`remote.engineer_concurrency=2`、`remote.authorization_remind_days=7`、`recording.hot_retention_months=6`；待确认（null）：`remote.bastion_baseline`、`remote.approver_policy`、`remote.idle_timeout_sec`、`remote.extension_policy`、`remote.high_risk_confirmation_policy`、`recording.readonly_enabled`、`recording.customer_access_approval` |
| 可复用 | 审计 hash 链（M7-5 操作码需新增）、工单时间线（D07）、对象存储与 900 秒短时链接、项目 RBAC（msp / padmin / tadmin / viewer） |

### 前文需要更正

| 前文写法 | 应改为 |
|---|---|
| 前置条件只写「JumpServer / Guacamole 已安装」 | 先决条件是 **Q01 路线确认 + Q03 组件 / 许可基线**（`remote.bastion_baseline`）书面批准；未批准前只能做设计说明与容量测算 |
| V40 `bastion_sessions`（`js_session_id`、`guac_token`） | 迁移号按合入顺序取 V33+；表结构应与具体产品解耦：远程申请、审批、会话、设备授权、录屏索引、命令日志分表，外部会话 ID 作为适配层字段 |
| 会话过期 / 录屏归档用 `@Scheduled(fixedRate / fixedDelay)` | 无 `@Scheduled`；审批超时、授权到期提醒、录屏归档均为独立 Worker + PG 租约 |
| `/second-approve` 两段固定接口 | readwrite 需「双人审批、审批人 ≠ 申请人、自审拦截」；审批人策略读 `remote.approver_policy`（Q01 / Q09），不要写死两级接口 |
| `com.mop.bastion.*`、`src/frontend/pages/bastion/*`、`bastionApi.ts` | `cn.mop.*`、`cn.mop.adapters.<产品>`；前端 `apps/web/src/pages/*Page.vue`  ＋ `api/client.ts` |
| 前端直接嵌入 Guacamole token | 建连令牌只在服务端校验审批与授权后一次性签发；无有效审批时建连接口必须拒绝并写审计（硬控制） |

### 合同主线要求（M7-6～M7-9，确认后实施）

- **M7-6 交互式远程**：工单详情页发起 → 选本租户已授权设备 + readonly / readwrite + 预计时长 + 事由（关联工单）；readwrite 双人审批，readonly 免审但留痕；审批 30 分钟超时作废；执行前再次校验资产状态与授权；默认时长 60 分钟可申请延长；单工程师并发 ≤ 2；结束自动回填工单时间线（时长 / 命令条数 / 录屏引用）；超时无操作自动断开。
- **M7-7 设备授权清单**：工程师 / 设备或设备组 / 级别 / 生效与到期 / 授权人 / 状态；批量授权；到期自动失效、提前 7 天提醒；临时授权（单次工单）；租户管理员只读查看。
- **M7-8 会话录屏**：readwrite 100% 强制录屏，readonly 按租户开关；热存储 6 个月后转冷备；按工单 / 设备 / 工程师 / 时间检索并定位到命令时间点；设计说明必须给出容量测算（会话数 × 平均时长 × 码率）；仅服务经理 / 平台管理员 / 审计角色可看，客户申请审批后可看本租户录屏。
- **M7-9 命令日志与高危拦截**：会话 ID / 序号 / 时间戳 / 原文 / 结果摘要 / 退出码 / 耗时；内置高危清单（reboot / erase / delete / format / writeerase 等）默认拦截需二次确认，清单可配置；密码 / 密钥参数替换为 `?`；关键词检索 \< 3 秒；导出走短时签名地址。
- **M7-5 审计**：申请 / 审批 / 建连 / 结束 / 录屏查看 / 高危拦截均写审计操作码。
- **M7-1～M7-4 受控自动化**为可选增强，须单独书面确认并报价，不得默认纳入。
- **M10-3 远程处置率**依赖本模块的会话与工单关联数据。

### 修订后的实施步骤

1. 输出《远程处置技术路线确认书》：交互式路线、候选产品（如 JumpServer + Guacamole）及许可、录屏容量测算、M7-1～4 是否纳入及报价依据 → 甲方书面确认（Q01 / Q03）。
2. 确认后：V33+ 建远程领域表；`cn.mop.adapters.<产品>` 实现堡垒机适配（资产同步、会话令牌、录屏与命令日志回收）。
3. 实现授权器与审批状态机（双人审批、自审拦截、超时作废、执行前复核）。
4. Worker：审批超时、授权到期提醒（7 天）、录屏归档与冷热迁移、会话结束回填工单。
5. 前端：工单详情「发起远程会话」、审批页、授权清单（客户只读视图）、录屏回放与命令检索。
6. 在指定测试环境与真实堡垒机联调后再做验收。

### 补充验收（合同原文摘录）

- [ ] 未审批直接调用建连接口被拒绝且写入审计；审批人等于申请人时被拦截
- [ ] 会话结束后工单时间线出现完整处置记录；录屏可按工单检索回放并定位到命令时间点
- [ ] 授权过期后发起会话被拒绝；readonly 授权的工程师无法申请 readwrite 会话；客户侧授权清单只读
- [ ] readwrite 会话 100% 有录屏文件；无权限角色无法播放
- [ ] 高危命令被拦截并需二次确认；日志中密码以 `?` 显示；关键词检索 3 秒内返回
阻塞：B13（M7 路线 / 许可未签）、B17（录屏留存与安全治理）、B19（岗位授权口径）；待确认 Q01、Q03、Q09、Q10。
