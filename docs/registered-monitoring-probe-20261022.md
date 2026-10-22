# 登记目标服务器单次测试（2026-10-22）

## 接通的功能

监控接入页面新增“服务器登记目标”：明确读取登记配置，选择某个授权资产后点击“测试连接”，直接得到服务器单次探测结果，不必手工导入报告。原先的配置生成、下载和采集机CLI测试仍可用。

API新增：

- GET /api/v1/monitoring-onboarding/registered-targets
- POST /api/v1/monitoring-onboarding/registered-targets/{id}/test，仅接受 configuration_sha256。

接口版本0.78。GET不触发网络探测。POST仍使用现有CSRF过滤器，要求padmin及 assets:read / configurations:read / configurations:write；不写业务表或发布配置，故不伪造幂等写回执。当前每进程只允许1个并行探测，重复请求429。配置摘要变化409，目标越租户、资产缺失或管理地址不一致404。

## 服务器配置与缺少的依赖

参考 deploy/examples/monitoring/server-test.env.example：

- MOP_MONITORING_PRESET_TEST_ENABLED 默认false；关闭时API503 CONFIG_PENDING。
- MOP_MONITORING_PRESET_TARGETS：与CLI/页面一致的完整version1 JSON；最多64目标，启动时严格校验并冻结，不自动读取浏览器提交的地址。
- MOP_MONITORING_PRESET_ALLOWED_ENDPOINTS：精确端点JSON数组，默认空。
- MOP_MONITORING_PRESET_ALLOWED_TARGETS：Blackbox/SNMP下游精确目标JSON数组，默认空；与Exporter本身授权分别判断。

需要运维人员将配置显式交付至受限运行环境。本文和示例不会启用任何真实设备、修改端口/防火墙、保存凭据或重启服务。预设目录由Maven直接打包同一JSON源，避免Java另存一份模板名单。

当前API仅支持IPv4，拒绝DNS、URL凭据、查询、片段、链路本地/元数据、组播/未指定地址及未知字段。直接Exporter/Agent端点IP必须等于授权资产管理IP；Blackbox/SNMP下游IP必须等于资产管理IP。因此远程集中式数据库Exporter的跨主机映射尚不支持，不应改填假资产IP规避。CLI的IPv6支持不等于此API已支持IPv6。

SNMP只传已配置的命名auth profile；不接收community/password。HTTP凭据、mTLS、FQDN以及真实供应商安装与身份范围仍未接入，不能通过关闭TLS验证补齐。Zabbix agent此阶段仅TCP连通测试，SNMP原生交接项仍NOT_RUN；未冒充已完成Zabbix协议认证或主机绑定。

## 探测与权限边界

- 只按登记ID调用，HTTP请求不能提交端点、下游、租户、资产替换或凭据。摘要必须匹配当前单目标规范化配置。
- Provider I/O前后复核当前成员关系和完整资产记录；IP/版本/授权变化后不释放旧结果。名单读取也复核，不能用已缓存配置代替当前资产授权。
- HTTP不跟随跳转、不使用环境代理、严格证书与主机名校验。整体请求1–10秒，至多2MiB，取消未完成Future；拒绝压缩、HTML登录页、错误状态和畸形指标。
- 预设特征指标有限且必须存在；成功指标（如probe_success、pg_up）须唯一并等于1。NaN/Inf必需指标、重复序列/标签与过大数据不成为成功。
- 返回匹配指标名/样本数量及状态，不返回原始标签、值、错误正文或秘密。持续采集、设备身份和健康证明始终false。
- 前端独立核对名单/结果身份与权限版本、目标配置摘要、预设及结果含义。取消只取消等待，明确提示服务器可能仍在完成单次探测；迟到结果不会渲染。重复点击、撤权、账号切换及刷新失败均清除旧状态。

## 本批边界

本批完成的是多场景预配置、可下载的采集配置、CLI验证/单次探测、页面接入工作区和服务器受控单次测试。Prometheus持续采集依赖实际采集服务安装并审核应用生成配置；本次未安装或配置真实监控组件。Zabbix模板实例查找/绑定、自动应用/回滚、持久化接入注册、MOP统一持续采集与历史视图、告警策略和真实设备联调仍是后续工作，不因单次通过或disabled示例交付而标DONE。

用户已确认当前视觉样式；本批只在现有工作区结构新增功能控件，没有修改全局样式。此后按用户要求逐页打磨交互、优先共用组件，仍不重新设计既有视觉。样式基线见 ui-style-baseline-20261022.md。

## 验证与问题修复

后端聚焦37项：13配置跨语言摘要、默认关闭/精确授权、协议/输出/并发、防覆盖、成员/资产前后复核，以及现有安全链CSRF。含6项实际回环HTTP/TCP测试，响应是合成数据，不是外部供应商验收。前端新增25项逻辑/原生DOM；新增4项Playwright脚本，仅发现。

完整Maven首次被新增HTTP测试嵌套配置污染组件扫描，导致3项ProfilePolicyTest失败；仅换TestConfiguration尚未隔离，随后以专用测试profile隔离该测试配置并复验，不放宽生产profile保护、不允许覆盖bean。测试类不进入应用包。

前端测试初版有一处括号错误；契约聚合检查也指出新增只读POST缺少显式CSRF头声明。均已修正，保留安全断言，并将该无业务写入的探测列入明确只读POST名单。最终各层数量和构建摘要见 evidence/registered-monitoring-probe-20261022/validation.json。

本次没有SQL、数据库迁移、原服务器部署、真实设备访问或凭据创建；正式128项TC状态不变。不能将上游已有部署/截图扩展为本新增探测入口已发布或浏览器通过。

合入前主分支已有63项界面与部署记录更新至36b91a12，全部保留。只合并新增功能导入/节点与文档段落，保留监控配置页操作栏位置及最新搜索位置；未回滚或改写主分支全局样式。同步后前端4894项与构建再次通过，浏览器脚本累计152项/15文件仅发现。上游真实浏览器与部署报告仍属于其原切片证据。

2026-10-22 12:47 UTC 用户更新分工：后续前端优化由用户手动完成，此前交互优化队列不再由dot执行；dot转为专注监控后端、多种服务与设备的快速接入。本批已实现前端仅收尾提交，后续不覆盖用户前端修改。
