# P01 诊断证据矩阵与实际回读

复用最新远端已有生产诊断API、返回前授权和面板scope清理，只修客户端/契约仍可接受矛盾检查结果的缺口。接口仍为只读`GET /operations/production-readiness`，不改变响应字段、不接收连接参数、不启用生产。

结果必须属于以下完整分支：

- 未观测：恰好5项；`DATABASE_OBSERVATION / NOT_READY / DATABASE_OBSERVATION_UNAVAILABLE`加4项固定检查，数据库时间/版本为null、runtime受限结论为false。
- 已观测：恰好9项；5项数据库检查加4项固定检查，必须有合法UTC时刻和非空版本，`runtime_database_restricted`与5项数据库均PASS等价。

每个检查ID的status/code按服务端实际常量逐项匹配，拒绝额外字段、重复/缺项、混合分支、错误原因码及“无观测却PASS”。安全设置PASS只对应明确有效设置；公共TLS仍NOT_RUN、Q02隔离仍CUSTOMER_GATED、测试运行仍BLOCKED，`production_ready=false`和`activation_performed=false`固定。失败清空旧结论，保留现有同步租户/成员/账号失效清理。

45项前端逻辑/面板回归与2项严格契约测试通过。全量代码回归另见统一证据；指定PG17.9/V50的候选真实HTTP中padmin取得9项真实观测，msp/tadmin均403无数据。实际Edge156、1440桌面点开“生产启用检查”并执行只读检查，显示真实PG版本、9项结果及“生产未就绪”，无页面错误/横向溢出。

候选API/Web及SSH转发已关闭，0迁移、部署、业务写入或外发；没有修改任何角色权限来制造通过。此结果只证明测试运行边界诊断，不代表正式数据库选型、租户生产隔离、公共TLS、SLA或生产批准。证据：[HTTP](evidence/local-astra-ut-20261023/upstream-batch/p01-http.json)、[真实页面](evidence/local-astra-ut-20261023/upstream-batch/p01-live-browser.json)。
