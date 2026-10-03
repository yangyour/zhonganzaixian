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

## 2026-10-03 资产详情日期只读面板

资产详情新增授权“维保与超期日期对照”，按明确点击读取真实API0.22，展示服务端参考日/Asia-Shanghai、读取时刻、资产/元数据版本、两项记录日期及7/30/90天/本季度标志。过往日期红色标记，当天单独描述；空值显示未配置/未知，不能把缺日期视为0或过期。不改变合同固定12列主台账，完整列表区间筛选另行建设。

AssetDateReader逐字段核验租户/资产、最低已知版本、元数据版本、服务端时间与上海日期的一致性、日期差/窗口/季度以及“覆盖未验真、隐患/送达未查询、此次无动作”字段。刷新先清旧结果，失败不保留旧日期结论，读到新版后拒绝版本倒退；资产版本、租户、身份、权限变化或卸载取消请求并拒绝迟到结果。仅GET，按钮不保存资料或生成提醒。页面明确结果截至该次读取时刻，不用浏览器时钟重算当前过期。

14新增Web共504、类型/构建及44契约通过。实际AssetDateReader+ApiClient对真实PG/OIDC/API20HTTP（面板9GET、0写入）通过；设置1合成资产+3资料修订共4独立写入。29日/91日/过去/今天/清空未知、另一版本更新期间迟到读取丢弃、双击抑制、丢弃已成功GET后旧结果清除、权限上下文变化、客户403，以及只读前后资料与审计尾不变均已验证。实际抽屉/控件/390px和截图仍未跑，B03未解除。

M3-10不是整体DONE：维保起始/供应商/提醒接收人、P4隐患与商机、每日Worker与企微送达、列表区间筛选/季度导出/报告仍待。API0.22与V1–V14不变。见[实际模块HTTP](evidence/niko-asset-date-reader-http-20261003.json)、[Web证据](evidence/niko-asset-date-panel-20261003.json)。
