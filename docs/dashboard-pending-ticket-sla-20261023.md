# R03 / UI01：待办 SLA 增强读取

新增独立 `GET /api/v1/dashboard/pending-tickets/sla`，`projection_version=MOP_PENDING_TICKETS_SLA_V2`。原 `GET /dashboard/pending-tickets` 的严格 V1 数据结构和 `overdue_sla=null` 保持不变。报告线只提供后端与契约，首页页面和客户端由集成会话接线。

## 同一授权快照

会话成员先校验 dashboard:read、tickets:read，数据库再次核验当前权限版本。计数、前五条、冻结 SLA、当前服务档位和监控恢复证据共用一笔只读 REPEATABLE READ 事务及同一 observed_at。MSP/padmin 只读当前租户，客户角色只读本人报障；不接受租户、角色、观察时刻或上限覆盖参数。

逐行 `version` 和 `sla_version` 必须一致。V33 复用 TicketSlaClockEvaluation 及已有监控 t2 读取；V42 复用 SlaClockState 和现有生命周期消费者，不绕过其启用门禁，不改变基础 SLA 规则。旧独立 SLA 接口仍使用其原来的数据库取时位置。

## 字段与未知值

每条记录保留 V1 的工单 ID、标题、登记时间、流程模型、状态和版本，新增：

- `priority`、`frozen_service_tier`、`sla_metric_version`、`sla_version`。
- `overdue`：任一时钟已知 breached=true 即为 true；两者均已知 false 才为 false；其余为 null。
- `response_clock`、`recovery_clock`：status、breached、missing_reason、deadline_at、completed_at、remaining_millis、remaining_basis。

V33 剩余量只累计冻结服务时段，不把周末/节假日算成服务预算。V42 使用既有引擎的 budget−elapsed；只有日期目标、没有时长预算时 remaining_millis 为 null。已耗尽预算返回0，是否违约仍由既有服务端 breached 判定；前端不能通过本机时间自行改判。

缺配置、未确认重开规则、V42未启用、历史超出预算、V42监控t2消费尚未连接均保留具体原因及 null，不冒充达标。V42监控关联时不会把工程师解决时间当作已经完成的监控恢复。

汇总字段包含 `known_overdue`、`unknown_sla`、`evaluated_pending`、`unevaluated_pending`、`evaluation_limit=100`。`unknown_sla` 包含未进入流程的登记、已评估但无法判断的工单，以及超出技术预算的未评估项。只有未知数为0才返回完整 `overdue_sla`；否则为 null，并列出缺流程/缺SLA输入或读取器/超出预算的原因。100张是单次同步计算的技术保护，前五条始终在已评估范围，不把前五条或前100条数量冒充全量。

## 本地候选 API

候选仅绑定 `http://127.0.0.1:18215`，临时SSH转发连接指定服务器的既有 PostgreSQL 17.9 / V50。原测试站未部署此次后端。命令不迁移、不初始化DEMO、不启动基础组件或Worker，不启用通知/采集/备份；V42消费者按默认关闭，读取到该谱系时保持明确未知。

```powershell
mvn -pl server/api -am '-DskipTests' package
py -3.11 tools/verify-dashboard-sla-candidate.py --output .local/dashboard-sla-new-check
```

默认自动验证既有 DEMO 的 padmin、msp、tadmin 三个上下文及匿名拒绝，然后停止候选 API/转发并释放 environment.lock。API写入仅正常登录和选择既有租户，业务检查均为GET。连接资料和账号只读原主目录 Git 忽略的 deploy/private，摘要不含凭据。

集成会话需要在候选上继续验证新客户端时，可加 `--hold`；成功后保持同一18215端口，直到 Ctrl+C，期间持有 environment.lock。前端使用 `MOP_API_PROXY_TARGET=http://127.0.0.1:18215`，不要退回正式测试站旧接口。使用前检查锁与端口，不停止他人进程。该方式是本地候选验证，不是部署。

## 实际验证与边界

- 69项聚焦Java单元/MVC/边界回归通过，无跳过；包含读取权限、no-store、禁止POST/范围覆盖、剩余工作时段、未知逻辑、V42门禁和精确版本约束。
- 4项指定PG真实隔离测试通过：混合已超时/运行/缺配置状态、本人/租户隔离、V1兼容；读取中并发提交接单后，前五条与SLA仍保持原版本，下一次读取同时更新。测试主体及观察者结束后停用，实际业务租户十张相关表指纹及V50历史不变。
- 本地候选API真实HTTP验证三个既有DEMO角色，并与同工单的既有 workflow/sla 接口比较优先级、版本和违约结果；匿名读取401。各角色都存在已知超时及未知项，完整超时总计正确保持null。最终候选JAR与结果见[HTTP证据](evidence/dashboard-sla-20261023/http.json)，候选已停止。
- 201项契约检查与生成一致性通过；保留主干 `tools/demo-api-contract.mjs` 和0.84定义。最初重新生成暴露旧生成器遗漏DEMO定义，已随主干修正吸收，没有删除DEMO边界。

V42真实完整生命周期/监控t2联调、100张以上全量异步汇总及首页V2接线仍需后续验证/实现；本次不把这些范围标完成。R03经营成本/FTE、统一八指标、时间维度/导出等仍在清单中，正式TC和客户确认不变。
