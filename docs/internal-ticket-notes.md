# D07内部工单备注（独立切片）

对应M5-D05评论/时间线的内部文字部分。当前仅输入核心和仓储接口；没有真实备注持久化、公开API或页面，不能当作评论功能完成。

## 输入和范围

- 原始文字1..2000 Unicode code points，拒绝全空白/BOM-only、无效代理字符与非文本ISO控制符；允许LF/CR/TAB
- 不trim、归一、变换换行或合成内容；SHA256绑定精确UTF-8字节，后续用于持久值一致性检查而非外部真实性证明
- 规划仅msp/padmin同租户内部读取/追加，不向客户DTO返回内部作者身份或备注；客户可见评论、附件和通知分别待Q09/OSS等真实实现
- 备注为人工填报，不是已接单/转派/解决/客户确认或SLA事实；正式状态仍受Q07/Q09/Q27门禁
- 仓储契约要求新鲜成员/权限版本、同租户父登记、受保护幂等事务、父版本锁定检查及审计；记录仅追加，无编辑/删除接口
- observed_ticket_version保留提交时父版本；独立追加不会悄悄改变工单业务状态、SLA或父记录版本。并发不同备注可分别保留，重复原请求必须幂等

## 当前验证

5项纯核心检查：原文/多行、Unicode长度、未知/空白、控制符/代理字符、精确UTF-8指纹与不归一。根570=557执行（含136既有真实PG回归）+13fixture跳过，6MinIO排除，0失败错误。既有PG回归不代表备注持久化通过；API V0.14不变，V1–V9及私有配置不改。

下一步新增独立V10及真实PG仓储/授权/事务验证，执行后冻结迁移；API/Worker不自动迁移。参见[核心证据](evidence/niko-internal-note-core-20261002.json)。完整M5-D05/D07未DONE。


## 2026-10-02 V10独立结构已执行并冻结

V10新增mop_ticket_internal_note，tenant/ticket/actor均有同租户FK，记录仅追加。精确UTF8 note_sha256由SQL CHECK重算核验，1..2000码点/空白BOM/控制字符、正父版本、时间范围及INTERNAL/MANUAL_INTERNAL_NOTE/SELF_REPORTED/状态不变标签共11个CHECK。运行身份仅SELECT/INSERT，无UPDATE/DELETE/TRUNCATE/引用/触发器或PUBLIC访问；没有增加父工单权限。实际授权/权限版本和父版本新鲜性仍必须由后续仓储事务核验，schema不能代替角色检查。

显式--apply-internal-notes要求V1–V9已验证；默认--check只读，API/Worker不迁移。niko真实PG check0→apply1→重复0→check0；旧--apply-asset-metadata也0，结构/ACL全部核验。V10 SHA256 ba1ad02e7df8079936288d8712a84062a5bd695885b1e45daacec9f4d35ad198，自此V1–V10冻结，未来结构修改新增V11等独立迁移。

6项真实PG schema/ACL回归均事务回滚：原文及hash/标签、父行摘要不变、跨租户FK、hash/父版本、空白控制符2000/2001emoji、不可变权限与禁止customer/状态改变标记。11项迁移策略和31项Python守卫通过；根577=564执行（含142真实PG）+13fixture跳过，6MinIO排除，0失败错误。并非备注业务仓储、HTTP或页面通过，后续继续，见[V10证据](evidence/niko-internal-note-schema-20261002.json)。


## 2026-10-02 授权不可变备注仓储

PostgresInternalTicketNoteRepository每次读写重新核验当前启用用户/成员/权限版本，仅msp/padmin；当前租户父登记与备注组合查询，错父/跨租户404。读为REPEATABLE_READ只读快照，创建时间/id有界分页；核验持久hash/INTERNAL标签与observed_ticket_version不大于当前父版本，异常503。写必须在既有受保护幂等事务：成员共享锁、父登记共享锁与expected_ticket_version核对，追加不可变备注/审计/重放同事务；不修改父登记或SLA。

同原键并发两独立连接仅一条备注/审计；不同原请求是不同备注，可同时保留，不能把追加行为冒充工单版本推进。客户viewer/tadmin读/写均403；客户原ticket DTO保持原样，不含备注/作者信息。失败下游整笔回滚，原键可安全重试；成功读取不写审计。断库/未受保护事务失败关闭，无fixture回退。

新增12真实PG通过（首次全量通过）：原文/hash独立回读、父与客户DTO不变、内部双角色/客户隔离、旧键原收据、版本冲突、跨租户/错父、权限版本、回滚、原键并发、独立备注并发、分页及2000emoji。根589=576执行（含154真实PG）+13fixture跳过，6MinIO排除。公开API/页面仍待接线，原V10不改；见[仓储证据](evidence/niko-internal-note-repository-20261002.json)。纯核心46cf5ed的CI37057313765 success。
