# 原指定环境巡检与工单验证（2026-10-04）

本地承接API0.36/V22及最新模板、执行单页面，复用原指定PG17.9、Keycloak26.2.4和MinIO；六个既有OIDC测试身份重新登录，不创建组件或身份。测试连接、Cookie/CSRF、原请求和控制记录仅留忽略目录。模板、人工结果与时间都为明确虚构联调数据，不当真实巡检或生产批准。

|范围|实际结果|证据|
|---|---|---|
|未发布模板API|117 HTTP，角色/CSRF、跨租户404、原回执、同键与CAS、字面分页；18自建候选归档|[117次](evidence/local-inspection-template-api-http-20261004.json)|
|人工执行单API|124 HTTP，冻结模板/资产、自述null与未填、双摘要、CAS/原回执；14自建执行单和2模板归档|[124次](evidence/local-inspection-worksheet-api-http-20261004.json)|
|实际模板请求模块|99 HTTP，四动作丢成功响应、实际MSP403后原body/path/method/key恢复、15+1及15+2分页；18候选归档|[99次](evidence/local-inspection-template-client-http-20261004.json)|
|实际执行单请求模块|105 HTTP，四动作原请求/来源快照恢复、晚响应丢弃、冻结历史；18执行单/1模板归档|[105次](evidence/local-inspection-worksheet-client-http-20261004.json)|
|明确来源选择模块|11 HTTP，现有资产字面/游标与模板版本/摘要核对；1候选归档、执行单写入0|[11次](evidence/local-worksheet-source-selector-http-20261004.json)|
|工单读取模块|六身份39 GET，现有登记/附件投影、分页刷新、其他报障人及外租户404；业务写0|[39次](evidence/local-ticket-reader-client-http-20261004.json)|
|模板真实DOM|Edge154.0.4258.53保留沙箱，1440×900/390×844共15检查；重复key不丢行、上下移动/null与空说明、丢成功响应原重试、真实409保输入、归档恢复和5版历史|[最终浏览器](evidence/local-inspection-dom-attempt2-20261004.json)|
|执行单真实DOM|同浏览器/视口13检查；资产与模板不自动首选、2已填/1未填不当正常、时间null和空/null备注、模板更新仍保留v1定义、丢响应原请求恢复、手机读取原v1未知时间|[最终浏览器](evidence/local-worksheet-dom-attempt2-20261004.json)|

两组真实截图均已逐张查看，长摘要/名称换行和表格内滚动没有文档横向溢出；真实浏览器页面错误0。手机项是390px仿真，不是物理手机或企微。通知、证据上传/绑定、模板适用性/批准、季度归集、H5/PDF/客户发布及Q23/Q24仍未完成。

第一次模板浏览器脚本的归档确认按钮与页面按钮同名，明确等待消息框后重跑通过；首次证据保留，原候选已归档。第一次执行单脚本使用资产不支持的page_size=20而被400拒绝；改为页面真实选项15后通过，原模板已归档。它们是测试脚本错误，不把失败次数累加成通过；每轮控制及原请求保留，不永久删除测试记录。

全后端、真实PG/MinIO、契约及1156 Web/类型/构建按[组件计数](evidence/local-component-v36-20261004.json)分别报告；PG/模块HTTP/模拟DOM/真实浏览器是各自验证范围，不能累计为合同完成率。完整128项仍未正式验收。
