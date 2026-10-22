# 工单 SLA 载荷完整性与状态一致性

基线 main 3aa49fc6cdb17ad672b268055e4817efe87cec84。依据现有服务端SLA_CLOCK_V2实现和[前端验收标准](frontend-acceptance-standard.md)校验展示载荷，不制定新SLA业务口径，不修改冻结策略或数据库。

## 协议来源

- server/domain/src/main/java/cn/mop/domain/sla/TicketSlaClockEvaluation.java：状态、暂停、未知与违规含义；监控恢复MET仍可能paused=true，不能错误拒绝。
- server/domain/src/main/java/cn/mop/domain/ticket/TicketSlaSnapshot.java：毫秒UTC、阈值/工作窗口及配置引用结构。
- server/persistence/src/main/java/cn/mop/persistence/PostgresTicketWorkflowRepository.java：未建流程空值、时钟任务类型/键/回执及顺序。
- server/persistence/src/main/resources/db/migration/V33__ticket_lifecycle_and_durable_clocks.sql：既有任务唯一性和回执枚举，只读核对，冻结SQL未修改。
- API的SNAKE_CASE序列化和既有隔离HTTP合成样本，真实接口样本回放不代表本次运行真实服务。

## 校验与未知值

拒绝未知流程状态/优先级/档位、倒置观察时间、错误集合类型、无效缺失原因和伪造未建立流程数据。workflow_version=0必须保持未建流程的null、空集合和0次重开，不补正常时钟。

时钟核验截止/完成时间范围、50/80/100阈值顺序及截止一致性、工作窗口数量与有无截止的关系，以及PENDING_CONFIG/RUNNING/MET/BREACHED/PAUSED/CANCELLED的明确字段含义。待配置仍保留null违规值；不因为缺少完成时间便臆造已完成，也不把所有暂停都等同人工确认。

耐久任务校验精确字段、唯一键、排序、种类/键匹配、回执与完成时间成对，以及合法回执枚举；EMITTED只表示内部任务回执，不表示外部通知送达。配置引用校验来源、版本、release_id和摘要结构，不声称摘要来源已认证、已由客户批准或已生产生效。

错误时间统一显示SLA完整性错误，避免显示无关“资产导出”错误。非法响应不保留旧核验结果，仍能按原工单GET恢复。修正SLA表格误用的台账caption，并提供独立命名键盘滚动区域。

## 分层证据

新增60项：58项载荷/状态回归、2项实际页面DOM异常恢复与表格语义。58项在旧读取器上50失败/8通过，修正后通过。既有合成fixture曾把完成时间放在观察时间之后、并在有截止时给出0个窗口，现按服务端协议纠正；真实HTTP捕获文件未改且仍兼容。

初次类型检查发现阈值对象回调窄化及参数化测试返回类型推断错误，修正后再跑最终检查，原失败保留。[验证记录](evidence/service-tier-sla-integrity-20261021/validation.json)、[本地HTTP记录](evidence/service-tier-sla-integrity-20261021/http-smoke.json)。真实浏览器/axe与128项正式TC仍NOT_RUN；无真实费用、工单、权限或供应商操作。
