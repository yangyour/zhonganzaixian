# 首页本月服务可用率卡片接线

UI02 / M1-1。首页月服务可用率不再使用布局元数据的固定未知值，而与服务健康、故障时间线共享同一次授权快照。后端复用既有 `ServiceAvailabilityAggregate`，按精确已知可用毫秒/有效服务毫秒加权，不对服务百分比取简单平均；任一服务覆盖不足则总比例仍为null。

`ServiceObservationSnapshot.monthly_summary` 返回 percentage、known_available_millis、effective_service_millis、service_count、qualified_service_count、coverage_status。月初空窗仍保留服务数，状态 EMPTY_MONTH_WINDOW；不默认100%。客户端核对汇总与各服务的时长、数量和覆盖一致，异常拒绝整份快照。

卡片区分无权限、读取中、取消、读取失败、未知与明确零值，显示达标覆盖服务数。下钻定位本页同一快照的服务明细，避免跳到不同期间的历史报表造成数字不一致。刷新、权限变化和卸载保护复用同一读取器，不重复发起独立KPI请求。

当前来源仍没有正向健康覆盖适配器，已知可用时长为0；故障覆盖不足时只能未知，不能因卡片接线宣称客户服务健康。没有默认服务映射、客户目标、部署、迁移或真实设备操作。PG/实际浏览器未验范围沿用前批，代码验证见 `docs/evidence/dashboard-month-service-kpi-20261024/validation.json`。
