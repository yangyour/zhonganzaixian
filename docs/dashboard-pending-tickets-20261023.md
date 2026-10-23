# 首页待办工单事实聚合

2026-10-23，报告与经营后端会话。新接口从持久化工单当前状态读取数量与最近五条，支持同租户 V33 workflow 与 V42 lifecycle 两种已有谱系，保留原首页可用性接口和现有页面实现。

## 实际 API

`GET /api/v1/dashboard/pending-tickets`，不接受查询参数。当前会话必须同时具备 `dashboard:read`、`tickets:read`，在数据库只读 REPEATABLE READ 事务中再次检查成员、有效权限和权限版本。MSP/padmin 只读当前租户；viewer/tadmin 继续只看本人作为报障人的工单。客户端不能覆盖租户、角色、排序、状态或数量。

字段说明：

| 字段 | 含义 |
| --- | --- |
| `registrations` | 当前授权范围内已持久登记的工单总量，不表示全部都已进入正式流程 |
| `known_pending` | 明确拥有唯一流程且当前不处于终态的工单数 |
| `terminal` | V33 的 CLOSED/CANCELLED，或 V42 的 CLOSED 数量 |
| `unconfigured` | 已登记但两种流程都没有当前修订的数量 |
| `pending_tickets` | 仅当 unconfigured=0 时为完整非终态数量，否则 null |
| `state` | AVAILABLE 或 PARTIAL；PARTIAL 的原因固定为 REGISTRATIONS_WITHOUT_WORKFLOW |
| `items` | 已知非终态工单按登记时间降序、ID 降序的前五条；不从某一个状态分页后截取 |
| `workflow_model` / `state` / `version` | 每条记录所属的明确持久化谱系及当前修订，不替换成客户端状态 |
| `scope` | CURRENT_TENANT_INTERNAL 或 REPORTER_ONLY |
| `metric_version` | MOP_VISIBLE_NONTERMINAL_TICKETS_V1 |
| `observed_at` | 同一事务结束读取时的数据库时刻；不冒充时钟精度验真 |
| `overdue_sla` | null；不使用客户端时钟自行判定超时 |

本接口对“待办”的固定事实定义为**尚未关闭/取消**，包含等待客户确认的 RESOLVED；V42 的 WAITING_VENDOR、PENDING_CUSTOMER、REOPENED 等同样不能漏掉。它不是“设备仍故障”的指标，也不代替正式 SLA 口径。空库确认范围内返回 0；缺流程的登记返回未知，不计成 OPEN 或 CLOSED。

计数、授权、前五条属于同一数据库快照。两套流程同时存在、未知状态、行数/前五条不一致或数据库失败返回 503，不能以空数组/0 冒充成功。GET 无审计/幂等写入，响应沿用 no-store 安全头，没有新增 POST。

## 运行与验证

无需迁移或新组件，复用指定 LAN PostgreSQL 17.9 / V50。服务装配位于独立 reader/controller，不改变前端视觉或已有 `/dashboard` 全 null 可用性契约。业务部署尚未执行。

```powershell
mvn -pl server/api -am '-Dtest=Dashboard*Test,InspectionQuarterlyReport*Test,QuarterlyReportPdfControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
npm test
node tools/build-api-contract.mjs --check
py -3.11 tools/verify-report-capture-isolated.py --suite dashboard --output .local/dashboard-pg-new-run
```

最后一个命令复用报告隔离验证器，持有 environment.lock，核验 SSH 主机键/项目归属后新建两个明确测试租户、一名内部主体及两名无登录凭据的观察者。测试只向新租户经现有报障与工作流仓储写合成事实；无采集、备份、派发或客户租户写入。测试结束停用三个主体及所有相关成员，保留不可变测试记录。

真实 PG 四项检查通过：混合 OPEN/IN_PROGRESS/RESOLVED/CANCELLED/未配置数据计数及前五条；本人报障隔离；另一租户和过期权限拒绝；在汇总后并发提交新登记，当前列表仍保持原事务快照、下一次读取可见新登记。查询前后审计/收据不增加；实际业务租户十张相关表完整行摘要和 V50 历史不变。V42 全状态分类有纯单元覆盖，本次 PG 没有构造 V42 业务流转数据，因此不宣称其完整流转联调通过。

新代码的 9 项纯领域/控制器检查、1 项 MVC 安全检查及首页/报告/PDF 回归通过；最终聚焦 Java 共 128 项（无跳过）、契约 197 项。MVC 是隔离仓储测试，真实 PG 是仓储级验证；未执行新端点 TCP HTTP、浏览器、正式合同 TC 或客户签收。证据见 [测试汇总](evidence/dashboard-pending-20261023/validation.json)和 [PG](evidence/dashboard-pending-20261023/postgresql.json)。
