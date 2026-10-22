# 应用内监控接入计划 API

契约0.80.0。与现有监控接口一样，仅在remote_test profile装配，其他profile不暴露这些路由。本切片把预设目录和批量计划生成接到现有会话/资产授权边界，减少手动填写租户、地址和完整目标JSON。没有前端修改、网络探测、资产创建、配置发布、业务写入或SQL迁移。

## 读取目录

GET /api/v1/monitoring-onboarding/presets

要求assets:read和configurations:read，响应前复核当前成员。返回13种共享预设、profile_schema、是否需要/已配置服务器采集器及device_setup_verified=false。

- profile_schema为可供表单使用的JSON Schema，复用技术端口、范围、协议和必填项；未配置采集器时不假装有可用选项。
- 只出现当前租户的采集器名字，目录不暴露采集器地址或认证引用。
- server_collector_configured仅表示服务器有对应配置，不表示设备、Exporter、凭据或连通性通过测试。
- catalog_sha256是共享预设文件的字节摘要，不是运行配置版本、动态采集器授权版本或权限版本；不应据此缓存授权。

## 生成计划

POST /api/v1/monitoring-onboarding/plans

要求padmin及assets:read、configurations:read、configurations:write，并要求现有CSRF头。示例请求：

```json
{
  "version": 1,
  "assets": [
    {
      "asset_id": "79000000-0000-4000-8000-000000000010",
      "profiles": ["linux-node", {"preset_id": "http-service", "key": "health", "scheme": "https", "path": "/health", "collector": "blackbox"}]
    }
  ],
  "defaults": {"interval_seconds": null, "timeout_seconds": 5, "exporter_scheme": "http"}
}
```

示例UUID只是合成占位，不是实际资产。客户端只能提交资产和服务选择，不能提交tenant_id、地址、完整endpoint、采集器定义、auth_profile、密码或enabled开关。地址取自当前授权资产；SNMP认证引用只取当前租户服务器采集器配置。

约束：请求体最多64KiB，资产最多64项，每项最多16个profile，总profile最多64；拒绝重复资产ID、重复JSON键、尾随内容、未知字段、未知查询参数和类型错误。类型正确但尚不完整/不适用的服务参数返回定位诊断，不泄露输入值。

成功响应中的plan.valid=true表示可生成计划：

- configuration兼容既有v1目标配置，全部enabled=false；缺失周期保留null。
- 目标ID与离线工具使用相同UUIDv5逻辑键，15目标/13预设黄金样例的配置及SHA256逐项一致。
- 生成期间和返回前核对当前成员与资产快照；asset_scope_checked表示本次资产授权读取已复核，不是物理设备身份或未来持续授权保证。
- applied、network_accessed、continuous_collection_verified、device_identity_verified始终为false。
- 没有写入或幂等回执；重复准备不创建额外业务记录，也不自动登记目标。

plan.valid=false表示依赖/服务参数还不完整，diagnostics只含安全原因码及可选asset_index/profile_index；configuration与摘要为null，target_count=0，不返回部分可应用配置。全局默认参数不合法、尚未读取资产时，asset_scope_checked=false。

## 错误语义

- 400：结构、类型、大小、覆盖字段或查询参数非法。
- 401：当前成员/会话失效或授权版本变化。
- 403：缺少操作权限或不是允许准备的角色。
- 404：资产不存在、不可见或仓储返回不匹配的租户/资产身份。
- 409 ASSET_CHANGED：生成过程中资产资料变化，需重新读取并生成计划；不把该情况错误处理成必须重新登录。

响应使用Cache-Control:no-store和Referrer-Policy:no-referrer。准备操作仍执行CSRF检查，不因“无业务写入”绕过现有安全链。

## 服务器采集器配置

MOP_MONITORING_ONBOARDING_COLLECTORS默认是version=1、collectors=[]。参考：

- deploy/examples/monitoring/onboarding-plan.env.sh.example：全空POSIX shell配置示例。
- deploy/examples/monitoring/onboarding-collectors.example.json：仅含文档IP和合成租户UUID，需替换与核验，不可直接用于实际业务租户。

每条包含name、tenant_id、kind、endpoint、auth_profile。kind仅blackbox/snmp；地址必须符合对应固定路径和明确IPv4规则；同租户同名不能重复。Blackbox的auth_profile必须null，SNMP必须为已配置引用名，不存community或密码。客户端无法越权选择其他租户采集器，也不能临时替换认证引用。

这些配置只用于准备，不自动写入登记目标，不授予探测白名单，不创建凭据或启动采集。即使探测总开关保持false，目录和直接Exporter计划仍可使用；缺少Blackbox/SNMP依赖时明确返回COLLECTOR_REQUIRED。

## 前端交接与后续

前端由用户维护，可调用目录生成参数表单，再提交资产选择并下载返回的disabled配置。按钮/状态应表述为“生成接入计划”，不能展示“已接入”“已发布”或“已开始监控”。不要把asset_scope_checked解释为设备身份认证。

计划启用、可信配置发布/回滚、实际Collector状态、MOP历史读取与告警仍需后续接线。真实设备和业务库联调未执行；本批验证使用纯计算、MockMvc现有安全链与模拟仓储，不是实际身份提供方或线上数据库验收。正式128项TC仍NOT_RUN。

证据见 evidence/monitoring-onboarding-plan-api-20261022/validation.json。

## 回归与提交

后端4276项总计，实际执行3234、跳过1042，0失败/错误；本批新增33项，其中4项检查现有HTTP安全链。Node契约192项（新增6项）通过，JAR核验包含新控制器、共享预设资源一致且不含测试类。合入时保留主分支9d3fed4d的7项用户前端/文档更新，没有覆盖或修改前端，也不采用其上游浏览器报告作为本轮证据。初次专项测试发现一个测试用例误改了采集器kind，修复夹具后通过；未放宽生产校验。
