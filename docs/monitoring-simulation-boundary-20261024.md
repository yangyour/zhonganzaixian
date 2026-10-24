# 监控建单失败模拟与隔离测试边界

调用链审查发现：/events/ingest 原先接受 simulate_autoticket_failure=true，并直接影响持久自动建单尝试；并非仅供纯计算测试使用。旧PG集成测试还硬编码了后来成为实际客户的租户，单靠测试开关不足以防止误写。本次只收紧模拟路径，不替代来源认证或完整采集链。

## 修正

- HTTP事件入口对true返回422 MONITORING_SIMULATION_FORBIDDEN，在幂等执行或业务仓储之前拒绝；任意租户、管理员或旧重放都不能通过公共接口制造故障结果。false正常事件、必填字段形状、CSRF/幂等与版本/授权行为不变。
- 仓储内部在任何事件/日志/尝试写入前额外核对模拟路径：仅真实连接的 current_database 为 mop_monitoring_test_ 加12位小写hex、current_user与session_user均为mop_test_runtime，且目标租户规范、明确且不属于两处受保护客户范围时才允许内部隔离测试。活动mop_test、空租户、混用管理员身份都拒绝；没有环境开关能豁免这些条件。
- 纯域计算的失败/重试测试保持。隔离PG内部可验证故障计划，但HTTP永不接受人为失败；该例外不是生产接口能力。
- 旧PostgresMonitoringEventCenterIntegrationTest及复用它的CAS测试不再回退固定历史租户。必须提供MOP_MONITORING_TEST_TENANT_ID，并在建立连接前校验SPRING_DATASOURCE_URL为显式环回隔离库URL；不接受活动库、非环回、带查询参数/秘密的URL和非法端口。测试本身不创建命名空间、身份或授权，依赖指定服务器已协调的测试资产/成员。
- 现有DEMO初始化脚本使用false，代码路径保持，本轮未重跑初始化。没有修改实际租户、数据、账号、DDL或部署。

## 契约与证据

公开字段仍为必填boolean，true的业务拒绝码为既有422错误结构。103项HTTP契约样例中“occurred_at=null”成功场景改用false，并同步其精确跨语言请求golden；另增独立拒绝测试验证true不进入业务仓储，未将true假装成功。既有实际错误/重试计算和正常写入测试保持。

证据见docs/evidence/dot-continuation-20261023/m02-simulation-boundary.json。定向测试使用纯Mock/MVC/JDBC协议替身；真实隔离PG/生产身份与浏览器未执行，历史PG测试仍按条件跳过。该改动不宣称真实自动重试、设备采集或合同验收完成。

后续仍需完成真实来源证明、持久对象映射消费与raw/puller到事件链路；不因阻止模拟参数而认为所有事件输入均已可信。
