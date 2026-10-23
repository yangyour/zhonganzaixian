# 季度报告原子来源捕获

2026-10-23，报告会话；新增行为是一次提交完成“明确修订 → 来源集合 → 季度草稿”。原来的先建来源集合再建报告接口继续可用。

## 接口

`POST /api/v1/inspection-quarterly-reports/capture`，JSON，最多 262144 字节；当前租户会话、CSRF 头及单个 `Idempotency-Key` 必需。只接受以下字段：

```json
{
  "expected_version": 0,
  "label": "季度巡检草稿",
  "year": 2026,
  "quarter": 4,
  "sources": [
    {
      "worksheet_id": "10000000-0000-4000-8000-000000000001",
      "version": 2,
      "expected_source_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
  ],
  "configuration": {
    "revision": null,
    "time_zone": null,
    "duplicate_rule": null,
    "archived_rule": null,
    "metric_version": null,
    "report_template_version": null
  }
}
```

示例 UUID/摘要仅说明格式，不能直接作为业务来源提交。最多 1000 条来源，必须是调用方明确读取过的执行单修订及其快照 SHA-256；缺项、重复引用、非整数版本、未知字段、重复 JSON 键及尾随 JSON 均拒绝，不自动改选最新修订。

配置沿用季度 DRAFT 定义，六项显式可为 null。配置值存在不代表获准生效；重复来源策略、归档策略、期次时区和模板/指标版本均冻结在文档中。来源完整性只覆盖明确选定的集合，不能称为全租户设备覆盖。

200 响应沿用 `InspectionQuarterlyReportSummary`，增加 `capture_mode=ATOMIC_EXPLICIT_WORKSHEET_REVISIONS`，其中 `source_collection_id` 及两个来源摘要均由服务器在同一事务中生成。返回 `generation_status=DRAFT`、`configuration_source=REQUEST_DRAFT_NOT_APPROVED`、发布/证据授权 false、设备总体数量 null。详情及受保护 PDF 继续由原 ID 读取相同冻结文档；本接口不另建 PDF 或附件权限。

## 事务与权限

当前 padmin 成员必须同时具备 `inspection_quarterly_reports:write`、`inspection_source_collections:read`、`inspection_source_collections:write`、`inspection_worksheets:write`。最后一项沿用现有来源捕获保护，不改变旧端点权限模型。每次首次执行及幂等收据重放前均从当前数据库核验全部权限与权限版本。

来源集合/明细、报告、两条审计和一条幂等收据共用已有受保护命令连接。来源摘要冲突返回 409；越租户或不存在的来源返回 404；结构/内容不一致或数据库故障返回 503。任一步失败回滚本命令全部写入。同键同内容重试返回原报告；同键不同请求由现有幂等机制返回冲突。请求摘要覆盖期次、配置和每条来源版本/摘要，对 JSON 对象键排序，对来源数组顺序保持敏感。

## 运行与验证

复用已指定 LAN PostgreSQL 17.9 / V50，依赖现有 V23/V41 结构和角色上限；无新迁移、无自动应用 SQL、无本机替代数据库。当前组件清单仍为 14 服务就绪、production_ready=false。

```powershell
mvn -pl server/api -am '-Dtest=InspectionQuarterlyReport*Test,InspectionSourceCollectionInputTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
npm ci --ignore-scripts
npm test
node tools/build-api-contract.mjs --check
py -3.11 tools/verify-report-capture-isolated.py --output .local/report-capture-new-run
```

最后一个命令先排他取得固定协调目录的 `environment.lock`，从原主目录私有文件读取连接资料，核验 SSH 主机键/项目归属，通过回环 SSH 转发访问原 PG。只新建有明确前缀的独立测试租户、无登录凭据/绑定的测试主体和无采集配置的测试资产；Java 测试以受限 `mop_test_runtime` 运行。结束仅停用本次主体及成员，保留不可变报告与审计，不恢复历史测试身份、不改实际业务租户。锁被占用时不执行共享写入，不移除他人锁。

实际完成 82 项聚焦 Java 单元/隔离检查、7 项新隔离命名空间 PG 检查、196 项契约检查。PG 覆盖整链提交和新仓储读回、预期摘要错误零写入、报告插入后故障整链回滚、跨租户来源 404、过期权限重放拒绝、执行单后续修订不改变旧报告、同键并发只生成一个完整链。实际业务租户的来源/报告/执行单/成员/资产七表行指纹及迁移历史前后一致。

本轮未启动新增 TCP API/Web，也未执行浏览器、正式审批发布、PDF 新接口联调或正式合同 TC；既有 PDF 能力不因此获得新的验收状态。月报/体检、跨业务指标快照和阅读记录仍待继续实现。
