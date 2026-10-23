# 33列可信来源导入资产草稿

## 归属与边界

众安在线 MOP 资产导入线。检出 `E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\assets`，规范 origin `https://github.com/yangyour/zhonganzaixian.git`，组织 `yangyour/zhonganzaixian`；功能分支 `codex/launch-assets-20261023`，初始上游 `origin/main`、基线 `05ec77ba`。依据本检出 AGENTS、六会话协调文档和两份 launch-work 清单。原主工作区及前端视觉文件未修改。

本功能新增真实服务端写入，不把来源预检当作导入成功。已持久化的33列解析产物可生成资产草稿及基本资料/维保历史，完整扩展原值仍保存在不可变来源；不是正式 CMDB 全属性投影或监控纳管。没有新增迁移，没有改写 V1–V50，没有启用外部通知、采集或生产部署。

## API 与事务

`POST /api/v1/assets/import-sources/{id}/cmdb-imports`，JSON 最多1024字节，只接受：

```json
{"expected_version":4,"source_parse_hash":"当前来源的64位小写SHA256解析摘要"}
```

必须具有有效会话、当前租户 `assets:write` 和内部 `padmin/msp` 角色，并提供 `X-CSRF-TOKEN`、`Idempotency-Key`。客户角色维护范围尚未确认，沿用现有手工资料内部角色限制。来源只能由创建者使用；跨租户、非创建者或无权关联引用统一404。拒绝查询参数、客户端租户/身份/规则/单元格和重复JSON字段。

在既有 `idempotentAuthorized` 事务内：

1. 重查成员、权限版本和来源创建者，锁定来源；重放回执也先重查授权。
2. 核对当前版本、解析操作/产物/原始摘要/模板身份，并重新计算解析绑定摘要。
3. 锁定三项运行配置的发布互斥键，读取当前有效且定义摘要相符的发布；过期、禁用、未配置或定义变化均阻止导入。
4. 从服务端原始产物重新校验33列表头、分类、必填、文本/日期/地址和映射。每批1–500条，备注仍必填。
5. 在租户资产准入互斥锁内计算当前资产总数，检查本批是否超出明确配额；重查站点、活动负责人、同租户名称及配置冲突域内的规范地址。手工新增同样取得该准入锁，避免本次检查与其插入交错。
6. 写入每条资产、基本资料与维保不可变历史；同事务写成功审计和幂等回执。任何失败全部回滚，包含已写入的前序行、审计和回执。

200返回 `status=IMPORTED_DRAFTS`、逐行 `asset_id/asset_version`、`source_id/source_version/artifact_id/parse_hash`、三个配置发布ID/版本和配额检查前后数量。同键返回原回执；来源行锁与不可变成功审计共同阻止同源换键重复导入。资产保留 `DRAFT/unknown/UNKNOWN/PENDING_ONBOARDING`、采样null。`monitoring_started=false`、`production_approved=false`。

错误：400请求结构；401会话/权限版本；403 CSRF/角色/能力；404来源/引用不可见；409版本、重放、已导入来源、名称/地址冲突或配额不足；422规则/映射/来源校验；503可信产物或数据库不可用。错误定位仅含来源记录/行号，不回显原单元格。完整契约在 `contracts/local-api.openapi.json`。

## 配置与字段处置

沿用配置提案→另一管理员审核→当前租户运行发布的既有流程；内部审核不替代客户确认。没有写入任何真实租户配置值。三项均无业务默认值：

|配置|消费者约束|
|---|---|
|`asset.import_steps`|下述明确对象；发布验证与消费校验相同。|
|`asset.ip_scope`|`TENANT` 或 `SITE`；不支持未经授权的跨租户全局探测。|
|`tenant.contract_device_quota`|非负安全整数；0拒绝新增，按当前租户所有已登记资产计数；不是监控项/NVPS预留。|

`asset.import_steps` 必须恰有这些字段：

- `schema_version`: 1（消费者结构版本）。
- `type_mapping`: 客户子类型code→现有资产类型；类型仅允许 `router/switch/firewall/ap/wireless_controller/other`，不预置服务器等类型归并。
- `site_mapping`: 来源站点原文→站点ID；`default_site_id`: 明确ID或null，仅用于来源为空；写时核对同租户数据库引用。
- `owner_mapping`: 来源负责人原文→规范用户UUID；非空来源无映射则拒绝，写时核对活动成员和用户。
- `address_policy`: 必须明确 `domain_mode/allow_single_label/root_dot_mode/allow_bracketed_ipv6/leading_zero_mode/mapped_ipv4_mode/whitespace_mode/numeric_domain_mode`，枚举沿用 `HostFormatValidator.Policy`。不做DNS查询/设备访问；当前资产地址列上限45字符，较长域名拒绝且不截断。
- `date_policy`: `ISO_DATE_ONLY`；只接受文本ISO日期，不推断Excel数值日期。
- `business_description_mode`: `SOURCE_TEXT`，所属业务作为来源说明，不伪造已核验业务系统关联。
- `preserve_source_columns`: 零基扩展列位置数组，只允许15、19–32。非空扩展列未明确列入就拒绝；保留不等于已映射入CMDB关联表。

前15列投影到现有资产/基本资料，子类型16参与明确类型映射，维保起始17和供应商18进入维保历史。资产编号、机柜/机房、协议、BMC、AP归属AC、宿主设备等扩展列本批只明确保留来源，不冒充可信依赖边、远程凭据或纳管配置。来源/产物不修改，回执绑定精确产物ID以支持追溯。

## 验证与未验范围

运行：`mvn -pl server/api -am "-Dtest=*Asset*,*Cmdb*,RuntimeConfiguration*" "-Dsurefire.failIfNoSpecifiedTests=false" test`。此命令不启用历史远程测试身份。新增真实PG入口为 `tools/verify-cmdb-import-rollback.py`，需要明确原主目录私有资料路径与固定协调目录；它排他获取 `environment.lock`，核验已有组件归属和SSH主机指纹，经临时回环隧道连接指定数据库，不启动/停止组件或执行迁移。

真实PG测试的每个案例创建全新UUID隔离租户，始终处于外层未提交事务；业务命令以实际 `mop_test_runtime` 权限执行，内层保存点验证真实SQL与回滚，结束回滚外层并查询租户计数为0。受控异常在第二条资产资料写入时触发实际SQL失败，检查资产、资料、审计、幂等回执均未残留。来源种子是明确标记的合成解析产物；不冒充源文件上传、对象存储或独立解析Worker联调。

没有在真实业务租户写合成数据，也没有恢复任何旧测试身份。测试里的提案/审核/发布仅是回滚范围内的隔离数据，不是客户批准。

当前不据此宣称并发多连接、真实提交后崩溃恢复、500行≤60秒、浏览器操作或正式合同TC通过。通用租户开通、跨入口原子配额预留、监控项/NVPS计量、保留审批及删除执行仍是后续功能；本批只验证导入时设备数量检查。生产隔离/首批真实映射与配额值仍待确认。

首轮真实PG的4个场景通过，重放场景因测试把持久回读JsonNode强转Map而失败；第二轮仅剩JSON内存IntNode/LongNode比较差异。均为测试适配修正，第三轮结果及本地回归以 `docs/parallel/assets-progress.md` 和对应证据记录为准，不删除失败历史。
