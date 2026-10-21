# 加密对象写入与原对象回读核验

## 已实现

BackupEnvelopeArchive 强制提供当前存储授权器、既有加密器及绑定ObjectStore。写入前先核对范围、对象存储前提和完整GCM认证，丢弃并尽力清除验证用明文；写入后重新读取完整对象、核对原字节数/SHA256、规范编码、范围及AEAD，再检查当前授权。

原生OSS传输新增未自动装配的backups()视图，使用固定部署前缀下backup-envelopes/tenantUUID/versionUUID，不接受URL或任意对象路径。上传的是编码信封，单次条件PUT、私有对象ACL、Content-MD5及SHA256核验，不提供multipart、公开URL、预签名下载、删除、桶设置修改或保留策略修改。

读取前后检查私有桶及对象ACL，允许Private或继承私有桶的Default；对象VersionId非空、未知/公开对象权限均拒绝。既有传输的TLS验证、V4签名、禁止重定向、SDK自动错误重试=0及请求资源边界保持不变。

OSS在桶版本控制Enabled或Suspended时忽略x-oss-forbid-overwrite，因此只接受明确从未版本化/Off且私有的前提，未知也不允许；不会自动关闭版本控制来适配。[OSS PutObject官方约束](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject)、[Java SDK禁止同名覆盖说明](https://www.alibabacloud.com/help/en/oss/developer-reference/prevent-objects-from-being-overwritten-by-objects-that-have-the-same-names-3)。

这些检查只能描述当前请求观察，不能锁住云管理员或其他写入身份。真正不可变命名空间、IAM和桶治理须独立批准并实测，不能把读取成功称为永久耐久性、WORM或保留承诺。后续权限应排除非必要的覆盖/删除/改ACL与改版本控制能力，本次没有配置任何真实权限。

## 未知结果及回执语义

- 一旦派发对象存储调用，异常、回读失败或后续撤权均返回BACKUP_ARCHIVE_WRITE_UNCONFIRMED；不能据此称原写入失败，更不能重新加密得到另一份密文后覆盖同一版本。
- verifyExisting只按原Scope、原字节数、原摘要读取并核查，不调用PUT、重新加密或生成数据密钥。缺对象、拒绝或超时不证明此前PUT未提交。
- 相同已存在对象经完整读取验证后不再次PUT；不同字节不覆盖。条件竞争遇到FileAlreadyExists也必须读回获胜对象，不能只信HTTP状态。
- 回执只说明当前对象字节与AEAD已核验，versionRegistered、backupRunCompleted、retentionVerified和deviceExecutionAuthorized均false；它不是数据库版本登记、真实恢复、设备停止或完成运行的授权凭据。

存储授权分STORE与VERIFY_EXISTING，两者都在返回前复查。加密器自身还要求当前解密核验授权。未来服务端必须从可信会话取得Access，不能把请求体角色/权限版本当证明。低层OSS视图是内部传输接口，按调用约定必须由受控协调器使用，没有对外API。直接调用低层视图不会自行完成AEAD或业务鉴权，不能将其直接连接到用户输入。

## 验证

11项协调器单元覆盖完整回读、未知写入只读核查、不盲目重试、错误不泄漏、假信封阻断、坏摘要、存储未就绪、前后撤权、独立只读授权、范围和依赖边界。

另外以真实OSS Java SDK连接同进程127.0.0.1临时HTTP协议模拟服务，8项检查通过：

1. 私有条件PUT及完整认证回读；仅存编码密文，无原文配置。
2. 相同信封重复提交没有第二次PUT。
3. 同版本不同密文被拒绝，原字节不变。
4. 服务保存后丢弃PUT响应，SDK仅发一次PUT，随后只读核查找到原对象，不重传。
5. 公开对象在读取内容前拒绝。
6. 已启用版本控制的桶在密钥unwrap及PUT前拒绝。
7. 模拟桶前提在PUT后变化，不返回成功回执。
8. 被篡改对象不会接受、修复或覆盖。

这是实际SDK/HTTP路径与合成协议服务的检查，不是云端OSS、IAM、持久磁盘、KMS或生产容灾验收。服务和客户端已关闭，模拟对象仅在内存中并已清除，没有生产凭据。首次专项编译错误是测试误用OSS接口不存在的setRegion；改用官方builder.region，第一次没有启动服务，第二次完成全部检查。

普通Maven默认跳过这个HTTP环境测试；显式MOP_RUN_BACKUP_ARCHIVE_HTTP_TESTS=true且只选BackupArchiveLoopbackHttpTest才启动临时服务。完整Maven verify共3994项，2977执行通过、1017跳过，专项HTTP通过另记，不重复计为默认执行。Python125项、Node契约173项通过，独立加密向量仍一致。

## 仍需开发与配置

新增archive授权器及不可变桶治理两个null引用，backup_execution_enabled/production_approved仍false；现有test-only OSS设置与前缀未放宽。没有Spring自动装配、后台派发、真实端点/凭据绑定或生产归档。

数据库版本元数据、原信封的耐久任务/幂等身份、Worker、KMS绑定、规范化索引、保留和恢复访问控制仍待开发或接入。用户不能仅填示例配置就宣称链路启用。V1–V47未改，没有新增业务API、DDL、浏览器或正式TC通过项。
