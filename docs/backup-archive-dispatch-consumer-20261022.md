# 原信封归档：显式单次消费者

## 已实现

BackupArchiveDispatch 接通既有 V48 原信封/追加日志与 BackupEnvelopeEncryption、BackupEnvelopeArchive。接受一个已由调用方持久保存的原命令：资产/运行/尝试/版本范围、原预期归档版本、dispatch ID/owner、三个不同的稳定命令 ID、证据引用和 trace。没有轮询、任务发现、Spring 自动 Bean 或启动入口；不会自动建新命令。

Binding 默认装配缺失；enabled=false 无任何读取/授权/存储动作，enabled=true 仍要求显式消费者绑定引用及独立 Authority。此引用形状不是审批证明。所有实际操作仍受仓储事务授权、存储授权、加密授权和实际证据验证器约束，不继承草稿权限或管理员身份。

## 有界执行与未知结果

1. 读取当前完整原任务，拒绝跨范围、历史 replay 冒充当前状态、版本漂移和不匹配 dispatch。
2. 仅当前 STAGED 且原版本匹配，允许请求 START_STORE。只有新提交、非 replay、原信封/身份/版本/计数匹配的回执可进入对象写入。
3. 使用已持久化原信封字节及 SHA 解码，调用现有不可覆盖存储适配器；不 seal、不生成新密钥、不替换版本、不重新加密。
4. START_STORE 回执未知，禁止调用存储；后续从当前日志恢复。已 STORE_STARTED / VERIFY_REQUIRED 的同一 dispatch 只做原对象核查，不再 PUT。
5. 上传或核查异常尝试记录 MARK_UNCONFIRMED；标记失败、并发核验或未知提交均保持 RECONCILIATION_REQUIRED，不以读取失败/对象暂未找到证明原 PUT 没发生。消费者从不调用 CONFIRM_NO_DISPATCH。
6. VERIFY_OBJECT 使用既有仓储独立证据核查及双授权/CAS；验证回执只表示归档日志记录，不完成版本登记或父运行。已经 VERIFIED 的返回只是历史记录，不访问存储或声称此刻对象健康。
7. 每个阶段及结果返回都做独立 Authority 检查；外部调用不包在该协调器持有的 SQL 事务中。并发/原命令幂等仍由既有仓储提供，不用进程内锁冒充分布式互斥。

返回仅为 DISABLED、ARCHIVE_VERIFICATION_RECORDED 或 RECONCILIATION_REQUIRED，事件版本可为未知null。storeMethodInvokedThisCall只表示本次调用过存储方法，不证明PUT提交。版本登记、父运行完成、设备执行、保留与恢复标志固定false。不返回信封、密钥引用、对象地址或底层异常文字。

## 配置与仍待链路

deploy/examples/backup-execution-dependencies.json 新增 archive_dispatch_consumer_binding_reference:null；31个引用槽均null，执行和生产批准仍false。例子不是运行时发布配置。真实绑定/Authority、仓储授权、原对象证明、KMS与不可覆盖对象存储仍须分别装配验证；不能把合成测试回调移作生产默认允许。

仍需：耐久任务发现/公平领取/调度生命周期、实际配置发布绑定、与 V49 版本登记/运行完成消费者连接、容量/保留清理及真实停止/恢复验证。本次没有设备命令、SSH、真实云存储、权限变更、消息发送、SQL迁移或生产启用。

## 证据分层

- 29项单命令/未知结果/权限/完整性检查，使用内存日志假实现，不是实际PG或并发事务验收。
- 4项生产原信封 writer 与真实JCE/现有Archive组合检查：原字节相同、新密钥创建0、只调用一次不可覆盖PUT；模拟存储、合成密钥，无KMS/OSS网络。丢失PUT回执、损坏回读及撤销加密权限分别拒绝成功。
- 完整Maven verify、Python依赖槽位检查、Node契约回归见证据；不重复计入此前真实PG验证。
- 前端生产代码未变；浏览器105项/12文件仅可发现、实际NOT_RUN；128正式合同TC仍NOT_RUN。

证据位于 docs/evidence/backup-archive-dispatch-consumer-20261022/。没有将消费者类存在等同于生产Worker运行或端到端备份成功。

## 后续实际数据库验证

[2026-10-22实库组合验证](backup-archive-dispatch-postgres-20261022.md)新增5项真实PG/JCE消费者检查，验证原命令恢复、并发仅一次PUT和撤权。此增量不改变本节合成KMS/存储/授权、未启用调度与未登记版本的边界；旧33项纯逻辑/适配器测试不重新改称实库测试。
