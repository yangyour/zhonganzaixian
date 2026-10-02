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
