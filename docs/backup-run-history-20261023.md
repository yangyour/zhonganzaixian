# B02：授权备份运行历史与失败事实

## 已接线

`GET /api/v1/assets/{assetId}/backup-runs` → 当前成员/内部角色及 assets:read → 当前资产 → 私有 RUN_HISTORY 独立操作授权 → V47 最新运行快照读取/摘要解码 → 同一事务总数/游标页 → 返回前完整成员复核 → 资产详情“备份运行与失败记录”。

- RUN_HISTORY 是独立私有grant操作，不从 VERSION_INVENTORY、下载、解密、资产可见或备份草稿权限推导。代码仅识别此操作，没有给任何账号授予它。
- `MOP_BACKUP_RUN_HISTORY_BINDING` 和原受控 `MOP_BACKUP_ACCESS_GRANTS_FILE` 缺失时返回503未配置，不返回空列表。现有grant文件需管理员另行明确授权，不自动更改。
- 仓储查询仅SELECT，身份/资产使用share锁，因此不是设置数据库readOnly事务属性；在REPEATABLE READ内先确定当前资产，核对独立授权，读取总运行数和按 queued_at/run_id 倒序的最新快照，再次检查grant后提交。没有对象存储、KMS或设备访问。
- 每个run只读取最大version，不把每次事件版本当独立运行。游标限制在选取最新版本之后应用；完整首屏数量和明细矛盾时失败，不把缺失行解释为空成功。
- V47快照沿用原有 BackupRunSnapshotCodec，验证摘要、范围、可重建状态和规范编码，同时核对行头的版本/状态/排队/观察时间/attempt_number。只返回安全的状态/时间/失败分类，不泄露密文、密钥引用、存储对象或下载地址。
- 单次运行的 `known_failed_attempt_count`、终局 `final_run_failure_count` 分开。两次尝试失败但还可重试时，最终运行失败保持null；RESULT_UNKNOWN不是失败，也不能自动重试。3次尝试后FAILED的run最终计1，不计3。
- 页面点击后才读取，支持当前页/总数、展开尝试、下一页、失败/取消后的原页重试、账号/租户/权限/资产变化清空和迟到响应丢弃。新增面板沿用资产详情结构，没有新增大统计占位或菜单改版。

## 明确保留的业务缺口

本批是失败事实读取链路，不是整个B02.1失败自动建单已完成。Q22未确认的“连续失败按attempt还是run、如何重置”仍为null，连续失败数为null，自动P3始终CONFIG_PENDING_Q22/false。合同已明确的两次阈值和600秒要求保留，但不能从这两个常量推导已获准建单。

后续需接可发布的Q22策略、明确有序来源、持久幂等工单意图和实际工单消费/补偿。恢复申请审批/人工结果、演练附件、保留清理及差异变更联动仍未实现完毕。不得为跨运行计数猜测未知/重叠运行的重置含义。

`snapshot_payload_verified=true`仅表示本次结构/摘要核验，不表示完整历史链或真实设备证明；`full_journal_chain_verified=false`、`object_storage_accessed=false`、`device_execution_authorized=false`、`writes_performed=false`。各页独立重新授权，不是跨页固定快照，分页时总数可以变化。

## 契约、组件与验证

公共OpenAPI从 `tools/backup-run-history-contract.mjs` 生成并保留所有既有接口；离线DEMO仅更新来源摘要/golden，仍47个已声明页面，0正式验收/0初始化。无新增数据库迁移，不更改冻结V1–V51。

验证详情记录于 `docs/evidence/dot-continuation-20261023/b02-run-history.json`。指定PostgreSQL/OIDC/浏览器所需私有连接及主机信任未提供，实际联调NOT_RUN。JDBC doubles验证查询/授权调用，不能当实际SQL执行证明；HTTP过滤器与jsdom不能当真实浏览器登录验收。大生成契约的既有远端上传阻塞仍在，本批先本地逐功能提交，不冒充已入main。

首次全量中的3个HTTP测试启动错误定位为新隔离测试上下文遗漏安全过滤器所需ObjectMapper Bean；补齐测试装配后重跑，不修改安全断言或关闭过滤器。

最终本地回归：Java 4,979发现 / 3,861执行通过 / 1,118条件跳过；前端331文件 / 5,327通过；Node契约251通过；离线DEMO18通过；类型及构建通过（既有chunk提示保留）。128项/25工作组覆盖检查通过，completion_verified=false。没有将跳过的真实组件测试计为通过。
