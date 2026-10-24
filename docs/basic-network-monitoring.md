# IP基础监控

2026-10-24：既有四设备24项网络与AF 3项资源/内部状态通过两轮真实受限读取，27项时间全部前进，20项MOP HTTP通过。新增可重复的只读采样/历史诊断工具；华为SNMP、SCP SDK权限及信锐详细指标仍保留阻塞。用户选择先保留现有监控，未改采集配置、告警或组件生命周期。当前状态、复查命令及边界见[最新记录](device-monitoring-check-20261024.md)，下文为历史实施证据。

2026-10-14防火墙新密码已私有记录并验证，CPU/内存持续采集、真实历史及桌面/手机监控页已上线。API0.49新增当前值/历史读取，复用既有Zabbix历史库和独立最小权限身份；67Java、1944Web、133契约、候选与公开入口各40HTTP及14浏览器检查通过。测试站14服务就绪、PG17.9/V31，无迁移；仅项目API/Web更新并新增采集Worker。SCP/其他指标及正式告警仍待，详见[完整交付与证据](firewall-resource-monitoring.md)。

2026-10-14当前基线：防火墙192.169.11.1已加入，四台设备共24项真实ICMP/TCP持续监控，API、页面及历史已验证并部署；防火墙API层返回未登录1003，专用账号待提供。见[本次联调及证据](firewall-basic-integration-20261014.md)。下文2026-10-12的三台/18项及旧地址阻塞属于历史阶段记录。

2026-10-12用户要求先实现无需设备凭据的基础监控，厂商资源指标等拿到文档后继续。复用192.168.1.215上Zabbix7.0.31，独立测试组`MOP LAN basic network test 20261012`仅纳入超融合192.168.1.170、华为交换机192.168.1.2、信锐交换机192.168.1.125。防火墙本日双来源复测仍不通，保持单独阻塞，不修改路由或设备配置。

## 持续采集

三台设备各6项：ICMP响应、3包丢包率、平均响应时间，以及TCP22/80/443握手，共18个simple check。每60秒由Zabbix Server执行，历史保存在已有Zabbix PostgreSQL库，测试保留7天且关闭trends；这些是明确测试配置，不代表正式客户留存/SLA规则。纯TCP检查不登录设备、不验证HTTPS证书或HTTP页面。信锐22/80返回0是探测结果，不自动生成异常告警。

ICMP延迟原单位为秒；无响应时Provider可能返回0，展示时必须结合ICMP/loss解释，不能写成“0ms正常”。每个监控项独立保留时间，旧值不作为新采样。固件、设备身份、CPU/内存/温度/带宽及SNMP授权仍未核验。

入口：`python tools/configure-basic-network-monitoring.py --output <新报告路径>`默认只读计划，加`--apply`才创建本项目组内主机/监控项。重跑先按明确名称/组/标记/配置回读，已存在且一致不重复创建，配置漂移停止而不覆盖。没有扫描、全局配置修改、组件重启或通知规则。SSH凭据只读取原私有文件，通过固定主机信任的SSH通道访问服务器回环API。

依据：[Zabbix 7.0 Simple checks](https://www.zabbix.com/documentation/7.0/en/manual/config/items/itemtypes/simple_checks)、[item.create](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/item/create)。网络探测无需在设备装Agent；详细资源数据仍待设备文档与只读授权。

## 采集切片证据

- [组件前置盘点](evidence/basic-monitoring-components-before-20261012.json)：既有12服务运行、PG17.9/V30、Zabbix7.0.31。
- [本日网络复测](evidence/basic-monitoring-preflight-20261012.json)：三台响应、防火墙未通。
- [只读计划](evidence/basic-monitoring-plan-20261012.json)、[显式创建](evidence/basic-monitoring-provision-20261012.json)、[重复核对](evidence/basic-monitoring-reapply-20261012.json)。
- [真实首值](evidence/basic-monitoring-first-values-20261012.json)：18项均取得Zabbix采样、unsupported为0；后续持续性与MOP/API/UI另行记录。

五项隔离工具测试覆盖仅读计划、同配置重复执行不写、配置漂移拒绝、版本门禁、指标键及类型。初次计划把认证头附带到不需要认证的apiinfo.version而被Provider拒绝，已修复该方法不带认证头后才执行创建。

第一次重复核对因Zabbix回读标签额外带automatic字段而停止，没有执行写入；已按tag/value与非自动标志核验归属，重新核对通过。

本批未新建/迁移MOP数据库结构，V1–V30不变。厂商文档及Q06正式告警规则仍待；全128项合同不因基础采集而标DONE。

## MOP读取接口与运行配置

API0.46新增只读`GET /api/v1/network-monitoring`和`GET /api/v1/network-monitoring/{assetId}/history?metric=latency&window=HOUR_1`。指标仅icmp/loss/latency/tcp_22/tcp_80/tcp_443，窗口仅HOUR_1/HOURS_24；拒绝外部host/item ID、IP、租户覆盖、重复参数和未知字段。历史最多2000点，超限422而不静默截断，保留原始十进制字符串和Zabbix记录纳秒时间；未采样区间不补零、不声明整窗完整。

每次读取先从当前PostgreSQL成员关系与assets:read授权取得当前租户，再逐一核验资产归属和IP；Provider I/O后重查授权/资产。跨租户或未绑定资产404，撤权/权限版本变化不能返回旧数据。三台设备通过既有资产API在“虚构星湾A（测试环境）”创建独立IP基础测试登记，沿用该测试租户已有站点；创建具备CSRF、原请求幂等和审计。超融合暂用已有other类型，保留实际品牌/型号，不自行确定合同九类枚举。台账的正式接入状态未被基础网络响应覆盖。

私有配置项为`MOP_NETWORK_MONITORING_ENABLED`、`MOP_NETWORK_MONITORING_ENDPOINT`（明确回环API端点）、`MOP_NETWORK_MONITORING_TOKEN`和`MOP_NETWORK_MONITORING_BINDINGS`。绑定为最多16条严格JSON数组，字段tenant_id/asset_id/ip/brand/model/host_id/items，items为6个上述指标键到Provider item ID的显式映射；UUID、IPv4、唯一ID与字段集合均校验。原值只保存在Git忽略目录及服务器受限env中；不从客户端接收配置，不提供任意监控API透传。此配置为测试部署的显式固定映射，不是完整凭据托管、映射审批/迁池或多资源池管理模块。

应用使用独立Zabbix User类型、只读组及API allowlist，仅能读取该3主机组的host.get/item.get/history.get；已真实验证只有3台可见且host.update被拒绝。管理员凭据不进入MOP进程。HTTP不跟随重定向、不使用系统代理，整体请求10秒及2MiB响应上限，JSON重复键/尾随内容与错误信封失败关闭；Provider原错误正文不返给用户。

查询同时核对当前主机/监控项、精确key、数据类型、单位、60秒周期和7天测试历史配置；配置漂移返回503。各指标独立标记FRESH/STALE/NO_DATA/COLLECTOR_ERROR/DISABLED/CLOCK_SKEW，非新鲜值为null。180秒是本次测试的数据新鲜度界限，不是离线告警或SLA阈值。ICMP无回应是网络探测结果，不推断设备关机；端口0不生成业务告警。启用本能力后，来源认证失败、映射漂移或缺少新鲜样本使`/health/ready`返回503与明确组件原因；设备无回应但持续有合法采样时，采集组件仍可就绪。

## 实际接口验证

候选API连接同一指定PG/Zabbix完成[63次HTTP检查](evidence/basic-monitoring-http-18012-20261012.json)：3台设备、6指标×2窗口共36次真实历史读取，最少31点；padmin/msp/tadmin/viewerA均按当前租户读取，viewerB列表空且三台历史均404，匿名401、非法/重复query400。实际37份成功业务数据通过[0.46契约校验](evidence/basic-monitoring-http-schema-20261012.json)，不把历史已有点数当成本次测试数。

[只读凭据范围](evidence/basic-monitoring-reader-scope-20261012.json)与[真实失败关闭](evidence/basic-monitoring-fail-closed-20261012.json)分别验证：独立负例API用无效token时live200/ready503，正常候选恢复ready200；未停采集、未改共享服务。72项后端相关用例（22新增）通过，1项显式SSH转发到真实Zabbix的Java读取测试单独统计。127项既有契约测试通过；本批没有PG结构迁移。

真实联调暴露多包平均延迟可能有超过12位小数；初版严格读取因此503且没有假成功。现已支持整数/小数各最多40位、按BigDecimal保留原值，实际精度回归及真实历史读取通过。排查中的私有Python脚本曾因模块重名误调用旧Zabbix验收脚本，已将新辅助模块改为唯一名称lan_monitoring_zabbix，恢复被其覆盖的旧本地证据；最终盘点证实10个非API/Web服务的镜像/启动时间/重启次数与最初相同。HTTP验证工具遇到SSH分块响应读取中断，Connection:close及一次显式GET重试后完成；正式入口有1次此类重试，记录在HTTP证据transport_failures，不算独立业务测试通过。

## 页面与测试站部署

入口：[基础网络监控](http://192.168.1.215:10010/network-monitoring)，使用既有项目账号，在“虚构星湾A（测试环境）”查看本批设备。三张卡片分别显示地址、原始品牌型号、ICMP状态、延迟/丢包及三个TCP端口；页面每30秒读取，后台60秒采集不依赖页面是否打开。用户明确选择设备、指标和1h/24h窗口，显示实际曲线及最近12条原始值。超过180秒的样本以及本地页面失去刷新不会继续显示为可达；切换租户、权限变化和读取失败清除旧数据，晚到响应不得回写。历史缺口断开，超时返回的零延迟不画成正常值。

1838项前端测试通过（含本批16项）；类型检查和正式构建通过。[真实Edge 155.0.4283.45页面验收](evidence/basic-monitoring-ui-20261012/result.json)完成16项：实际登录/三设备、六种指标、两种窗口、设备切换、刷新、错误恢复、手机导航和另租户空列表。1440×1000与390×844没有文档横向溢出，pageerror为0，6张截图已人工检查。手机宽度是浏览器仿真，不是物理手机验收。错误提示场景仅浏览器拦截响应；真实Zabbix无效token另见独立后端证据，不混为设备故障注入。

现场检查还修复了全局“尚未采集”旧提示和手机导航缺少监控入口。首轮浏览器脚本因响应监听晚于输入而超时，改为先监听再执行操作后通过；菜单图标导致严格文字定位失败亦已修正。测试工具错误不计作产品通过。

[最终部署证据](evidence/basic-monitoring-deployment-20261012.json)：80个Web工件上传后逐文件SHA256核对，API构建摘要e24b82969e15d7f468cfff9ffbe591f35e330d6dadbebf3acf8d6c8f931e0fee；Web在独立不可变版本目录，通过WEB_RELEASE_DIR切换。仅更新API/Web，其他10个服务保持；首次部署见[初始记录](evidence/basic-monitoring-deployment-initial-20261012.json)。最终[正式测试入口63HTTP](evidence/basic-monitoring-http-10010-20261012.json)再次核验同租户读取、36个历史查询和跨租户404，最少35个历史点，API切换前历史仍可读。

[最终组件盘点](evidence/basic-monitoring-components-final-20261012.json)确认12服务运行、PG17.9/V30、Zabbix7.0.31，候选/负例API及本机验证转发已移除，公开ready仍为true/durable=true/production_ready=false。未新增数据库迁移、设备写入、通知或告警阈值。CPU/内存/接口流量/温度/防火墙会话/超融合资源等按用户要求等待文档，不因基础网络正常而推断设备完整健康。

本批[分层验证汇总](evidence/basic-monitoring-validation-20261012.json)分别记录隔离、真实组件、HTTP、契约和浏览器数量，包含关系不重复累加。
