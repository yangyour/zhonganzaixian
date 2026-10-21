# M6 配置备份运行与尝试事实边界

## 合同固定部分与当前范围

M6-1/T00R078明确一次初始尝试、失败后重试2次且间隔10分钟；M6-4/T00R081要求连续2次备份失败关联P3，但Q22仍未确认“次”按attempt还是完整run以及跨运行计数重置。当前只实现不可变BackupRunState领域模型；没有设备连接、凭据、采集、存储、表结构、定时器、Worker或业务API。技术时间边界为UTC毫秒、0001—9999年；版本号/引用字节数使用跨端可精确表示正整数上限，不代表客户配额。

run派生状态QUEUED→RUNNING→RETRY_WAIT→SUCCEEDED/FAILED；attempt单独保存序号、开始、最后观察、结束、结果、错误分类和成功版本引用。每次已明确失败后延迟10分钟才允许下一尝试，第三次已明确失败才是此run的最终FAILED。尚未发生的尝试/成功时间/最终失败计数保留null，不把尝试失败当整次run失败；成功重试仍保留前面失败的attempt。本模型的knownFailedAttemptCount仅统计当前run已明确失败的attempt，finalRunFailureCount只在终局为0/1，并非跨run连续计数，也不决定建单。

## 未知结果、存储与隔离

丢失响应或结果不明只标RESULT_UNKNOWN，run保持RUNNING、reconciliationRequired=true、nextAttemptAt=null；即使已超过10分钟也不允许盲目start。只能在独立核查后明确同一次attempt成功或失败；失败重试时间从确认失败时起算。本模型不执行核查，网络断开不能直接调用fail当作设备未执行。未知状态的后续观察必须时间单调；当前attempt快照的变化不是完整审计流水，未来仓储须另存变更事件及审计。

成功转换要求非空StoredVersion，绑定同租户同设备（错对象404）、有效版本UUID、加密对象SHA256/字节数/持久化时间；引用时间必须处于当前attempt开始与成功确认之间。此引用只表达调用者提交的已持久化事实，不能凭格式验证证明对象加密、KMS、字节存在、OSS权限或对象校验完成。未来适配器必须实际核验对象及元数据事务后再调用succeed；对象保存失败不能填充成功引用。本模型没有明文配置或密钥字段。

所有转换产生新对象/不可变列表及递增逻辑版本，终局不可重开、同一attempt不能再次start或重复完成，早于观察的结束时间拒绝，重试日期溢出拒绝。expectedVersion只是纯对象检查，尚无数据库CAS、跨进程锁、租约或fence证据；未来仓储仍须当前身份/租户授权、幂等、真实CAS与独立审计。HOST_KEY_REJECTED/AUTHORIZATION_REJECTED等分类不会放宽主机信任或授权；即使模型达到retry due，也不允许绕过重新授权与固定主机指纹。

## 门禁与验证

所有状态保持failureCountSemantics=null、automaticTicketStatus=CONFIG_PENDING、automaticTicketAllowed=false；没有选择Q22口径或自动P3，也没有默认批准计划、真实SSH命令、报备匹配、保留删除、恢复下发或生产密钥。每日02:00调度、6小时diff、客户概况与跨run最近成功事实均不在此切片，不根据一个run制造这些结果。

20纯单元验证：初始/两重试、精确10分钟边界、第三失败终局、成功重试保留失败attempt、未知结果阻止重试及同attempt核查、单调观察、跨租户/设备404、空/跨时间存储引用拒绝、逻辑版本、不可变快照、终局保护和日期溢出。完整回归结果另见证据；这些单元没有本机组件或假设备服务充当真实联调。M6-1/M6-4及128项均不据此标DONE。

根1327报告=1314执行（392既有真实PG）、13历史fixture跳过/6MinIO排除，70契约/生成一致性通过；未修改API0.33或V1–V19，未重启API。前版本传输12e6cb46 CI37158297916 success已回读。见[测试证据](evidence/niko-backup-run-state-20261003.json)。


后续补充：[设备协调租约状态与过期失联边界](backup-device-lease-core-20261021.md)已实现纯状态及结构恢复规则；实际数据库锁、Worker及设备停止核查尚未完成，不能把租约过期当作自动重领许可。

## 2026-10-21 数据库协调增量

[设备协调事务与验收](backup-device-journal-20261021.md)已补，V46 追加式日志及仓储在隔离 PostgreSQL 验证通过。该日志不是备份执行记录，不证明远端停止，过期不能接管；真实授权/停止核查适配器、任务 Worker、KMS/归档等仍待接入。

[运行快照恢复与规范载荷](backup-run-snapshot-20261021.md)新增严格重建及 runId/租户/设备绑定的有界编码，未知结果恢复后仍阻断重试；实际运行事件仓储与 Worker 继续开发，编码器不是数据库持久化证明。

## 2026-10-21 运行事件持久化增量

[运行追加日志与仓储](backup-run-journal-20261021.md)新增 V47 与 PostgresBackupRunRepository，补充数据库 CAS/幂等及当前租约绑定，保留未知结果和十分钟重试边界。上文纯模型历史边界不代表本次尚无仓储；真实授权、对象核查、Worker和设备执行仍未完成。
