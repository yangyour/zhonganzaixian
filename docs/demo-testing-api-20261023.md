# 演示渠道与页面显式样例 API 0.84.0

2026-10-23逐项补齐复核：原已提交OpenAPI有本页契约，但生成器仍停在0.83且缺演示追加步骤，重新生成会删除本页接口/会话字段和渠道隔离声明。已补`tools/demo-api-contract.mjs`并接入主生成器，生成结果与审阅后的0.84契约结构及字节一致，没有变更运行时API、演示名单或出站权限；根契约测试新增生成一致性门禁，避免再次只改生成物而遗漏源定义。

适用于用户2026-10-23明确授权的独立演示账号。指定测试服务器仍复用PG17.9/V50，不新增迁移。默认未配置的真实租户不启用这些行为。

`MOP_DEMO_CHANNEL_TENANT_IDS` 是服务端明确UUID列表，最多20个、不允许重复、拒绝真实租户及旧Fixture租户。当前只登记本次3个全新演示租户。租户名称、用户名和前端标记均不授予权限。会话仅在此名单内增加可选 `demo_examples_enabled:true`；实际端点重新检查当前成员、页面能力及读取后的授权，不信任浏览器声明。

## 既有渠道接口的停用演示配置

当前测试站已加载邮件/企微控制器；对演示范围，它们返回独立的演示渠道标识，`enabled/secret_configured/dispatch_enabled=false`，`last_check.ready=false`，来源 `DEMO_DISABLED_CONFIGURATION`。读取和写入仍使用现有PostgreSQL仓储、租户过滤、CSRF、幂等、版本及审计。绑定标识使用单独的example.invalid发件地址或wwDEMO企业标识，不读取实际渠道绑定。

仅允许 `active:false` 的example.invalid邮箱或DEMO_企微成员绑定。启用绑定、连接检查、固定测试消息及站内消息外发均在供应商调用或入队前返回 `409 DEMO_CHANNEL_DISABLED`。其他未配置租户仍404，真实渠道原有行为保持。演示投递记录不填造ACCEPTED/DELIVERED；停用绑定本身就是此页的测试数据。

## GET /api/v1/demo/examples/{page}

支持 `sla-scenarios`、`sla-reports`、`operational-comparison`、`capacity-estimates`、`observation-window-diagnostics`、`threshold-diagnostics`、`contact-import-preview`。按页面检查已有业务能力，无新超级角色。响应包含当前tenant_id/actor_id、page、available、input及固定 `source:EXPLICIT_DEMO_INPUT`、`business_data_written:false`、`provider_accessed:false`。实际租户返回available=false/input=null；未知页面或租户覆盖参数404，未登录401、无页面权限403。

前端只在服务端会话明确允许时请求。结果必须匹配当前账号/租户/页面，途中切换、撤权、销毁或用户已编辑则不应用。已加载样例明确标DEMO，使用原有纯计算/格式预览接口展示结果，不伪造成功。用户更改样例后恢复原离开保护；月报未决写命令的离开/卸载保护保持，默认示例不解除该保护。

样例只是可重现的假设输入；报告生产规则、健康指标、SLA权威口径、真实通知送达和备份执行仍遵循原门禁。

## 验证

渠道专项34项通过，后续API及依赖完整package合计4274项报告、实际执行3233、条件跳过1041，失败0；条件跳过不算真实组件联调。新增演示端点包含实际租户无样例、能力检查、授权变化拒绝和禁止伪造写入/供应商事实的测试。前端全量最终300文件4960项通过，新增6项涵盖普通会话不请求、响应跨租户、切换取消、加载中编辑和计算中编辑保护。首轮并行负载下旧巡检摘要测试超时及后继spy影响保留，单独27项及降低并发的全量复跑均通过。接口契约199项通过；类型/生产构建和静态发布策略通过。真实部署与最终页面结果在账号交付记录追加。
