# M02 持久对象映射与来源归属复核

本功能接续资产与停用登记，但不把停用目标、映射一致性计算或一次来源核验冒充持续采集。资产详情增加紧凑的“监控对象映射”区，手动读取、分页、查看、登记草稿、提交当前资产版本、取消草稿、来源复核发布和撤销。沿用当前页面结构，不增加统计占位或主导航。

## 真实调用链

AssetMonitoringMappingPanel → 严格客户端输入/响应检查与 durableCommand → /api/v1/monitoring-object-mappings → 当前会话/租户/能力 → 原幂等事务 → PostgresMonitoringObjectBindingRepository → V55 不可变根/修订日志。ACTIVATE 在同一有界事务中调用绑定来源 ZabbixObjectIdentityReader，复核后再持久化与审计。API/Worker 不自动迁移。

- 根键为精确 pool_id + HOST/ITEM + opaque external_id，禁止名称/IP推断或跨池匹配。当前已接通的元数据适配器为 ZABBIX_7；hostid/itemid 必须规范正整数ID。其他监控协议仍沿用既有目录/探测能力，不宣称已完成其持久对象归属适配。
- draft 与 active 分开。提交/取消草稿不改变已有发布；ACTIVATE 替换发布并清空草稿；REVOKE 清空发布但保留新草稿。取消与撤销不要求来源可连通或旧资产版本仍匹配，不能因配置失效阻止撤销。
- 创建前要求明确允许池与已登记来源类型。发布还要求明确复核名单、自审规则、证明有效期、当前资产版本、当前管理员能力与来源配置摘要。未确认值保持 null，不填客户默认值。
- HOST 只调用 host.get；ITEM 先 item.get 得到 hostid，再 host.get。使用显式配置的三个自身主机标签分别声明资源池、租户、资产UUID；不采用继承标签、名字/IP、调用方声明或已有映射填补缺失标签。
- 完整唯一结果和规范UUID才可生成来源摘要。源元数据与申请租户/资产不匹配返回404；协议异常不返回供应商原文、URL或秘密。元数据声明不等于物理设备身份认证。
- global key 事务锁与数据库触发器防止同一池/对象被同时发布到两个租户。外租户已发布记录即使过期仍占用，须显式撤销才可重新发布，不隐式转移归属。未发布草稿不抢占全局键。
- GET 在当前授权下核对审批配置、来源定义、复核人当前资格、资产版本和证明时效。失效原因与历史仍可见；CURRENT_RECORDED_MAPPING 仅表示读取时记录有效。返回前重查完整成员关系和项目账号修订；不宣称每字节流式撤权。
- 幂等缓存只保存 acknowledgement_only 回执和 accepted_version，不缓存“当前仍生效”的结论。页面确认后重新 GET；未知结果保留原命令与同一幂等键，不自动重试；上下文变化清空/取消读取，晚到响应不能覆盖新上下文。
- 历史资产关联用于列表，撤销或改绑后旧资产仍可找到根记录。页内每个映射独立读取当前状态，不是跨页/全列表一致快照。

## API

- GET /monitoring-object-mappings?asset_id=UUID，20条页；before / before_id 成对游标。
- GET /monitoring-object-mappings/{id}，实际草稿/发布、可用性与当前允许的复核动作。
- POST /monitoring-object-mappings，明确 asset_id、asset_version、pool_id、object_type、external_id、reason。
- POST /monitoring-object-mappings/{id}/actions，SUBMIT_DRAFT / CANCEL_DRAFT / ACTIVATE / REVOKE，各动作严格字段集合及 expected_version。ACTIVATE 仅收 expected_policy_reference 与 expected_source_definition_sha256，不收 proof、verdict、observed_at、租户或凭据覆盖。
- 写操作保留 CSRF、幂等、乐观锁、当前授权和同事务审计。monitoring_applied、dispatch_allowed、device_identity_verified 均 false。

实际OpenAPI由最终生成器添加，保留主干及本轮其他接口；离线DEMO目录仅同步契约指纹，不初始化账号或生成业务数据。

## 配置、迁移与依赖

空白配置示例：config/monitoring-object-mapping.env.example。功能默认关闭，两个私有JSON分别按租户/池配置。endpoint 只接受已有SDK支持的绑定环回地址；token_env 是秘密变量名称，不是秘密。三个标签键必须明确且不同。reference 应在来源身份或凭据权限改变时更新，摘要不包含秘密值。来源读最多两次现有10秒有界RPC，不配置或扩大任何实际账号访问权限。

V55预约 f9d8c59c 已在远端main；本地候选 NOT_APPLIED / NOT_FROZEN。此前56个远端ref谱系见 m02-mapping-lineage.json。只有新增显式独立迁移命令 --apply-monitoring-object-mapping 能请求55，不运行该命令。本轮零DDL、零指定服务器写入，V1–V54字节保持。源码预约不是跨主机锁，任何真实应用前还必须核对共享环境协调、完整谱系、受限权限与隔离命名空间。

只读指定隔离PG检查默认跳过，不创建数据库、不造数据。指定PG/OIDC/Zabbix与真实浏览器验证尚缺受控连接/主机信任，NOT_RUN；代码/模拟协议/MVC/jsdom/语法解析均不替代真实联调。

## 验证与尚未完成

本轮证据：docs/evidence/dot-continuation-20261023/m02-object-mapping.json。一次测试预期错误已记录：切换至msp会先失去configurations:read，实际403符合能力边界，并非本应返回成功；测试调整为明确断言此权限拒绝，原撤权401与租户边界测试保持。SQL候选增加显式非null版本/时间/原因检查，避免CHECK的NULL语义放过缺字段。

本功能没有接通 ZabbixEventPuller → raw/quarantine → 持久映射消费 → 正式工单/通知整条链。采集器/规则发布、首值独立核验、234项参数/OID消费、更多来源与正式验收仍待后续，不将映射API完成写成M02全模块完成。

Zabbix 7.0官方协议依据：[host.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/host/get)、[host object](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/host/object)、[item.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/item/get)。自身tags与继承tags使用不同选择项，本实现明确只取自身tag/value。
