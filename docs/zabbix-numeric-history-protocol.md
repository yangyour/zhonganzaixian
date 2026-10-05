# Zabbix 7.0 数值历史协议边界

## 已核对的官方依据

官方 [history.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/get) 提供history类型、itemids、包含边界的time_from/time_till及clock/ns排序；其中0是数值浮点、3是无符号整数。官方 [History object](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/object) 将clock/ns定义为接收时间，ns是纳秒；不能将其直接说成物理探针执行时刻或已同步时钟。

接口还可能返回未被清理的已删除对象历史。因此未来接入必须先核对平台当前租户/资产/资源池/映射授权，不能仅凭provider itemid或历史存在判定可见性；本纯协议构造器不承担这项授权。

## 本次代码范围

ZabbixNumericHistoryProtocol仅编码固定history.get单item数值查询并解码调用方明确提供的响应字节。ExplicitBinding保留平台Scope、pool UUID、providerItemId和mappingRevision，但没有持久化/批准/真实性验证。没有HTTP transport、token/密码参数、登录、Provider安装、凭据生成、控制器或浏览器API透传。

- 类型明确0或3，不依赖Provider默认。只发itemids单元素、4个output字段、whole-second包含边界、clock/ns升序和limit=10001；不接受hostids/search/output扩展或任意method。时间过滤的末秒包含其所有ns，不误说成只到该秒的零纳秒
- JSON-RPC版本与安全整数requestId严格匹配，UTF-8/JSON媒体类型、重复键/尾随内容/深度/字符串及2MiB解码界限。响应根/行字段精确核对；API错误只返回分类，不回显message/data
- 返回itemid必须是明确映射目标；clock/ns有界且处于查询秒桶内，顺序严格递增、重复点或逆序全部拒绝。10,000点可解码，出现第10,001点返回POINT_LIMIT_EXCEEDED且不泄露部分数组；不丢弃/去重/聚合
- 数值保留原十进制字符串；无符号值最大2^64−1，超过JS安全整数仍不转double。当前有界支持普通十进制字符串：浮点整数/小数各最多40位，无符号20位；指数形式、负零、JSON数字替代字符串或超范围明确拒绝/UNSUPPORTED_NUMERIC_ENCODING。此限制不是声明所有Provider合法数值均已支持；需要扩展时增加明确精度协议，不静默转换
- clock/ns完整保留为Instant纳秒与sourceRecordKey，不把同毫秒不同ns合并。标准化纯核心也放开来源时间到纳秒，仍独立核对上海业务日期和范围，未改变API0.44里事务/授权观察时刻的毫秒格式
- 缺值来源记录仍只保留sourceRecordedAt，sampledAt=null。解码到的接收时间不证明物理探针时刻；后续映射必须保留timestampMeaning

## 不作的声明

POINTS_DECODED仅表示给定字节符合本次受控协议，不证明访问过Zabbix。transportPerformed/credentialsUsed/sourceVerified/mappingVerified/authorizationVerified/completeWindowClaimed均false，monitoringReady=null。明确空Provider数组与失败时null数组分开；空数组也不代表设备正常、留存完整或已授权。

真实资源池/monitor_object_map、单位/OID版本审批、认证传输、Provider现场响应、采集曲线/仪表盘联调仍未实现或未验证。未把前一版本原库测试作为新环境证据，也未建立本机监控替代服务。正式合同验收NOT_RUN。

最终26新codec测试+25指标核心+15版本协议=66聚焦通过；Java根2311报告/1530执行/781跳过、125契约、158Python通过。见[协议证据](evidence/niko-zabbix-numeric-history-20261005.json)。
