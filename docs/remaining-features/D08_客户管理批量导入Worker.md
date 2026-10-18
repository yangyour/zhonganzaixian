# D08 客户管理批量导入Worker · 开发文档

> 本文件由 Notion 页面导出（2026-10-18），内容以 Notion 原页面为准：https://app.notion.com/p/6853b5d6c3e24f11b6d3fda1aeaedb9d

> [!WARNING]
> 目标：完成客户联系人/资产批量导入 Worker，包括领取、批量写入、重启恢复、导入结果通知。<br>前置条件：客户确认 XLSX 导入模板字段映射。

> [!WARNING]
> **代码核查（commit cca471d · 2026-10-17）**：本页部分表名、迁移号、包路径及 JPA / `@Scheduled` 写法与仓库实际不符，开发前先读页尾「七、代码核查补充」，冲突处以该节为准。联系人导入已实现，真正缺口是资产业务入库（can_import=false）。

## 一、导入作业结构回顾

现有 `V18/V19` 已完成以下结构：
```sql
-- import_jobs: 作业头，202 受理记录
-- import_job_items: 行级导入项，包含 json_payload、status、error_msg
```
**只需实现 Worker 领取和处理逻辑，不需新建迁移。**
---

## 一补、作业领取与租约实现

> [!WARNING]
> 领取利用 PG `FOR UPDATE SKIP LOCKED` 实现分布式领取，多个 Worker 节点不会重复处理同一作业。

**文件**：`src/main/java/com/mop/customer/repository/ImportJobRepository.java`
```java
@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    /**
     * 领取一个 PENDING 作业并设置租约到期时间。
     * FOR UPDATE SKIP LOCKED 确保多个 Worker 不会抢占同一个作业。
     */
    @Query(value = """
        SELECT * FROM import_jobs
        WHERE status = 'PENDING'
          AND (lease_expires_at IS NULL OR lease_expires_at < NOW())
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<ImportJob> claimOneRaw();

    /**
     * 裡片 claimOneWithLease 封装（需在 Service 层调用以开新事务）
     */
    @Modifying
    @Query(value = """
        UPDATE import_jobs
        SET status           = 'RUNNING',
            lease_expires_at = :expiry,
            started_at       = NOW()
        WHERE id = :id
          AND status IN ('PENDING','RUNNING')
        """, nativeQuery = true)
    int updateToRunning(
        @Param("id")     UUID    id,
        @Param("expiry") Instant expiry
    );

    /**
     * 刷新租约，防止超时被其他 Worker 拓走。
     */
    @Modifying
    @Query(value = """
        UPDATE import_jobs
        SET lease_expires_at = :expiry
        WHERE id = :id
        """, nativeQuery = true)
    void renewLease(
        @Param("id")     UUID    id,
        @Param("expiry") Instant expiry
    );
}
```
**V18 迁移补充字段**（如果还没有）：
```sql
-- 补入 V18 或新增小迁移
ALTER TABLE import_jobs
  ADD COLUMN IF NOT EXISTS status           TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
  ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS started_at       TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completed_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS error_msg        TEXT;
```
---

## 二、导入 Worker 实现

**文件**：`src/main/java/com/mop/customer/ImportWorker.java`
```java
@Component @Slf4j
public class ImportWorker {

    private static final int BATCH = 50;

    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void process() {
        // 领取一个 PENDING 作业（租约马, fencing 魔法）
        Optional<ImportJob> jobOpt = jobRepo.claimOneWithLease(Instant.now().plusSeconds(300));
        if (jobOpt.isEmpty()) return;

        ImportJob job = jobOpt.get();
        log.info("开始导入作业 job={} tenant={}",
            job.getId(), job.getTenantId());

        try {
            processJob(job);
            job.setStatus(ImportJobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
        } catch (Exception e) {
            job.setStatus(ImportJobStatus.FAILED);
            job.setErrorMsg(e.getMessage());
            log.error("导入作业失败 job={}", job.getId(), e);
        } finally {
            jobRepo.save(job);
            // 发送导入完成通知
            outboxService.publish("import.completed", job.getId(), job.getTenantId());
        }
    }

    private void processJob(ImportJob job) {
        // 从 MinIO 下载 XLSX
        byte[] xlsx = storageService.download(
            job.getBucketName(), job.getObjectKey());

        // 解析 XLSX
        List<Map<String, String>> rows = xlsxParser.parse(xlsx,
            job.getFieldMappings()); // 字段映射来自作业元数据

        // 分批处理
        List<List<Map<String, String>>> batches = Lists.partition(rows, BATCH);
        int rowIndex = 0;
        for (List<Map<String, String>> batch : batches) {
            for (Map<String, String> row : batch) {
                processRow(job, rowIndex++, row);
            }
            // 刷新租约，防止超时被其他 Worker 抜走
            jobRepo.renewLease(job.getId(), Instant.now().plusSeconds(300));
        }
    }

    private void processRow(ImportJob job, int rowIndex, Map<String, String> row) {
        ImportJobItem item = itemRepo
            .findByJobIdAndRowIndex(job.getId(), rowIndex)
            .orElse(ImportJobItem.pending(job.getId(), rowIndex));

        // 幂等性：已完成的行不重复处理
        if (item.getStatus() == ItemStatus.COMPLETED) return;

        try {
            switch (job.getImportType()) {
                case CUSTOMER_CONTACTS -> contactService.upsert(
                    job.getTenantId(), row);
                case ASSETS -> assetService.upsert(
                    job.getTenantId(), row);
            }
            item.setStatus(ItemStatus.COMPLETED);
        } catch (ValidationException e) {
            item.setStatus(ItemStatus.FAILED);
            item.setErrorMsg(e.getMessage());
            log.warn("导入行失败 job={} row={}: {}",
                job.getId(), rowIndex, e.getMessage());
        }
        itemRepo.save(item);
    }
}
```
---

## 三、XLSX 解析器

**文件**：`src/main/java/com/mop/customer/XlsxParser.java`
```java
@Component
public class XlsxParser {

    /**
     * @param xlsx         Excel 文件内容
     * @param fieldMapping 导入列名 -> 内部字段名的映射
     */
    public List<Map<String, String>> parse(
            byte[] xlsx,
            Map<String, String> fieldMapping) throws IOException {

        List<Map<String, String>> result = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) throw new ParseException("表格无表头行");

            // 建立列索引映射
            Map<Integer, String> colToField = new HashMap<>();
            for (Cell cell : header) {
                String colName = cell.getStringCellValue().trim();
                String fieldName = fieldMapping.get(colName);
                if (fieldName != null) colToField.put(cell.getColumnIndex(), fieldName);
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, String> rowData = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> e : colToField.entrySet()) {
                    Cell cell = row.getCell(e.getKey(), RETURN_BLANK_AS_NULL);
                    rowData.put(e.getValue(), cell == null ? null
                        : dataFormatter.formatCellValue(cell));
                }
                if (!rowData.isEmpty()) result.add(rowData);
            }
        }
        return result;
    }
}
```
---

## 四、导入验证逻辑

**文件**：`src/main/java/com/mop/customer/ContactImportValidator.java`
```java
@Component
public class ContactImportValidator {

    public void validate(Map<String, String> row) {
        // 必填字段
        requireNonBlank(row, "name",  "姓名不能为空");
        requireNonBlank(row, "phone", "手机号不能为空");

        // 手机号格式校验
        String phone = row.get("phone");
        if (phone != null && !phone.matches("^1[3-9]\\d{9}$"))
            throw new ValidationException("手机号格式错误: " + phone);

        // 邮笱格式校验（如果有）
        String email = row.get("email");
        if (email != null && !email.matches("^[\\w.-]+@[\\w.-]+\\.[a-z]{2,}$"))
            throw new ValidationException("邮笱格式错误: " + email);
    }
}
```
---

## 五、前端页面

### 5.1 导入页面

**路由**：`/customers/import`
```javascript
<template>
  <!-- 第一步：下载模板 -->
  <el-button @click="downloadTemplate">下载导入模板</el-button>

  <!-- 第二步：上传文件 -->
  <el-upload
    :action="uploadUrl"
    accept=".xlsx,.xls"
    :on-success="onUploadSuccess">
    <el-button type="primary">选择 Excel 文件</el-button>
  </el-upload>

  <!-- 第三步：进度监控 -->
  <ImportProgress v-if="jobId" :job-id="jobId" />
</template>
```

### 5.2 ImportProgress 组件

```javascript
<script setup lang="ts">
const props  = defineProps<{ jobId: string }>()
const status = ref<'PENDING'|'RUNNING'|'COMPLETED'|'FAILED'>('PENDING')
const total  = ref(0)
const done   = ref(0)
const errors = ref<{row:number, msg:string}[]>([])

// 轮询作业状态
const poll = setInterval(async () => {
  const job = await importApi.getJob(props.jobId)
  status.value = job.status
  total.value  = job.totalRows
  done.value   = job.completedRows
  errors.value = job.errors
  if (['COMPLETED','FAILED'].includes(job.status)) clearInterval(poll)
}, 3_000)
</script>

<template>
  <el-progress :percentage="Math.round(done / total * 100)" />
  <el-tag :type="status === 'COMPLETED' ? 'success' : 'info'">
    {{ statusLabel[status] }}
  </el-tag>
  <el-collapse-item v-if="errors.length" title="失败行详情">
    <el-table :data="errors" size="small">
      <el-table-column label="行号" prop="row" width="80" />
      <el-table-column label="错误信息" prop="msg" />
    </el-table>
  </el-collapse-item>
</template>
```
---

## 六、验收检查项

- [ ] Worker 重启后从断点继续，已完成行不重复导入
- [ ] 验证失败的行标记 FAILED 并记录错误信息
- [ ] 批量导入 500 行 \< 30 秒完成
- [ ] 导入完成后发送企微通知（依赖 D11）
- [ ] 导入结果可通过 API 回读逐行状态
- [ ] 不同租户导入作业严格隔离

## 七、代码核查补充（commit cca471d · 2026-10-17）

> [!WARNING]
> 本节根据仓库实际代码与合同 M2-9、M3-6、M3-7 核对，与前文冲突时以本节为准。重点：联系人导入（V18/V19 + contact-worker）已经实现，前文的 `import_jobs` / `XlsxParser` / JPA / `@Scheduled` 方案不要再做；本页真正的缺口是**资产业务入库**（`can_import=false`）。

### 已有实现（不要重复建设）

| 能力 | 迁移 / 代码 | 现状 |
|---|---|---|
| 联系人批量新建作业 | V18 `mop_contact_import_job`（row_count 1–100、payload_bytes ≤ 512KB、source_format `contact-create-only-v1`、state / attempts / fencing_token / lease_owner / lease_until / next_at）、`mop_contact_import_result`；V19 生成列哈希守卫修复 | 显式 JSON CREATE_ONLY，同租户已有联系人不合并不覆盖 |
| 联系人 Worker | `server/worker`（`cn.mop.contactworker.*`），独立 JAR，PG 租约 30 000 ms；compose 服务 `contact-worker` | 整批联系人 + 修订 / 结果 + 审计 + 幂等收据同一事务提交 |
| 联系人接口 / 页面 | `ContactImportController`：`POST/GET /api/v1/contact-imports`、`GET /contact-imports/{id}`；`CustomerContactImportPreviewController`；`ContactImportsPage.vue`、`ContactImportPreviewPage.vue` | 不含 CSV/XLSX、通知、账号创建 |
| 资产导入源暂存 | V6 `mop_asset_import_source / _operation / _job / _artifact / _mapping`；MinIO bucket `mop-test-asset-imports` | 上传 → 解析 → 显式列映射可用 |
| 解析器 | `server/import-parser`（`CsvSourceParser`、`XlsxSourceParser`、`ParseBudget`、`SafeXml`，POI 5.5.1，子进程隔离）；`server/worker`（`cn.mop.importworker`） | 拒绝公式 / 宏 / 外部链接 |
| 合同 15 列模板 | `AssetImportTemplate`  ＋ `GET /api/v1/assets/import-template` | 只有表头，无演示行 |
| 资产导入接口 | `GET /assets/import-policy`、`POST /assets/import-sources`（multipart）、`GET /assets/import-sources/{id}`、`POST /assets/import-sources/{id}/parses`、`/mappings`、`GET /asset-import-operations/{id}` | 返回 `business_status=CONFIG_PENDING`、`can_import=false`、`precheck_status=NOT_RUN` |
| 前端 | `apps/web/src/utils/assetImport*.ts` 及资产页导入面板 | 三步式弹窗的前两步可用 |

### 前文需要更正

| 前文写法 | 应改为 |
|---|---|
| `import_jobs` / `import_job_items`、`ALTER TABLE import_jobs` 补字段 | 联系人用已有 `mop_contact_import_job/_result`；资产用 V6 五张表；V1–V32 已冻结，补字段只能新增 V33+ 迁移 |
| `ImportJobRepository extends JpaRepository`、`com.mop.customer.*` | 无 JPA；包名 `cn.mop.*`，参照 `server/persistence` 的 JDBC 仓储写法 |
| `ImportWorker` 用 `@Scheduled(fixedDelay = 5_000)` | Worker 是独立进程（`contact-worker` / `import-worker`），PG 租约领取 + fencing_token，不在 API 进程内调度 |
| `XlsxParser` 用 `wb.getSheetAt(0)` 直接解析 | 复用 `import-parser` 子进程与 `ParseBudget`；必须显式选择工作表位置和表头行 |
| 「500 行 \< 30 秒」 | 合同 M3-6 为 **≤ 60 秒**（`import.max_elapsed_sec=60`，Q13 压测条件待确认） |
| 导入完成后直接推企微 | 结果走站内消息 / 通知意图（D11），企微渠道未接通前不硬编码 |
| 前端 `src/frontend/...`  ＋ ImportProgress 组件 | `apps/web/src/pages/*Page.vue`  ＋ `utils/*Workspace.ts`，异步结果统一轮询 `/operations/{id}` 类接口 |

### 合同要求但前文未覆盖（M3-6）

- 15 列模板：设备名称 / 设备类型（9 类枚举）/ IP 或域名 / 品牌 / 型号 / 序列号 / 站点 / 位置 / 负责人 / 所属业务 / 采购日期 / 维保到期日 / 超期服役日期 / 采集方式 / 备注（必填，`import.note_required=true`）。
- 单批 ≤ 500 行（`import.max_rows=500`），`.xlsx` / `.csv`（`import.formats`）。
- 预览逐行 ✅ / ⚠️ / ❌；错误级：必填缺失、IP / 域名非法、租户内设备名重复（提示「与第 N 行重复」）、IP 与库内冲突、类型不在枚举；警告级：日期非法置空、负责人非本租户成员置空、品牌无 OID 映射。
- 结果「成功 N / 失败 M」，失败行下载错误回执 `.xlsx`（行号 / 原值 / 错误原因 / 修正建议）。
- 幂等：租户 + 设备名 + IP 去重，同一文件重复导入不产生重复设备。
- 审计含文件 hash、导入行数、成功 / 失败统计。
- 入库前必须原子占用设备配额（M2-11，见 D18），超额整批拒绝。

### 修订后的实施步骤

1. 新增 V33+ 迁移：资产导入批次结果 / 行级校验结果表（或在 V6 artifact 上扩展），不改冻结 SQL。
2. 在 `import-worker` 内实现业务预检（错误级 / 警告级规则），规则中依赖 Q31 的字典（`asset.columns`、`asset.import_steps`、`asset.ip_scope`）只读配置，null 时预检返回待配置而不是默认放行。
3. 实现业务入库：同事务内配额占用 + 幂等键（tenant_id, name, ip）+ 审计，`can_import` 改为按配置发布状态计算。
4. 生成错误回执 xlsx 存 MinIO，经 `export.signed_url_ttl_sec=900` 短时链接下载并写下载审计。
5. 前端补第三步「预览与校验」和结果页；结果通知走站内消息。
6. 500 行性能用例在指定测试环境执行并留证（B18）。

### 补充验收（合同原文摘录）

- [ ] 含 3 类错误的 50 行台账，错误行 100% 被拦截且回执原因准确
- [ ] 500 行导入耗时 ≤ 60 秒
- [ ] 同一文件重复导入不产生重复设备
- [ ] 导入超出设备配额时整批拒绝，配额计数不漂移
- [ ] 审计日志含文件 hash、行数、成功 / 失败统计
阻塞：B07（配额 / 五步纳管）、B08（`can_import=false`、导出 `download_allowed=false`）、B18（性能基准）；待确认 Q13（压测条件）、Q16（配额）、Q31（台账列与 IP 冲突域）。
