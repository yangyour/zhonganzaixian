# D07 客户确认窗口的显式时间对照

## 合同依据与范围

M5-15/T00R071、M5-16/T00R072明确：基础档和标准档48小时，进阶档24小时；到期前12小时提醒一次。来源为仓库中两份合同的[逐项原文](../doc/development/evidence/contract_requirements.json)，不是原型默认值。`CustomerConfirmationWindow`只实现这些固定时长的无副作用对照。完整状态扩展仍需M5-3书面确认/Q07，真实身份资格Q09、通知Q14及重开MTTR/Q27没有被本切片批准。

## 输入与输出

输入明确tier、resolvedAt、observedAt，任一可为null表达未知；不读系统时钟、不推断当前服务档位、不按工作日或本机时区换算。时间范围0001–9999年、精度UTC毫秒为与现有接口一致的技术边界，越界或计算结果越界拒绝，不能截断或回绕。

- 缺tier或resolvedAt：提醒时间、确认截止、阶段全部null
- 仅缺observedAt：保留由明确输入计算的时间，阶段null
- observedAt早于resolvedAt：BEFORE_RESOLUTION，表示所选对照时刻，不能冒充真实已解决
- 提醒前WAITING；提醒时刻及之后、截止之前REMINDER_WINDOW；精确截止及之后DUE
- missing保留所有缺项并不可修改；结果及原输入不可变，后续另一档位计算不重写前一个结果

结果字段显式命名scenarioReminderAt/scenarioConfirmationDueAt/scenarioPhase；effective=false、effectiveConfirmationDueAt=null、shouldNotify=null、shouldAutoClose=null、workflowStatus=CONFIG_PENDING。DUE只说明输入时间对照，不证明工单仍待确认、提醒未送达、具备关闭权限或可执行任务。

## 尚未接入的真实动作

无数据库/新SQL、仓储、API或页面。本类没有工单/租户/身份输入，不是授权器，也不得接受客户端对照结果直接关单。正式接入必须从当前租户保护事务读取已冻结档位/本轮解决时刻/状态版本，再处理并发确认、未解决、自动关闭及重开；保留原SLA截止和历史。按工单+确认周期建立提醒唯一键、耐久outbox/回执，派发前重查状态，过期任务不得复用原文“12小时后”假称准时送达。不能凭数学时间宣称提醒一次、真实按时关闭或<5秒验收完成。

与既有72小时重开资格校验并列，但不会生成关闭时刻、重开次数、客户确认凭证、close_mode或统计。API契约仍0.44.0，V1–V26不变，128项正式验收NOT_RUN。

## 测试

15项独立JUnit覆盖三个档位、到期/提醒前1ms与精确边界、未来解决对照、缺每一输入与全缺、只读缺项、场景无授权、前后不同档位不改旧结果、UTC闰日跨月、毫秒/范围/截止溢出。实际测试数量与根回归、跳过项见[证据](evidence/niko-customer-confirmation-window-20261005.json)。所有新测试为纯内存数学，不是PG、HTTP、企微或真实时钟Worker验证。
