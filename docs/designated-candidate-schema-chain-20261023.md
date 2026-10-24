# 指定PG隔离迁移链与首次冻结

在已授权复用的LAN PostgreSQL17.9实例上，按模块创建四个带唯一归属marker的项目验证数据库。活动`mop_test`没有迁移、修改权限或复制客户业务数据；现有服务、全局角色、端口和生命周期保持。开始前取得本机`migration.lock`，fetch最新远端并核对63个引用，V51只有原冻结摘要；活动及匹配候选库的只读预检仅发现V50活动库。远端批次已收尾，实际执行仍不把本机锁当跨主机锁。

## 实际执行结果

| 阶段 | 本阶段执行 | 首次／重复 | 验证库（已保留） |
| --- | --- | --- | --- |
| N01 | 原冻结V51 | 1／0 | `mop_notification_test_59ca183a056d` |
| P02 | V52 | 1／0 | `mop_attachment_test_de85f810020d` |
| R02 | 修正后V53 | 1／0 | `mop_report_test_449b59f37319` |
| M02 | 修正后V54、V55，逐版执行 | 各1／0 | `mop_monitoring_test_554a57478666` |

每个后续库只继承前一自有库的schema、精确Flyway history和能力字典，再执行本阶段版本。克隆来的history表示来源谱系，不宣称前序迁移在新库重新执行。各库的名称、owner、COMMENT marker、运行/current/session身份均核对；不创建或提权角色，不绕过正常RuntimeDataSources/RuntimeDatabaseGuard。测试入口只接受特定随机库名及显式开关，拒绝活动`mop_test`。

每阶段真实SchemaVerifier、资源pin/历史checksum、非空root的FOR SHARE/FOR UPDATE、首次revision以及非法UPDATE后的savepoint回滚通过。V53–V55仅`id`列有锁定所需权限，表级及其他列UPDATE仍无权限，实际`UPDATE id=id`由不变的触发器拒绝。首次审阅发现的行锁权限问题在未应用前修正；旧候选和新摘要映射保留于迁移登记，V1–V51未改写。

**V52–V55现在已执行并冻结。** 成功DDL之后，即使后续业务测试失败、验证库未部署或将来清理，也不得再改写这些资源。新发现的问题使用新迁移。原始Flyway安装时间不附加猜测时区，UTC执行窗口和原始值分别保留于证据。[冻结清单](evidence/local-astra-ut-20261023/schema-chain/first-application-freeze.json)、[完整安全摘要](evidence/local-astra-ut-20261023/schema-chain/summary.json)。

## 保留与边界

四库均为`RETAINED_BY_POLICY`，按用户“不自动删除”要求保留，自有连接已关闭、环境锁已释放。工具默认不DROP，不强制终止任何PG连接。未来如用户明确要求清理，仍须精确归属/零连接及已持久化可判定迁移history；任何未知或写盘失败都保留数据库，不能丢失首次应用证据。

活动库V50历史、能力字典和指定实际租户保护表指纹前后相同。工具未触发通知、设备操作、扫描、报告发送或部署，业务验证固定`NOT_RUN`。插入的SYNTHETIC最小结构夹具只证明表约束、行锁和首revision，不等同完整仓储/HTTP/扫描/PDF/供应商业务链完成。

执行命令：

```text
py -3 tools/verify-candidate-schema-chain.py --output .local/candidate-schema-chain-attempt-1 --lock-owner review-v53-v55-25a17013 --lock-session local-astra-ut-20261023 --retain-databases
```

后续不复用此已存在的输出目录，也不自动更改活动库。迁移和完整业务启用仍须按各模块独立核验；临时库连接限制不扩展成正常运行账号可访问任意数据库。
