# D06 监控API版本协议边界

## 2026-10-03 纯编解码切片

Zabbix组件/新测试账号的限niko授权仍待用户明确回复；没有安装、建库、创建账号或运行采集。等待期间完成独立ZabbixVersionProtocol纯编解码器，依据[官方7.0 apiinfo.version](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/apiinfo/version)：请求固定方法、空数组params、JSON-RPC2.0和明确整数id，不接收auth参数或任意API方法。它不实现HTTP传输，也不读取私有配置或模拟一个Zabbix服务充当联调。

协议响应限制16KiB、3层、64 token、严格UTF-8、重复键/尾随拒绝；只允许明确JSON MIME及UTF-8 charset。HTTP非200（包括重定向）不能当成功，响应RPC版本、id类型/数值、精确result/error根字段均核对。仅接受规范三段数字版本；7.0.x为本切片可识别系列，其他规范版本保留UNSUPPORTED_VERSION，不静默扩大支持范围；异常文本、前导0、预发布或空白不回显为版本。结构化API错误只返回分类，不记录上游消息/正文。

配置URL纯语法检查仅允许显式端口的127.0.0.1和/api_jsonrpc.php或/zabbix/api_jsonrpc.php，拒绝localhost/其他地址、userinfo、query、fragment、编码/绕行路径。URL检查不是授权，也没有放宽网络/系统安全设置；后续真实传输仍必须针对已批准配置，HTTPS证书不能绕过，重定向不能跟随。请求整数id限制在跨端可精确表达范围；协议编码不包含身份秘密。

VERSION_READ仅描述这一份响应中的版本，monitoringReady=null、observationsRead/serverSchedulerVerified/tenantMappingVerified/providerPermissionsVerified=false；不能把版本字符串当Server采集、Agent心跳、数据库、用户权限或租户隔离就绪。现有MonitoringAdapter尚无真实观测实现，不会从这个编解码器制造正常监控值。

## 验证与后续

15隔离字节/URI单元通过，覆盖无auth空数组请求、整数精度、响应id串/浮点/错号、非200/HTML/错编码、重复键/过限、API错误脱敏、系列不匹配、固定回环与路径/凭据拒绝。完整根1287=1274执行（392既有PG回归）+13历史fixture跳过/6MinIO排除，70契约/生成一致性通过；392PG不包含Zabbix业务。

新增adapter显式Jackson依赖沿用项目BOM，未改API0.33或V1–V19，未重启API，也未增加Provider HTTP、数据库表/凭据/权限。候选阻塞记录338764e CI37156695981 success已回读。真实传输超时/字节界限/禁止代理与重定向、组件版本/权限/观测仍待后续分别实施及批准后的真实联调，不能标D06/128项DONE。见[证据](evidence/niko-zabbix-version-protocol-20261003.json)。
