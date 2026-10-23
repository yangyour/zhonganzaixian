# 恢复演练证据核对工具

`tools/recovery_drill.py` 对现有私有备份流式计算SHA256，并核对演练前后提交的隔离环境观测。它不连接数据库、不运行pg_restore、不创建命名空间、不读出备份内容到日志、不启动／停止服务。接口用于在获授权演练后检查结果一致性，不能替代真实恢复执行或客户签收。

manifest字段：`format=1`、`environment`、`isolation_authorization_ref`、`backup_sha256`、`disruption_at`、`restore_started_at`、`ready_at`、`rpo_target_seconds`、`rto_target_seconds`、`source`、`restored`。RPO/RTO目标未确认保持null，不设业务默认值。时间必须含时区。

source和restored各包含：`namespace`（必须符合mop_drill_隔离命名）、`source_commit`、`artifact_sha256`、`schema_sha256`、`observed_at`、`recovered_through_at`、`dependencies_ready`、`objects_sha256`（未知为null）、`tables`。tables每项只含 `name`（schema.table）、`rows`、`sha256`，必须非空且名称唯一。生产／共享mop_test和实际业务租户标识不可作为演练命名空间；命名校验不等于已核验服务器隔离或授权。

核对内容：

- 实际备份归档SHA256与登记摘要一致；原文件只读、不覆盖。
- 同一隔离命名空间、应用提交／制品、schema历史、完整表集合、行数／内容摘要以及对象归档清单匹配。
- source观测不晚于故障，恢复开始不早于故障，就绪不早于恢复开始且不晚于恢复后观测；恢复水位不得晚于故障，也必须匹配备份基线。
- RPO按故障时间减恢复水位计算；RTO按就绪时间减故障时间计算，包含启动恢复前的等待。依赖未就绪或没有就绪时间时，RTO未知且恢复检查失败。
- 未提供目标值标NOT_ASSESSED，缺对象清单标NOT_RUN；任一不一致FAIL，缺项INCOMPLETE，全部匹配仅OBSERVATIONS_MATCH。

```powershell
py -3.11 tools/recovery_drill.py --manifest .local/drill-observations.json --backup .local/owned-drill-backup.dump --output .local/drill-review.json
```

退出0仅表示已给观测和归档一致，2表示失败或缺项，1表示输入／输出不可用；输出排他创建。报告始终包含 `restore_executed_by_this_tool=false`、`observations_authenticity_verified=false`、`isolation_authorization_verified=false`、`customer_acceptance_verified=false`、`production_ready=false`。时间、水位、表／对象摘要须来自真实、可审阅的采集证据；填入JSON本身不是证明。未校验业务抽样、数据库角色／权限恢复、密钥恢复或故障切换，不能拿本报告签收完整灾备。

2026-10-23验证：14项纯单元／临时合成文件测试通过，覆盖腐败归档、少行／改内容、不同schema／制品／命名空间、未知目标、缺对象、未就绪、时间倒置、阈值边界、重复表和输出防覆盖。没有在真实数据库执行恢复，没有触碰指定服务器现存库、账号或实际租户；真实RPO/RTO、获授权隔离恢复空间、演练窗口、完整组件重建及正式TC仍待条件具备。
