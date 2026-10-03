# 维保与超期日期对照边界

## 2026-10-03 M3-10 显式到期日期核心

新增WarrantyDateAssessment，以明确参考日对照已有维保到期/超期服役两项日期，彼此独立。日期为空则天数、7/30/90天窗口、本季度归属均null，不把未知当已过期或0天。采用日历日差、包含0到7/30/90天；负数另标PAST_DATE，不混入“未来N天内”。到期当天单独DUE_TODAY，避免未经确认就声称整天已失去服务。参考季度按同年同自然季度，不把去年同季度当本季度。服务器Instant按已确定Asia/Shanghai转换一次，纯函数不默认系统当前日或浏览器日。

这仅是记录日期的算术，不证明维保起始日/供应商/合同覆盖，也不生成P4隐患或发送通知；coverageVerified=false，动作NOT_REQUESTED。T+29进入30/90窗口，T+91不进入，7/30/90边界、闰年、跨年/季度、上海午夜、0001–9999范围均有测试。

12新增纯规则、完整根842=829执行（含256既有真实PG）+13历史fixture跳过/6MinIO排除及42契约通过；最后加Instant.MAX范围保护后完整重跑成功。没有该模块新PG/API/UI或通知送达证据，无迁移，API0.21未变。M3-10真实到期提醒、P4隐患/商机工单、报告、导出、客户及DOM验收均未完成。同步刷新128项表中已有工时/手工来源/客户评论/日历/SLA切片索引，新增128行唯一性/证据链接/未完成状态测试，防止仅在长日志有记录而主表陈旧。

见[核心证据](evidence/niko-warranty-date-core-20261003.json)。

## 2026-10-03 API0.22 真实记录日期只读投影

GET /api/v1/assets/{id}/manual-metadata/date-assessment复用既有真实PG资料仓储的重复读事务：当前成员/权限/租户与资产、当前元数据来自同一授权快照。仅msp/padmin有asset_dates:read，跨租户/不存在404，客户403；全部query拒绝，不允许覆盖tenant/as_of_date/timezone/version。参考日仅从本次服务端observed_at按Asia/Shanghai确定，绑定asset_version/metadata_version；无资料时日期和派生值null、有资料但日期未填仍unknown。

输出source=postgresql和recorded_dates_source=MANUAL_ASSET_METADATA，同时scope=RECORDED_DATE_ASSESSMENT_ONLY。不声称真实维保覆盖：coverage_verified=false，当前未提供起始日/供应商保持null。未查既有隐患与通知，lookup明确NOT_IMPLEMENTED；actions_this_request仅说明本次未创建隐患/未通知/未修改资产，不能解读成全局没有隐患或已送达。契约严格区分未知/过去/今天/30日/未来，窗口标志与天数有交叉约束。

8新增控制器、根850=837执行（256既有真实PG）+13历史fixture跳过/6MinIO排除、44契约/490Web/类型构建通过。首次Mockito抛错后重新when触发旧异常，改doThrow后完整根重跑；契约生成加强交叉约束时括号错误已修复，重新生成及44项全测通过。API拥有的栈正常退出并以固定新JAR启动，6新OIDC会话。37真实HTTP均符合API0.22：1合成资产/4元数据修订（共5设置写入），29/91/过去/今天/空值、角色与query边界、跨租户404、3次重复读取元数据/审计尾不变。业务真实读取与日期数学已验，不是新通知组件联调。

无新结构/迁移，V1–V14冻结；页面/完整筛选、每日隐患Worker、服务经理/决策人通知与真实送达、商机工单、季度导出仍待，M3-10及128项非DONE。见[真实HTTP](evidence/niko-asset-date-assessment-http-20261003.json)和[回归证据](evidence/niko-asset-date-assessment-api-20261003.json)。
