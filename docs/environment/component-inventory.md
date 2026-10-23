# 指定测试环境组件清单

2026-10-23 04:50:34 EDT 最终统一更新：7应用为main `2291ddf8`，14服务真实就绪；复用PostgreSQL17.9/V50、MinIO、Redis、RabbitMQ及Zabbix，7基础容器生命周期和原私有配置保持，0迁移/无新基础组件。新增后台能力只交付代码及工具，未自动装配或启用真实通知、采集、备份/恢复/删除。452HTTP与桌面实际读取通过，已有未配置门禁保持；HTTPS证书名称/信任链仍未通过独立复测，无批准替代。实际版本、发布与回退见[统一发布](../unified-release-20261023.md)及[组件证据](../evidence/unified-release-20261023/components-after.json)。

2026-10-23 通知后台消费最初只读核验：指定 LAN PostgreSQL 17.9／V50，运行账号可读 workflow outbox 与回执日志，两类来源当时均 0 行。随后 03:17 EDT 按授权创建并核验独立合成通知租户，6 项 V50 真实 PG 并发/恢复检查通过，专用租户保留 15 行不可变日志，其他租户仍 0 行；旧实际业务租户合成测试未运行。供应商用隔离适配器验证，真实签名/送达、路由调度和站内 outbox 写联调仍未完成。探测证据、影响与状态见 [通知组件核验](notifications-20261023.md)。无新增组件、迁移、部署或外部发送。

## 2026-10-23 报告原子捕获验证

同日首页待办聚合继续复用原 PG17.9/V50，在新的两处报告隔离测试租户中完成 4 项真实查询/并发快照测试；新增三名无登录凭据主体和成员已停用。实际业务租户十张相关表摘要及迁移历史不变，无新增服务/迁移/外发/部署。V42 业务状态实流转、新接口 TCP HTTP 和浏览器尚未验证，详见[首页待办聚合](../dashboard-pending-tickets-20261023.md)。

报告独立会话固定主机键只读盘点：原 LAN 14 服务全部运行且 ready=true，PG17.9/V50，Redis7.4.9、RabbitMQ4.3.2、Zabbix7.0.31、MinIO RELEASE.2025-09-07；production_ready=false。随后持有 environment.lock，在同一 PG 上新建两处带 SYNTHETIC_REPORT_CAPTURE 前缀的隔离租户及无登录凭据的测试主体，以受限运行账号通过临时回环 SSH 转发完成 7 项真实报告捕获测试。主体及成员关系结束后停用；保留不可变测试证据。实际业务租户七张相关表行指纹和迁移历史不变，0 迁移、0 新基础组件、0 外部通知。缺少正式报告审批/发布及客户规则属于业务未完范围，不是 PG 不可用，不启用替代组件。见 [功能与运行边界](../report-capture-20261023.md)及[实际 PG 证据](../evidence/report-capture-20261023/postgresql.json)。

2026-10-22 20:44 EDT 最终统一发布：包含刚到达的4848519监控纯计算信号提交，7应用均更新为main `a411968`；14服务ready=true/durable=true，PG17.9/V50、0迁移，7基础组件生命周期及原配置保持。370 HTTP/摘要及4浏览器读取重新通过，未创建演示身份、写合成业务、调用设备探测或启动新采集来源。登记目录/Prometheus来源未配置的真实503门禁保持，不将纯计算信号判定当作真实设备服务状态已接通。最新组件和回退证据见[最终发布节](../all-branch-release-20261022.md)，下文首轮及更早版本为历史。

2026-10-22 20:30 EDT 最新统一发布：7 应用均为 main `8671369`（API0.81），14 服务运行、ready=true/durable=true；PG17.9/V50、0 迁移，7 基础组件的 ID/镜像/启动时间/重启次数及原私有配置保持。实际账号 4 台真实设备、工单/事件空态，370 HTTP/摘要及 4 浏览器读取通过。当前登记目标目录和 Prometheus 读取来源未配置：用途为登记目标采集状态的只读观察，来源未接通时真实接口 503 CONFIG_PENDING；影响为无法提供抓取首值/时效，候选为单独核验并显式授权的现有 Prometheus 来源，状态 NOT_CONFIGURED/未批准启用，无新增监控引擎或本机替代。监控 CLI 仅离线验证，未进行设备探测或配置应用，见[发布与组件证据](../all-branch-release-20261022.md)。

2026-10-22 10:21 EDT 最新应用发布：仅 API/Web 更新为 `9d3fed4`，桌面操作流程已通过实际域名复验；14 个项目服务 ready=true、durable=true，PostgreSQL 17.9/V50，0 迁移。其他 12 个容器的生命周期、镜像及配置与发布前一致；不新增组件，不执行消息发送或设备探测，当前账号仍只读 4 台真实设备。见[发布记录](../web-flow-release-20261022.md)。

2026-10-22 实际数据切换：JiangFaMing 当前租户只保留 4 台用户指定设备及真实监控、账号和渠道绑定；合成业务清理 1,758 行，257 项外键及回滚演练通过。复核 PostgreSQL17.9/V50、14 服务 ready，所有容器生命周期及组件版本与执行前一致，0 迁移/0 重启/无新组件。真实基本网络采集和防火墙资源采集继续复用 Zabbix；未接入指标保持未知。旧测试身份停用，禁止向该租户重新生成合成数据。见[切换记录](../real-data-cutover-20261022.md)。

2026-10-22 最新UI发布：API/Web已更新至123b5f5，右上角搜索、紧凑操作栏和事件样式已在业务域名实际验证；14服务就绪、PG17.9/V50不变、0迁移，其余12服务生命周期保持。见[发布记录](../compact-ui-release-20261022.md)，下文此前版本为历史。

2026-10-22 页面布局验证：本地新前端连接指定 LAN 现有 API，40 个授权页面双视口及资产/事件额外两视口只读检查通过；本机 Edge 155.0.4283.62 / Node 22.22.1。无基础组件增删、重启、配置或迁移；未另起数据库/缓存/监控，未部署测试站。组件版本沿用下文已核实的部署记录，本轮不冒充重新盘点。见[布局验收](../compact-page-actions-20261022.md)。

2026-10-22 后续重部署已完成：用户恢复网络后重新固定主机密钥SSH/owner及14服务核验，7应用切换至603337b，PG17.9/V50历史保持、0迁移；7基础组件生命周期及原私有配置不变。195项真实HTTP、143静态摘要及蒋发明真实登录/桌面手机4项浏览器读取通过。监控13预设/13禁用目标及标准库CLI已交付，仅服务器离线catalog/validate，不启用或探测真实目标。SSH管理路径阻塞已解除，历史失败保留；详见[本次发布](../test-environment-redeploy-20261022.md)。

2026-10-22 指定 LAN 更新：复用同一 PostgreSQL17.9、MinIO RELEASE.2025-09-07、Redis7.4.9、RabbitMQ4.3.2、Zabbix7.0.31、Java17.0.20.1/Nginx1.31.6，无新增基础组件；7基础容器在迁移和三轮发布中生命周期不变。项目库独立 V39→V50，应用发布0迁移，14服务/真实依赖ready=true，production_ready=false，详见[实际证据](../test-environment-update-20261022.md)。

本轮额外只读探测确认备份版本列表缺少独立授权查询 Bean 装配：源码只有可选接口/工厂，无正式提供者；真实API返回503 `BACKUP_VERSION_INVENTORY_NOT_CONFIGURED`。用途：按当前租户/设备独立授权读取版本元数据；影响：版本列表不可用，不授予下载/执行；候选：由经确认授权源实现并显式装配受限 PostgreSQL 查询；状态：NOT_CONFIGURED/未批准替代授权，不以草稿权限或空成功响应替代。该缺口不代表PostgreSQL离线。

2026-10-20 SLA专项继续复用同一LAN PG17.9/V39和受限运行账号；三项真实PG场景最终通过，临时SSH转发随验证退出。本次盘点发现测试租户尚未登记服务档位，新报表保持null并允许只读事实查询，未代填业务配置。健康采样覆盖和观察窗验真属于尚未接入的业务能力，来源事件数据不当作完整健康覆盖；不新增基础组件替代它们。见[最新验证](../customer-policy-optimization-20261020.md)。

2026-10-20客户口径优化：固定主机信任SSH只读盘点确认LAN PG17.9/V39、14服务运行及原应用ready=true。恢复关联与关单保护真实PG测试复用既有项目库、受限运行账号及临时回环SSH转发；仅追加SYNTHETIC记录，未新建本机组件、迁移、发送消息或切换服务。V40/V41仍仅有云端隔离证据，原测试站未应用。见[本次实施](../customer-policy-optimization-20261020.md)。

2026-10-19邮件通道：复用用户明确提供的腾讯企业邮箱SMTP465服务，本机实际Java客户端及指定LAN候选/正式API均已通过加密/主机名/证书校验与账号认证；本机IMAP993此前仅连接验证，本次不读取邮件。持久化继续复用指定LAN PG17.9，独立新增项目V39、6项真实PG通过，未启动本机数据库/队列或邮件替代服务。API/Web已更新至804a3d8，14服务运行，其他12项实例/镜像/启动时间/重启数保持一致；候选已清理，25项正式入口HTTP通过，本机验证进程已停止。用途为固定测试与本人通知明确转发，自动业务通知关闭；本机口令保持DPAPI加密，Linux采用0400、UID10001只读秘密文件，容器环境不含口令。实际邮件发送/投递尚未执行，不登记为完整渠道送达成功。见[邮件接入记录](../email-integration-20261019.md)、[正式组件/HTTP复核](../evidence/email-live-20261019.json)。

2026-10-19实际SNMP团体字已由用户提供并仅更新Git忽略私有文件，解除凭据未提供项；指定服务器及原Zabbix网络命名空间各一次身份GET仍`TIMEOUT_NO_RESPONSE`，没有身份/资源样本，monitoring_ready=false。当前需核对设备团体权限/视图/ACL、监听配置及收发统计/响应路径，状态`SNMP_NO_RESPONSE`；v2c已启用、目标IP属于Vlanif192且up/up以用户上轮截图为证。没有批准替代、其他凭据尝试或设备配置写入。见[实际参数复测](../evidence/huawei-snmp-actual-credential-probe-20261019.json)。

2026-10-19华为v2c/地址确认后再次复测：用户CLI截图已确认Polling v2c启用、192.168.1.2/24属于Vlanif192且up/up，解除这两项待确认；原私有示例凭据从服务器/既有Zabbix网络命名空间各一个GET仍超时，状态`SNMP_NO_RESPONSE`、monitoring_ready=false。实际只读团体字、视图/ACL及SNMP监听/收发计数未核验，仍影响身份、固件及详细指标首值/持续历史；无批准替代、未试其他团体字或执行设备写入。见[本次证据](../evidence/huawei-snmp-v2c-enabled-probe-20261019.json)和[观察来源](../evidence/huawei-snmp-v2c-enabled-observations-20261019.json)。

2026-10-19华为配置截图后复测：同一192.168.1.2/UDP161、私有示例参数，在指定服务器/原Zabbix网络命名空间均超时 `TIMEOUT_NO_RESPONSE`，与早一轮errno111拒绝分别保留。用途为只读设备身份/指标；影响为身份、固件、首值和持续历史仍不可用，monitoring_ready=false。待设备侧应用并核对Polling v2c、Vlanif192是否拥有目标IP、只读团体/视图与来源ACL；无替代组件或安装/设备写入。状态`SNMP_NO_RESPONSE`，不是已接通。见[最新诊断](../evidence/huawei-snmp-after-ui-screenshot-20261019.json)和[截图分析](../huawei-snmp-integration-20261019.md)。

## 2026-10-19 华为 MIB 与 SNMP 示例只读联调

固定主机信任复核指定 LAN 环境，前后 14 服务运行、PG17.9/V38、Zabbix7.0.31、原应用 ready=true。华为 192.168.1.2 在服务器/现有采集网络命名空间均 ICMP 2/2、TCP22/80/443 开放，但使用用户授权文档示例发送 SNMP v2c GET 时两处均 errno111/`UDP_PORT_UNREACHABLE`；未取得身份/固件/指标，设备 monitoring_ready=false。用途为厂商资源、接口与光功率采集，影响为首值/历史/恢复/页面接线仍未验收；解除条件是设备侧核对 SNMP 代理、UDP161、协议/只读视图及来源 ACL，并提供实际只读参数。无批准替代组件，不改成本机监控或 fixture；服务器缺 snmpget/snmpwalk，仅使用一次性有限 UDP 诊断，不安装/重启服务或修改设备。MIB 为社区镜像，七项修正候选保持未发布/未核验。见[联调记录](../huawei-snmp-integration-20261019.md)、[最终诊断](../evidence/huawei-snmp-example-probe-final-20261019.json)。

2026-10-19测试站统一更新：固定主机信任SSH核对后，复用指定LAN PG17.9/V38、MinIO、Redis7.4.9、RabbitMQ4.3.2与Zabbix7.0.31；API/Web及5项Worker更新到 `34a183e`，14服务就绪，7基础组件ID/镜像/启动时间/重启次数全部不变，0迁移。企业微信由服务器现有受信任出口直接读取，凭证仅私有文件只读挂载；登录和人工通知启用，自动业务通知关闭。本次无组件安装/升级、无测试发送；HTTPS证书及真实扫码仍保留独立状态。见[发布记录](../test-environment-update-20261019.md)、[组件证据](../evidence/test-environment-update-components-20261019.json)。

2026-10-19企业微信消息联调：本地API复用指定LAN PG17.9（V38）与已有服务器出口，实际应用与成员校验、耐久发送和用户接收确认均已完成。新增的是本地业务进程及SSH转发，不是基础组件；原共享服务未安装、替换或重启。应用页面/登录代码在本地验证，公网原站点未切换。扫码登录结果与完整D11验收单列，见[开发记录](../wecom-development-20261019.md)及[实际接收证据](../evidence/wecom-http-20261019.json)。

2026-10-19企业微信接入复核：指定LAN服务器固定主机信任SSH恢复可用，项目归属已核对；实际PG17.9、当前V32。本机直连企微应用/成员接口返回60020（出口IP未受信任），指定LAN出口及同客户端经SSH隧道读取成功，应用启用、指定成员已激活。复用该服务器现有出口与PG，不安装新组件，不修改共享端口或配置。通知发送与登录完成情况分别见[企业微信开发记录](../wecom-development-20261019.md)，本次仅客户端只读联调通过。

2026-10-18恢复后复核：原指定测试服务器通过项目SSH隧道可用，PG17.9、mop_test及迁移V33结构/权限核验通过；V29—V33均按显式迁移入口执行。LAN测试站此前独立连接失败状态仍保留，不与本次原服务器证据合并。D07持久化测试使用项目合成数据，未改变共享组件配置或其他租户数据。

2026-10-18本地接续探测：指定LAN SSH握手失败（未取得banner、未进入认证），复核SSH读取超时；10010端口可建立TCP，但ready/live读取RemoteDisconnected。用途为新工单结构/PG及HTTP集成验证；影响为当前版本/就绪未能盘点，真实迁移和联调未执行。既有组件仍为首选，没有批准替代组件，也未启动本机DB/缓存/队列。状态`CONNECTIVITY_BLOCKED`，保留旧证据的原日期，不把10月15日成功当作今日成功。详见[本轮记录](../notion-development-20261018.md)。

2026-10-15：容量运行配置继续复用指定LAN PG17.9/V32与现有API镜像，未新增基础组件或迁移。固定7项同快照读取及权限检查已真实验证；明确标记的容量测试值已禁用，不影响采集/保留执行策略。API0.52与Web已发布原测试站，候选已移除，其余12服务未变，无迁移，证据见[配置化实施记录](../configurable-development-20261015.md)。

## 2026-10-15 运行配置API/Web发布

复用原14个项目服务，PG17.9已显式升级V32；发布API0.51与Web时其他12服务生命周期一致，后续Web说明修正时API及其他13服务不变。候选API已清理，无新增基础组件；配置管理就绪不表示所有业务消费者或生产审批已完成。[交付与证据](../configurable-development-20261015.md)。

## 2026-10-15 运行配置V32复用

复用指定LAN测试PG17.9、受限mop_test_runtime及显式迁移账号，仅新增本项目运行配置不可变发布结构；无需新数据库/缓存/队列，原14服务持续运行。配置目录并不代表外部组件/凭据已配置或业务执行已就绪。[迁移证据](../evidence/runtime-config-20261015/migration.json)。

## 2026-10-15 其余页面静态发布

复用现有LAN测试API/PG等14服务；仅切换Web至 `33c78dfa` 构建，其他13服务生命周期一致，0迁移/新增基础组件。显示摘要与计算回读不代表设备执行、通知渠道或客户批准已接通。[发布证据](../evidence/other-pages-20261015/frontend-deployment.json)。

## 2026-10-15 其余页面接续：本人工时

继续只读复用指定LAN测试环境14项服务，本人工时通过真实受限账号读取PG记录；前端本页汇总不新增接口、基础组件、迁移或财务规则。见[实现与验证](../other-pages-20261015.md)。

## 2026-10-15 页面接线测试站发布

仅切换Web静态构建（`05c344ac`）；其余13服务实例/镜像/启动时间/重启计数一致，14服务就绪、无迁移/新增组件。已直接经测试站入口读取4台设备、资源历史和报障，数据不依赖本机隧道存续。生产TLS、正式事件/SLA/渠道及其他设备资源源仍按既有缺口登记。[发布证据](../evidence/live-pages-20261015/frontend-deployment.json)。

## 2026-10-15 页面真实数据复用

只读盘点指定LAN项目14服务运行，前端通过固定主机信任SSH回环API复用现有Zabbix/PG及防火墙采集器；页面真实读取4台绑定设备和CPU/内存历史。没有新增或替换基础组件、数据库迁移或服务生命周期变更；版本沿用已部署清单，本轮不冒称重新核验全部组件版本。正式事件/SLA/送达缺口保留，见[范围与验证](../live-pages-20261015.md)。

2026-10-14 功能页原型还原最终Web版本 `a2c6875` 已切换，89个静态文件摘要一致；沿用原安全头配置，API与其他13服务实例/镜像/启动时间/重启计数未变，ready=true，无新组件或迁移。实际页面继续连接指定测试API；本轮未重新盘点组件版本。见[最终发布](../evidence/function-prototype-20261014/frontend-deployment.json)。

2026-10-14 逐功能验收及前端修复发布后再次盘点：14项目服务运行，PG17.9/V31、ready=true、production_ready=false；只更新项目Web安全头和静态目录，两次切换均核对其他13服务实例/镜像/启动时间/重启数一致，无新增组件或迁移。真实Worker、MinIO、监控和业务API参与45个已实现子场景验收。见[最终组件](../evidence/feature-acceptance-20261014/components-after.json)与[验收报告](../feature-acceptance-20261014.md)。

2026-10-14 功能验收修复项目网关重复安全响应头：原Nginx镜像中语法检查通过，只切换Web配置；API及其他13服务实例/镜像/启动时间/重启计数一致，无数据库迁移，ready=true。Web静态目录保持原版。见[部署记录](../evidence/feature-acceptance-20261014/security-headers-deployment.json)。

2026-10-14 原型样式完善继续复用指定LAN测试站的真实登录、资产、总览、搜索与监控API。本机仅运行候选静态Web及回环代理，未新增/替换基础组件，无服务重启、数据库迁移或测试站Web切换；已有CPU历史真实回读成功。组件版本沿用下文清单，本轮未重新盘点版本。见[验证边界](../prototype-ui-20261014.md)。

2026-10-14防火墙新密码已私有记录并验证，CPU/内存持续采集、真实历史及桌面/手机监控页已上线。API0.49新增当前值/历史读取，复用既有Zabbix历史库和独立最小权限身份；67Java、1944Web、133契约、候选与公开入口各40HTTP及14浏览器检查通过。测试站14服务就绪、PG17.9/V31，无迁移；仅项目API/Web更新并新增采集Worker。SCP/其他指标及正式告警仍待，详见[完整交付与证据](../firewall-resource-monitoring.md)。

|组件/用途|当前证据及影响|状态与替代|
|---|---|---|
|AF API采集Worker|新增项目工作负载，复用既有Java镜像；CPU/内存每60秒真实读取，固件8.0.106.4646|测试可用；无新增基础组件替代|
|Zabbix资源历史与受限身份|独立3项、读取/写入角色分开；实际历史及错误恢复通过|复用7.0.31和原PG历史库，测试历史7天|
|证书身份及正式规则|当前仍TEST_INSECURE，告警/SLA未配置|TLS/客户业务确认继续待处理|

2026-10-14 Impeccable前端发布仅切换Web静态目录，87文件SHA256一致；既有API、采集器及其他13正式服务生命周期未变，无迁移及新增基础组件。开发工具只在本机使用，真实接口仍复用LAN测试环境。见[部署证据](../evidence/impeccable-ui-20261014/deployment.json)。

2026-10-14导出消息恢复前端已部署。只切换Web静态版本；切换窗口的全部已观测其他容器实例/镜像/启动时间/重启计数保持。本轮无API/Worker重启或数据库迁移，既有基础监控GET200，续领真实XLSX通过；[部署快照](../evidence/export-message-recovery-deployment-20261014.json)与[清理后既有组件检查](../evidence/export-message-recovery-components-final-20261014.json)分别留证，不代替另一批设备采集验收。

2026-10-14导出消息遗漏修复继续复用实际API/PG/MinIO。原地续领与未知授权恢复已验证，原消息完整行不变，每个原请求只记一条授权审计；本轮无新基础组件、后端改动或迁移。预览由SSH回环转发实际API，未用本机组件替代。见[范围和证据](../export-message-recovery-20261014.md)。

2026-10-14用户更新防火墙密码后，原Java客户端已实际登录并读取版本AF8.0.106.4646、CPU 12%、内存58%，正常注销；此前PASSWORD_EXPIRED门禁已解除。新密码仅记录在Git忽略的私有凭据文件。本次为真实单次首值，持续采集/历史/页面继续实现，证书仍为显式TEST_INSECURE。见[密码更新后首值](../evidence/firewall-password-updated-read-20261014.json)。

2026-10-14已将临时证书跳过实现为SCP/AF实际Java客户端的TEST_INSECURE配置，94Java及3工具用例通过；同JVM严格→测试→严格真实验证完成，例外仅两个固定地址。SCP认证成功但ACCESS_DENIED，AF登录成功但PASSWORD_EXPIRED，均未伪造资源指标或启动后台轮询；只发布独立检查工件，无API/Web重启或数据库迁移，当前API0.48/V31保持。见[实现及证据](../device-test-tls-mode.md)。

2026-10-14导出消息API0.48/Web已部署，V31/0启动迁移。13服务在候选及本机验证进程清理后就绪；除API/Web外11服务镜像/启动时间/重启计数不变，复用PG/MinIO及原队列。Web安全头/静态gzip已生效，HTTPS/Secure Cookie与生产就绪仍未据此完成。见[最终盘点](../evidence/export-inbox-components-final-20261014.json)和[部署](../evidence/export-inbox-deployment-20261014.json)。

## 2026-10-14 导出消息复用与V31

复用当前PG17.9和MinIO，不新建基础组件或账号。V31增加本项目本人导出消息表及受限ACL，API开关默认关闭；候选显式启用后ready包含真实结构保护。原68表65表摘要保持，另3表存在并发写入，不声称全表等值。真实MinIO/56HTTP和仅自有候选API重启已验证；共享13服务未因候选验证重启。缺失的自动发布、外部渠道和正式性能仍分别登记，见[消息说明](../export-inbox.md)及[迁移](../evidence/export-inbox-migration-20261014.json)。

2026-10-14 上线优化第三轮最终只读复核包含导出队列：13服务运行、V31、ready=true、production_ready=false。候选安全头/静态gzip配置在既有Nginx1.31.6语法验证通过，临时文件移除，未reload或迁移。当前站主JS在gzip请求下仍无Content-Encoding；候选尚未生效。前期本轮V30/12项检查与后期V31/13项分别留证，环境升级属于其他并行任务。见[总证据](../evidence/launch-optimization-validation-20261014.json)。

## 2026-10-14 导出自动准备应用

复用既有PostgreSQL17.9/V30、受限mop_test_runtime和Java镜像；新增独立项目应用export-preparation-worker，不引入数据库/队列/缓存/监控组件。Worker无存储/迁移/身份源秘密，仅准备真实XLSX。候选进程验证38次HTTP和领取后中断接管，常驻发布后13服务全部运行、ready=true、production_ready=false；只新增该应用并更新Web，API及另10服务生命周期未变，V30/0迁移，本机转发关闭后复核仍通过，见[最终盘点](../evidence/export-queue-components-final-20261014.json)。缺失的站内信送达链仍阻塞M3-7完整闭环，候选为既有通知设计下的耐久站内消息，尚未交付；不能把MinIO或队列在线记为通知成功。见[功能边界](../asset-export-automatic-preparation.md)。

2026-10-14 前端上线准备第二轮再次经固定主机信任只读盘点：12服务运行，schema V30、ready=true、production_ready=false。真实浏览器通过SSH回环API验证恢复和自身会话结束/重登录；无基础组件安装、生命周期修改或迁移。

2026-10-14 全站交互打磨前再次执行固定主机信任只读盘点：12 服务运行、schema V30、ready=true、production_ready=false；本轮无组件安装/迁移/配置变更。前端浏览器使用本地构建和指定站真实 API；Vite 经 Nginx 反代登录因同源检查拒绝，静态覆盖又遇到浏览器私网访问限制，最终采用固定主机信任 SSH 隧道直连服务回环 API，保留同源校验，不放宽 CSRF。

2026-10-14导出与横排菜单已在原10010入口部署（代码fd1e8f1/API0.47/V30）：仅API/Web更新，其他10服务镜像/启动时间/重启次数未变化。独立导出MinIO已启用，发布就绪true；候选API和本机测试转发关闭后12服务仍运行、ready=true、production_ready=false。详见[部署证据](../evidence/export-navigation-deployment-20261014.json)与[最终只读盘点](../evidence/export-navigation-components-final-20261014.json)。

## 2026-10-14 导出发布专用存储

复用既有MinIO，在本项目内新增归属标签明确的 `mop-test-asset-exports` 桶和独立受限账号；原照片/导入账号及策略未改。3项真实MinIO验证原字节、并发不覆盖、5MiB以上条件上传；实际Delete、前缀外写、其他桶读取均拒绝。凭据和下载签名密钥仅私有配置，发布启用时独立探测；无存储回退或新基础服务，PG仍V30/0迁移。用途为已准备台账文件发布/授权读取，不代表站内信、自动准备或其他报告已交付。当前支持MinIO，OSS发布保留候选、未接入。见[功能与配置](../asset-export-publication.md)。

## 2026-10-14 验收差距检查的只读复核

通过既有固定主机信任SSH脚本复核当前内网指定环境：12服务运行、PG17.9/V30、MinIO显式启用、Zabbix7.0.31、Redis7.4.9、RabbitMQ4.3.2；ready=true、durable=true、production_ready=false。[当前组件证据](../evidence/acceptance-review-components-20261014.json)和[公开HTTP复核](../evidence/acceptance-review-http-20261014.json)已保存；未安装、重启或修改组件。本机PowerShell初次套接字访问被拒，但Python直连及SSH检查通过，不登记为服务器故障。

当前缺口仍为厂商资源读取、可信TLS、设备凭据/映射、通知/企微、堡垒机/录屏，以及已有组件上的业务接线和生产批准；用途、影响、解除条件见[验收差距文档第3节](../acceptance-gaps-20261014.md)。防火墙密码过期、SCP两项读取权限缺失以同日原验证为依据，本轮未重试设备登录。OSS只是可选存储，不能继续作为已选MinIO路径的阻塞；各历史条目保留原时点含义。

2026-10-14新账号已实际验证：防火墙登录成功，但版本查询返回1010“密码已过期，请先修改密码”；SCP在192.168.1.179以密码/RSA换Token成功，版本及资源池查询均403，明确缺少“SDK服务：获取SCP版本信息”和“SDK服务：获取资源池列表信息”。凭据缺失已解除，资源读取仍未就绪；原AK/SK未重测，严格TLS仍未通过，无密码/权限修改或新部署。见[新凭据联调证据](../device-credential-verification-20261014.md)。下文同日较早的“账号未提供”与未登录1003仅为历史阶段记录。

|当前缺口/用途|真实证据与影响|替代候选及状态|
|---|---|---|
|AF资源读取|登录成功，数据查询1010密码过期，需客户更新密码|不自行修改密码或换其他账号；PASSWORD_EXPIRED|
|SCP SDK只读权限|Token已取得，两项GET仍403/0x120220010001，缺少明确SDK读取权限|不使用超管账号替代；READ_PERMISSIONS_MISSING|

2026-10-14防火墙持续监控已补齐：复用Zabbix7.0.31及原项目专用组/受限读取身份，新增一个主机六项，当前四台24项真实采集；原三台主机及item ID保持。仅更新API，其余11服务镜像/启动时间/重启次数与本次初始盘点一致，最终12服务ready，PG17.9/V30，无新增组件或迁移。详见[联调记录](../firewall-basic-integration-20261014.md)、[最终盘点](../evidence/firewall-basic-components-final-20261014.json)。

|组件/用途|本次探测证据与影响|替代候选及状态|
|---|---|---|
|AF基础ICMP/TCP采集|192.169.11.1六项FRESH；MOP四台历史及跨租户404实测|复用原组件，无替代；测试基础监控可用|
|AF专用WEB API账号/资源读取|用户尚未提供；两条免登录GET返回HTTP200/业务1003；版本及资源未读|不得使用SCP密钥或默认密码替代；CREDENTIALS_AND_SCOPE_PENDING|
|AF HTTPS证书链|严格校验verify_code20，阻止正常可信HTTPS；仅本次免凭据探测临时跳过|客户提供受信任链及匹配名称/IP，尚未批准正式替代；TLS_CONFIG_PENDING|

2026-10-14防火墙地址更正：用户明确为192.169.11.1，设备清单已更新；服务器/采集容器网络两处ICMP均2/2回应，80/443开放，22超时，服务器路由仍经192.168.1.2。之前192.168.11.1的失败不能继续作为正确设备的网络阻塞。仅对指定设备/该地址增加预检白名单例外，不开放整个非私网范围；未修改路由、ACL、服务或设备配置，持续采集/设备API授权另行验证。见[复测证据](../evidence/firewall-corrected-network-20261014.json)。

2026-10-14用户授权对192.168.1.179临时跳过HTTPS证书验证进行只读联调：免认证公钥接口200/code0，AK/SK签名版本接口401/code401，源端提示EC2授权无效（0x020170010002）。凭据从截图私有提取，仍需页面复制原值排除OCR误差，并核对签名参数和授权；不能据此直接归因密钥错误或证明资源权限不足。临时TLS跳过仅本次验证脚本，既有应用/系统信任不改，未读到SCP资源、未写设备。见[验证与客户待确认](../scp-live-verification-20261014.md)；证书与资源范围缺口继续保留。

## 2026-10-12 SCP管理接口准备

|组件/用途|探测证据|影响|替代候选与状态|
|---|---|---|---|
|192.168.1.179 SCP管理入口|指定服务器与Zabbix网络命名空间ICMP有回应，TCP80/443开放，22超时；[证据](../evidence/scp-network-preflight-20261012.json)|只证实网络路径，不证明软件版本或API认证|用户已指定SCP，不改为直连HCI/SNMP替代；配置准备完成，真实API待验证|
|HTTPS证书信任/IP名称|指定服务器默认SSL验证失败；[证据](../evidence/scp-tls-preflight-20261012.json)|阻止发送认证信息，未请求业务API|待管理员提供受信任证书及匹配访问名称/IP；无关闭校验替代，TLS_CONFIG_PENDING|
|SCP专用只读凭据与源范围|用户确认未申请，资源池/项目ID及实际版本未核验|认证、资源数据、调度采集及页面首值仍NOT_RUN|AK/SK或专用只读账号为候选，须明确选择；CREDENTIALS_AND_SCOPE_PENDING|

本地库已有内部认证/资源查询与离线配置校验，复用原Java/Jackson/JCA，不安装新基础组件；现有192.168.1.170的Zabbix基础监控保留。配置只在Git忽略私有目录，公开模板不含秘密。见[SCP实施边界](../scp-connector-preparation.md)。

2026-10-12最终基础监控已部署API0.46及Web：[页面](http://192.168.1.215:10010/network-monitoring)。[最终盘点](../evidence/basic-monitoring-components-final-20261012.json)与最初逐项比较，10个非API/Web容器的镜像、启动时间和重启次数相同（包含Zabbix Server/Web/Agent），PG仍17.9/V30、12常驻服务运行；仅API/Web更新。候选/负例API和本机验证转发均清理后公开ready仍通过。Zabbix18项真实基础采集已接入MOP当前租户授权读取；CPU/内存/流量等厂商指标仍DOCUMENTATION_PENDING，告警规则CONFIG_PENDING，不能沿用下文旧记录把当前基础监控说成仅测试Agent。

2026-10-12新增IP基础监控：复用既有Zabbix7.0.31和其PG历史库，对三台明确设备创建独立组内18项simple check，60秒周期/7天测试历史；18项均取得真实首值。防火墙网络复测仍阻塞，详细SNMP/API指标仍待凭据及文档，无新增基础服务。MOP基础读取使用独立只读API角色/用户，仅授权该3主机组的host.get/item.get/history.get；token仅私有配置，不把管理员凭据提供应用。详见[实现与证据](../basic-network-monitoring.md)。

## 2026-10-10 四台设备预检与当前缺口

重新只读确认内网测试服务器12服务运行、PG17.9/V30、Zabbix7.0.31及MOP ready=true；[组件证据](../evidence/lan-device-components-20261010.json)。复用既有SSH与Zabbix容器网络命名空间，未安装组件或修改既有配置；监控组件运行不代表设备监控就绪。

|依赖/用途|当前探测证据|影响|替代候选与状态|
|---|---|---|---|
|AF-1000-B1800，192.168.11.1|服务器及Zabbix网络命名空间ICMP均无回应，22/80/443均HOST_UNREACHABLE；服务器路由经192.168.1.2|防火墙实际访问未通，不能认证/取首值|先核对跨网段路由、访问控制、地址及设备状态；不改路由或换用假数据，BLOCKED_NETWORK|
|aServer-P-2200，192.168.1.170|两来源ICMP有回应，22/80/443开放|只确认地址有网络响应，未验证设备身份/固件/资源指标|SNMP或厂商只读API仅候选，待凭据及能力确认，CONFIG_PENDING|
|S5731-S24P4X，192.168.1.2|两来源ICMP有回应，22/80/443开放|不能据此声明CPU/端口/流量可采|只读SNMP仅候选，待版本/凭据/MIB与现场样本，CONFIG_PENDING|
|aRS6300-50Q-EI-24X，192.168.1.125|两来源ICMP有回应，443开放，22/80拒绝连接|管理入口端口不同不代表设备离线，尚无端口指标|只读SNMP或厂商API仅候选，不要求打开22/80，CONFIG_PENDING|
|四台设备的只读凭据、租户/站点绑定、模板/OID|用户明确尚未配置凭据；本次不登录设备、不试默认community|尚不能进入正式监控接入/指标/告警闭环|使用Git忽略私有配置或既定凭据引用；无已批准替代服务，CONFIG_PENDING|

[最终网络证据](../evidence/lan-device-network-final-20261010.json)及[复验说明](../device-network-preflight.md)。两来源均为瞬时网络诊断；Zabbix命名空间测试使用宿主机Python/ping，不声称由Zabbix监控项完成采集。MOP业务就绪与设备monitoring_ready=false分别记录。以下2026-10-08及更早项目记录保持历史。

2026-10-08最新存储选择：用户要求上传下载改为MinIO并可配置切换，已在新服务器部署后端77b41cf及显式minio，API/照片/来源Worker使用统一选择和独立受限账号，端点/区域/桶可配置，历史测试默认桶/IAM不变；为真实可配置验证另建两个明确标记的测试桶及受限账号。48隔离/2实际MinIO、5种真实就绪切换、36上传下载HTTP/旧原图摘要通过；无迁移，其余9容器启动时间相同。OSS保留可选配置，资料缺失不阻塞已选MinIO的测试能力，生产批准/通知/导出发布等门禁不自动解除。见[配置说明](../configurable-object-storage.md)与[最终证据](../evidence/configured-minio-final-20261008.json)。

2026-10-08新增内网测试环境：用户授权在192.168.1.215验证连通并部署全套。固定主机键SSH真实认证，空白Ubuntu26.04.1主机安装Docker29.1.3/Compose2.40.3；Web/API/三个Worker、PG17.9/V30、MinIO既有验证版本、Redis7.4.9、RabbitMQ4.3.2、Zabbix7.0.31 Server/Web/Agent共12个常驻容器运行。新入口为http://192.168.1.215:10010/login，Zabbix10020；数据库/存储/缓存/消息仅回环，均使用独立项目账号、库/桶/vhost及命名卷。旧服务器只读导出三个镜像，未迁移旧数据或改变旧服务。

新环境照片/来源存储的受限读写及删除/越界/跨桶拒绝、三个Worker49次HTTP、身份53次HTTP、13项真实浏览器检查、单次导出XLSX及API重启保留19张业务表通过；V1–V30显式初始化且最终只读0迁移。临时下载/验证隧道关闭后仍ready=true。Zabbix实际采集仅为明确的合成测试Agent容器，MOP监控业务映射仍CONFIG_PENDING；Redis/RabbitMQ运行不等于MOP业务接入。OSS、通知、堡垒机/CMDB及正式规则缺口继续登记。完整[新环境部署记录](../lan-test-deployment-20261008.md)与[最终组件证据](../evidence/lan-deployment-final-20261008.json)独立于下文原环境历史。

2026-10-08 登录视觉整理继续复用指定 API 的项目账号与资产读取；真实登录、remote_test/ready/durable及20项浏览器检查通过。仅新增随前端打包的生成背景图，UI复用既有Element Plus，不新增服务/组件、不执行迁移或业务写入；未将背景里的机房设备当作真实采集证据。部署与组件版本边界沿用本轮[明确记录](../login-visual-refresh-20261008.md)。

2026-10-08 页面交互打磨：不新增组件依赖，API/数据库迁移不变。Edge 154.0.4258.62 接原指定 API 完成真实项目账号登录、非空虚构资产读取与 14 项交互检查；就绪返回 remote_test、ready=true、durable=true、production_ready=false。未执行业务写入或组件重启；ready 不提供组件版本，本轮未重盘点版本。隔离页面测试与上述 HTTP/浏览器证据分开，见[交互打磨记录](../ui-interaction-polish-20261008.md)。本机 Web 构建不代表服务器已部署，未解除真实监控/生产组件门禁。

2026-10-07本地补测：固定主机键SSH实际连接原PG17.9/V28与MinIO RELEASE.2025-09-07T16-13-09Z；772项PG/6项MinIO全部执行通过。指定库仍28条成功迁移、0失败，本轮0新迁移。10个项目/共享容器ID与启动时间前后相同，3个项目测试Worker仅在队列测试窗口暂停并在finally恢复；共享数据库、存储、Keycloak等未停止或重配。本机隧道因套接字10055断开的一轮留作失败，后续分批复验通过，结束后仅关闭本次本机隧道。Keycloak实测26.2.4仍运行，默认项目账号登录不依赖它；最终API就绪、本地账号登录与密码策略全null/version1通过。Redis/RabbitMQ、OSS、监控、CMDB、通知、堡垒机不因本轮PG/MinIO通过而获得业务联调结论。详见[完整补测](../local-test-completion-20261007.md)及[组件证据](../evidence/local-components-20261007.json)。

2026-10-05最终身份切换：项目账号/RBAC已在原10010入口运行（b68f324/API0.45/V28），默认不依赖Keycloak；PG17.9保存账号哈希、角色、成员与审计，SSO仅保留可选OIDC。未另起身份服务，原共享Keycloak/PG/MinIO启动时间保持。五个项目容器运行且0重启，密码策略全null。分层证据见[身份验收](../project-identity-acceptance-20261005.md)；旧条目按历史保留，客户正式批准门禁不解除。

2026-10-05项目内身份：原PG17.9已完成V27/V28显式增量结构，V28新增Argon2id格式兼容和租户资源能力，重复/只读均0DDL；其余63表指纹不变。账号、角色、成员授权继续复用该库，不新增IAM服务。新默认登录将采用项目内账号，OIDC保留可选适配、默认关闭；既有Keycloak不停止。当前正在真实回归，尚未以结构证明完成登录切换；策略字段保持null。见[项目身份说明](../project-identity-authorization.md)。

2026-10-05项目内身份/权限V27候选：继续使用原PG17.9，事务内结构探测13项通过后全部回滚，历史仍V26、无新账号或组件。密码策略字段全null，未代入建议默认值；Keycloak共享服务保持。见[项目身份说明](../project-identity-authorization.md)。

2026-10-05最终实测版本：PG17.9、KC26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z；项目应用Java Temurin17.0.20+8、Nginx1.31.3。三个常驻Worker37HTTP真实链路通过，单作业导出Worker生成并核验真实XLSX；API重启时其余7容器启动时间相同。未调用OSS/监控/通知/堡垒机，原门禁保持。见[验收报告](../test-server-acceptance-20261005.md)及[终态证据](../evidence/server-deployment-final-20261005.json)。

2026-10-05应用现部署在原指定服务器10010端口，API仅回环18010，项目Nginx回环19000只转发既有MinIO。五个项目应用容器复用服务器已有Java17/Nginx镜像；原PG/KC/MinIO启动时间在部署前后相同。独立mop-acceptance realm/client及六个虚构身份使用原Keycloak服务，原mop-test不变。真实ready/发现200、六角色登录通过；762PG/6MinIO组件回归、1685Web已验，后续业务HTTP/UI单列。见[部署说明](../test-server-deployment.md)。

2026-10-05服务器验收部署盘点：原PG17.9、Keycloak26.2.4、MinIO现有实例可达。原项目库显式V23–V26各1次、重复/只读0次，48张旧业务表摘要相同；未重启共享组件。外部登录支持新增固定验收origin/独立项目realm策略，8项隔离身份测试通过，真实部署与登录验收另行记录。见[部署说明](../test-server-deployment.md)和[V26证据](../evidence/designated-schema-v26-20261005.json)。

历史服务器盘点日期：2026-09-30；本地接续验证更新：2026-10-01。云端已停止业务写入，本批本地通过工程loopback隧道复用同一指定服务器，照片PG/MinIO/OIDC实际联调已通过；历史云端未连通记录另保留。原连接资料仅在Git忽略的`deploy/private/develop.txt`，本文不记录密码、token或含密连接串。

用户要求本地启动及涉及外部组件的集成测试也复用本环境。默认运行模式为 `remote_test`，组件失败不得回退到fixture或本机替代服务。项目全局规则见 `AGENTS.md`。

|能力|服务器已有组件/版本|项目本机SSH转发|测试环境复用/验证状态|
|---|---|---|---|
|关系数据库|shared-postgres：PostgreSQL 17.9；原镜像timescale/timescaledb-ha:pg17-pgvector086|127.0.0.1:15432 → 服务器127.0.0.1:12060|独立mop_test、migrator/runtime；V5独立迁移一次及结构/ACL核验，重复Apply为0DDL；43项真实PG测试（照片24+原19）通过；file/job/grant/operation/附件CAS/审计持久化，V1–V4历史保持|
|身份|hltk-keycloak：Keycloak 26.2.4|127.0.0.1:18110 → 服务器127.0.0.1:12110|本项目mop-test realm/client及6个虚构身份；本批4个测试身份真实OIDC与80次HTTP/契约通过，沿用当前membership授权；正式身份源/岗位及客户批准仍待确认|
|缓存|shared-redis：Redis 7.4.9|127.0.0.1:16379 → 服务器127.0.0.1:12070|发现运行组件；本批没有依赖缓存的业务，不另建缓存、不声明已集成|
|消息|zhsx-rabbitmq：RabbitMQ 4.3.2|本机AMQP15673 → 服务器5673；管理16673 → 15673|发现运行组件；待outbox/Worker开发时建项目vhost与受限账号，不使用其他项目队列|
|对象存储|shared-minio：RELEASE.2025-09-07T16-13-09Z|127.0.0.1:19000 → 服务器127.0.0.1:12080|3项真实存储、原图/grant/hash；52次边界/恢复/撤权和25次运行中RETRY_WAIT/自然完成独立通过，finally恢复；历史IAM证据保留；扫描/保留/报告存储仍待|
|Web反代|已有nginx容器与服务；本项目静态原型容器zabbix-msp-prototype运行|现有原型端口10000；本地Vite5173|原型仍是静态参考，不能视为Zabbix采集引擎；未改既有反代或端口|

宿主探测为CentOS Linux 7、Docker 26.1.4。本轮复用现有测试服务，未升级宿主/组件，未停止或重配其他项目服务。版本/许可/支持范围是否满足客户正式生产基线仍按Q03核验，本轮测试接入不代替正式批准。

## 缺失或尚未具备的组件

2026-10-02 资产导入测试存储已在同一现有 MinIO 中新增独立桶 `mop-test-asset-imports` 与仅限 `asset-import-sources/*` 的受限账号。真实 Get/Put、Delete 拒绝、其他桶不可见、前缀外及匿名访问拒绝已验证；照片账号/IAM、服务配置和生命周期不变。见[原始源存储说明](../asset-import-storage.md)及[组件证据](../evidence/asset-import-component-verification.json)。应用大文件条件写、V6/Worker/解析/映射仍待独立验证，不能据本组件证明导入完成；扫描、留存和生产许可门禁继续保留。

以下结论仅为当前盘点范围内未发现运行实例，不代表合同功能被取消。

|组件|探测依据|影响|替代候选|后续状态|
|---|---|---|---|---|
|Zabbix Server/Proxy|容器与运行进程未发现采集引擎；仅有同名静态原型|资产草稿可以持久化，真实纳管、采样、事件处理保持CONFIG_PENDING|优先客户既有监控；Zabbix具体版本待许可，无已批准替代|登记缺失；待组件许可/环境与Q03/Q06确认，不擅自安装|
|外部ITSM/工单系统|未发现可确认的专用实例或接口资料|一期本平台权威已由用户明确选择，本轮PG报障登记不依赖外部ITSM；若后续需要外部对接仍缺接口，正式状态/SLA/处理规则尚未完成|本平台为已确认开发方向，不新增替代组件；外部ITSM候选仅在后续明确对接需求后评估|缺失登记保留；权威方向已明确，Q07剩余状态/规则及Q08/Q09/Q14/Q16继续登记|
|堡垒机/录屏服务|未发现可确认的JumpServer/Guacamole或客户堡垒机入口|真实远程连接、双审批执行、录屏不能验证|优先客户堡垒机；JumpServer/Guacamole仅条件候选|登记缺失；按Q01/Q03确认路线后实施|
|通知通道/企业微信应用|仅凭主机盘点不能证实账号、应用或回执API|通知送达和客户H5绑定暂不能联调|按客户指定通道，当前无已批准替代|资料缺失；按Q17等提供测试应用与授权|
|客户CMDB接口|未提供可确认的接口资料或测试凭据|现有测试种子不代表真实客户资产来源|客户指定CMDB优先，测试种子不充当正式替代|资料缺失；后续绑定外部asset/source映射|

## 命名空间与权限

只新增本项目mop_test数据库、mop_test_migrator/mop_test_runtime角色以及mop-test realm/client/test users。数据库运行角色非超级用户、无建库/建角色/复制权限；Flyway与运行身份分离，运行角色不具DDL权限，审计表仅SELECT/INSERT。不存在自动数据库clean入口。

角色的成员关系从项目数据库读取，Keycloak签发者与sub仅用于身份映射。用户名只用于展示，同名重建账户不得自动继承旧映射。数据库服务共享不表示已经完成客户生产租户RLS或全部六层隔离，Q02仍单独确认。

报障及照片切片复用同一PostgreSQL/Keycloak/MinIO，不安装组件或开放公网端口。V5仅新增所需照片权限和ticket的version/updated_at更新，原内容与审计无覆写/删除权限，运行身份无DDL；结构与ACL已真实核验。报障outbox仍CONFIG_PENDING、next_at=null，没有RabbitMQ投递；独立PG Worker只消费照片任务。联系方式UNVERIFIED，JPEG/PNG仅用户开发方向，扫描UNSCANNED、生产UNVERIFIED、留存null；Q28/Q10及正式岗位门禁保留。

公开探测摘要：`component-probe.json`。凭据仅在 `deploy/private/`；原始全机盘点输出不用于公共产品页面。

## 2026-10-01后续D04诊断复用

[D04诊断](../configuration-diagnostics.md)继续使用同一PostgreSQL17.9/Keycloak26.2.4及loopback项目隧道，没有新建组件、迁移或扩大照片MinIO权限。读取器在单只读REPEATABLE READ内复核mop_test/runtime、active user/membership、当前padmin与授权版本，只投影当前租户登记/提案元数据；定义、发布和客户核验来源保持NOT_IMPLEMENTED/null。

后续完整检查51项指定PG=既有43回归+新诊断8，3项MinIO回归、123纯/隔离通过；[安全属性摘要](../evidence/local-configuration-diagnostic-build.json)与照片旧构建按批次分开。六个真实OIDC身份50项[HTTP记录检查](../evidence/local-configuration-diagnostic-http.json)通过；只关闭本run项目PG转发时diagnostic/ready503、live/Keycloak200，恢复同上下文后200，finally恢复标记、ready、六会话及padminA。旧registry/draft count和服务器内SHA256未变化，没有DDL/原成员/原值变更。

[实际页面证据](../evidence/local-configuration-diagnostic-ui.json)另有26项PASS、0pageerror，真实Edge154.0.4258.48持续Playwright无头、1440×900/390×844仿真及Root逐图复核。旧列表非数组Q空表和非文本metadata标签投影已修，negative rows保留并可见/可查明确503；缺失Q仍unknown，没有修改数据或fixture回退伪造成功。最终前端154=初始147+新metadata7，全量/类型/构建通过，66定向不再累计。诊断不接入Redis/RabbitMQ/监控或正式发布/客户核验，不据读取成功宣称生产批准。

## 2026-10-01本地照片组件联调

独立CLI默认Check、显式ApplyPhotos；V5已执行一次并冻结，重复Apply执行0DDL。API/Worker启动不执行迁移，进程拥有者记录覆盖API/Web/照片Worker；停止前核验路径、命令和启动时间，不停止远程组件或其他项目。

配置照片能力后，`health/ready`实际核对PG/OIDC、策略、V5结构/ACL及MinIO，照片`enabled/ready/policy_ready/schema_ready/storage_ready`全部true才就绪，失败503及脱敏原因码，不静默回退。未绑定文件仅上传人可读，内部授权成员只可读取其他人已绑定登记的文件；原图必须经当前会话和平台grant授权。

[构建摘要](../evidence/local-photo-build-summary.json)为96纯单元/隔离、43 PG、3 MinIO通过，fixture13跳过。基础80次HTTP、[恢复52次](../evidence/local-photo-resilience.json)、[运行中重试25次](../evidence/local-photo-worker-retry.json)和[UI36项](../evidence/local-report-photo-ui.json)分别通过。旧52次脚本先停Worker，其RETRY_WAIT未执行范围保留；新25次在正常就绪后断项目转发，真实RETRY_WAIT与同PID/startTime退避完成原图/hash，finally恢复marker/hold、三进程就绪及四会话。新专项未改V5/IAM/成员/全局或远程配置；UI为Edge持久无头和手机仿真，非物理相机/企微；未新建组件替代服务。

## 历史：2026-10-01云端接续阻塞与新增代码

下表保留云端当时的NOT_RUN/BLOCKED，不作为本批本地状态；V5及后端编译的本地结果以上述记录为准。

|依赖|本轮证据与状态|影响及后续|
|---|---|---|
|项目私有配置|develop.txt存在；runtime.json和known_hosts缺失；helper预检明确拒绝|真实OIDC/PG/MinIO及启动NOT_RUN；通过受控私有配置交付已有绑定，不重新生成或旋转账号密码|
|指定SSH通道|平台代理目标域名策略拒绝，未建立本项目受控隧道|指定组件云端连通及集成BLOCKED；保留原主机信任配置，不开放公网组件端口、不改其他服务|
|Maven依赖下载|Maven Central返回429、Google镜像返回403；有工具链不等于构建已通过|完整Java编译/JUnit仍未完成；保留TLS/制品校验，网络恢复后重新验证|
|照片PG结构与权限|V5为未执行草稿；V1–V4未改；API/Worker启动不自动迁移|新增照片请求/Worker明确未就绪；先审核再独立迁移，不以普通启动或target4测试偷偷执行V5|
|照片业务|新增PG租约/fencing、MinIO条件写、附件CAS、15分钟平台grant及前端代码|DOING/PARTIAL；真实恢复/竞争/撤权/原图HTTP尚未验证，不把PG消费者记作RabbitMQ业务接入|

当前云端未新建数据库、缓存、队列或监控替代指定组件。历史MinIO桶/IAM验证仍有参考价值，但新增业务与本轮访问权限须另外验证。Q28扫描/隔离、Q10留存清理及生产Q02/Q03/Q17等门禁继续保留，未知结果不记通过。

工程[GitHub Actions验证配置](../ci-validation.md)固定官方actions完整SHA及项目工具链。SDK提交`eba5630`的历史回读见[安全摘要](../evidence/github-eba5630-verification.json)。照片提交`f56ad1b`的[独立run 36846410035](https://github.com/yangyour/zhonganzaixian/actions/runs/36846410035)已通过connector实际回读，契约/Web、纯Java和后端三个job及28步骤全部成功，见[照片摘要](../evidence/github-f56ad1b-verification.json)。CI不连接私有指定组件，不提供替代PG/缓存/队列/监控，不能计入组件联调。历史失败及当时读取阻塞仍保留。

## 2026-10-02 Dot直接工作区访问检查

本次从指定handoff分支SHA恢复源码并校验全部400文件，未接回此前云工作区的私有运行资料。`deploy/private/develop.txt`、`runtime.json`、`known_hosts`及导入命名空间私有配置均不存在；已检查MOP环境变量引用但未输出任何秘密。指定PG/Keycloak/MinIO/SSH本轮没有执行连接探测，状态为BLOCKED_PRIVATE_CONFIGURATION，不能沿用本地历史PASS宣布云端就绪。影响：真实API/UI、完整Worker故障/重启及指定组件集成验收未运行。后续需要受控提供既有私有配置和主机信任资料；不重新生成凭据、不创建替代服务、不放开公网组件端口。代码、隔离单元、契约、类型及前端构建可独立推进。

终端Git/gh未登录，普通HTTPS Git读取失败；已连接GitHub工具可读取指定分支，并用于逐文件校验获取源码。没有在日志或仓库保存访问令牌。

## 2026-10-02 Dot获取私有配置后的实际检查

在原工作区更新到`81b2352d19198c76e8e8bbfb705f224b7a9b599c`，确认远端分支和本地提交/目录树一致。6个配置已逐文件校验，JSON可解析，照片/导入桶与runtime一致，指定SSH目标在固定known_hosts中存在。没有输出秘密或改变这些文件。

凭据缺失阻塞已解除，网络阻塞仍在：指定SSH端口直连返回ENETUNREACH(errno101)，获准提升执行后的同一无认证探测结果相同；仅尝试现有环境代理的CONNECT超时。未发送登录凭据，未执行远程命令，未建立隧道，因此PG/Keycloak/MinIO及真实HTTP/UI未运行，不能记为组件故障或凭据错误。当前只证实本地loopback监听可用。需当前云工作区具备到指定SSH端点的已批准可用网络路径；未开放远端公网组件端口、修改网络/服务或切换任务环境。

原runtime的解析器JAR是Windows路径，在Linux不可直接使用；工程内已有构建JAR可作为启动时进程环境覆盖，尚未启动API/Worker。后续运行仍排除迁移账号变量，保持V1–V6冻结和不自动迁移。

发现旧`inspect_test_server.connect`使用AutoAddPolicy且会重写known_hosts，已改为强制既有固定目标键、RejectPolicy、不保存主机键；缺文件/缺目标键在认证前失败。5项隔离信任策略回归通过，不是服务器握手验证。见[脱敏连接记录](../evidence/dot-private-connectivity-20261002.json)。

## 2026-10-02 niko独立环境新增授权与真实结果

用户明确允许niko部署组件，新增范围不修改原服务器。新PG17.11/Keycloak26.2.4及API已运行，69真实PG、6真实OIDC身份及44配置审批HTTP通过；正常重启8表指纹一致。MinIO因netlink EPERM未启动，提升执行同样失败；存储改按用户选定OSS推进，尚未认证或写入。原远程网络阻塞保留，新环境与旧结果不混合。详见[独立说明](../niko-local-test.md)和[新证据](../evidence/niko-local-components-20261002.json)。

## 2026-10-02 独立浏览器验收边界

新环境PG/OIDC/API的客户报障HTTP可以继续；同执行空间Chromium启动前因Unix socket EPERM终止，支持的云浏览器访问127.0.0.1前端被ERR_BLOCKED_BY_CLIENT拒绝。未加载页面、无UI通过证据，不改变系统权限或创建代理/转接绕过。详见[独立记录](../evidence/niko-ui-blocker-20261002.json)。真实OSS仍待受控替换配置。


## 2026-10-02 niko PostgreSQL Unicode查询依赖

实际目录读取确认新库server_encoding=UTF8、datcollate/datctype=C/C，已有pg_catalog.und-x-icu（ICU提供者）；默认lower保持Ä不变，导致新增Unicode资产筛选回归首轮失败。改用该既有collation的逐表达式小写，不创建collation、不修改数据库/服务器locale或权限。真实PG检查Ä/ΟΣ/İ/K、原文保持和无Unicode归一，前后全局C/C未变；主台账/资料查询/引用文本采用一致表达式，ready实际验证依赖、缺失失败503。原指定服务器尚无法连通，不能宣称具有相同依赖或效果。详见[主台账读取](../asset-ledger-reads.md)。


2026-10-02补充：niko API从每次启动独立SHA256/0400副本运行，不再直接使用可被Maven覆盖的target。真实重构建中target字节变化、API进程及副本不变，随后6身份OIDC/20只读HTTP通过；原ThrowableProxy时序线索、首轮/proc children采集失败和边界见[运行产物隔离](../niko-runtime-artifact.md)。未改变组件版本/账号/权限/原服务器；48守卫和根616（163真实PG）通过，其他阻塞仍在。


2026-10-02：niko独立V11客户评论表显式迁移1、重复/检查/旧模式0，16列/14CHECK/租户FK及runtime只追加ACL核验，7真实PG schema测试全部回滚后新表0行。V1–V11冻结，无原服务器/组件账号/父工单授权变化；尚无评论业务仓储/HTTP证据。见[客户评论结构](../ticket-comments.md)。


## 2026-10-03 V13/V14已提交数据的真实API崩溃恢复

新增独立显式--verify-owned-v14-api-crash场景，先检查本轮证据路径不存在，保留此前11表与V10的14表证据。必须核验本次父进程直接启动的API进程、当前用户、精确Java/JAR命令、固定只读工件SHA256和回环8080监听，再SIGKILL。真实wait退出137、连接ECONNREFUSED已验证；用同一固定工件重启，PG和Keycloak在本次API故障期间未重启。

新身份登录前，比对20张明确白名单业务表的同快照只读数量/等值SHA256，全部不变；范围为原14表加客户评论、日历头/修订、手工登记、人工恢复确认和设计配置表，不等同整库或外部真实性证明。V1–V14前后独立只读schema check均执行0次迁移，未自动迁移。随后重新取得6个真实OIDC会话，故障前10/后12实际HTTP全部按API0.20契约验证。

两条合成手工原登记（其一已独立人工确认，其一尚无确认）在重启后均原样回读；原手工创建键仍返回原登记快照，原人工确认键仍返回原确认，原创建重放不重置当前恢复。登记与确认的原成功审计各仍1条/原ID，资产和未知实际恢复时间null保持。56Python进程/证据保护测试及39契约通过；Bash/Node语法检查通过。场景结束自动清理本次拥有组件，随后正常启动新的持续QA栈。

这是已提交后终止API，不是事务进行中被杀，不覆盖PG/WAL、Worker、OSS、浏览器/390px或原指定服务器故障验收。V1–V14和API0.20未改，其他合同阻塞仍保留，不把此场景标为所有恢复验收或128项完成。

见[真实V14 API崩溃证据](../evidence/niko-api-v14-crash-recovery-20261003.json)。

### 2026-10-03 07:18 niko联调执行通道恢复

旧交互QA会话在exec传输断连后不可恢复。使用已有标准启动脚本重新启动获准回环PG17.11/Keycloak26.2.4/API固定工件；未新建库/身份凭据、未迁移、未手工移除PG PID文件。就绪、完整根875（862执行含264真实PG）及6OIDC/79日期列表HTTP通过，当前API0.23。原服务器B01/OSS B02/DOM B03/其他业务组件B05不因此解除。


## 2026-10-03 补充维保V15结构切片

独立V15执行1次，重复/只读/旧目标0次；新增可空起始/供应商及明确声明标记，旧默认写入在历史已声明后失败关闭，原列/回执/审计20表投影指纹不变。10新真实PG结构、17迁移策略、60Python保护、根935（922执行含283真实PG）/49契约通过；结构测试回滚后补充声明0行。V1–V15冻结，下一迁移V16+。仓储/HTTP兼容/UI、供应商核验/送达/DOM仍未完成，不标M3-10或128项DONE。见[结构与边界](../manual-warranty-details.md)。


## 2026-10-03 独立维保API0.26

10控制器/根962（949执行含295真实PG）/51契约/554Web/类型构建和97真实HTTP通过；1合成资产11修订11维护审计。旧体保留补充字段/显式清空/旧新原回执/当前资源重放授权/两角色混合并发200+409/10+1历史分页有证。旧基础DTO不增列，供应商及覆盖未核验；页面/DOM、隐患送达导出仍待。V1–V15不改、不自动迁移，M3-10与128项非DONE。见[维护说明](../manual-warranty-details.md)。
# 2026-10-03 本地复核补充

当前 Windows 可通过固定主机键 SSH 访问原指定组件；实读 PostgreSQL17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z，PG已有 `und-x-icu`。原项目 `mop_test` 从V6逐功能升级到V15，运行/迁移角色及其他项目隔离不变，未改服务生命周期或全局配置。每功能1次、重复0次、最终只读0次；见[本地迁移记录](../local-schema-continuation.md)与[结构证据](../evidence/local-schema-v15-20261003.json)。用途是承接新增工时、资料、评论、日历和人工事件等真实业务验证，结构通过本身不等于这些功能联调完成。云端B01、OSS受控认证及外部监控/通知缺口仍保留其原范围。

## 2026-10-03 本地应用与完整存储流水线

原指定PG17.9/Keycloak26.2.4/MinIO的API、照片Worker、导入Worker均已实际启动；295PG、6MinIO、六OIDC及97维保HTTP已验。原文件在真实PG受理、MinIO存储、独立解析子进程解析；本项目回环存储断链进入RETRY_WAIT、恢复后同一任务完成。用途与状态已从仅组件可访问推进到上述明确范围的真实业务验证；Redis/RabbitMQ业务集成、监控/通知/堡垒机、OSS受控凭据仍沿用缺口，未擅装替代组件。就绪仍不虚称Worker活性，任务执行证据另见[本地记录](../local-runtime-validation-20261003.md)。

2026-10-03收尾：原组件不变，最新API0.27与两个Worker已重新就绪、六新OIDC及72容量HTTP通过；目录现21项，无新组件、迁移或业务写入。完整各批边界见[本地交付](../local-delivery-20261003.md)。


## 2026-10-03 容量场景V16结构切片

仅niko项目库独立V16执行1次、重复/只读/旧目标0次，V1–V16冻结。12真实PG结构/18迁移策略/63Python、根1004（991执行含307PG）/53契约通过；20表旧值指纹不变、测试回滚后场景表0行。Windows显式入口已补，49PowerShell保护待CI，本机未执行；原服务器V16未验。仓储/公开保存/UI与完整M10-7/128项非DONE。见[结构边界](../capacity-estimate-core.md)。


## 2026-10-03 niko联系人V17结构

仅现有niko PG17.11项目库独立V17 1/0/check0，21既有表全列不变；12新增真实PG结构、根1088报告/1075执行含334PG、19迁移策略/82Python/57契约通过。结构角色范围和0残留见[联系人边界](../customer-contacts.md)。没有新组件或账号，原服务器V17、联系人仓储/API/页面/并发与渠道送达未验；下一迁移V18+。


2026-10-03 18:44 niko重建实例：既有PG显式V18/V19联系人导入结构分别执行1/重复0/check0；V18真实功能错误用V19独立修复，原SQL不改。最终13真实runtime结构测试通过/全部事务回滚，新队列与结果0行；现有API0.31正确health/ready端点200。没有启用实际导入Worker、对象存储或通知组件，原服务器迁移仍未执行。详见niko-customer-contact-import-schema证据。


2026-10-03 19:39 niko联系人Worker：新增专用contact-imports JAR复用现有受限PG，无新账号/对象存储/通知组件。实际正常处理与在途SIGKILL(-9)/重启fence2恢复通过，3次JVM均只管理本次直接启动且已核验身份的进程；API、PG、KC未重启。所有验证Worker最终停止，6合成联系人归档、作业终态保留；不声明持续生产服务或公开202接口就绪。


2026-10-03 20:18 niko重建实例：自有隔离PG/KC/API正常关闭再以固定API0.32重启，六真实OIDC会话刷新；公开202/creator回读与专用Worker合计388HTTP通过，19作业119自建联系人已归档。仅复用现有受限PG账号，V1–V19不变；Worker验证后正常停止，ready仅证明结构/访问，worker_liveness=null。五无关队列/身份表指纹未变，原服务器/OSS/通知/浏览器B03仍未补齐。


## 2026-10-03 21:40 niko监控组件候选：待单次明确授权

为补齐B05真实监控缺口，已提出仅niko的Zabbix 7.0 LTS Server/Web/Agent及必需依赖候选，并请求批准新建独立测试库、受限数据库用户、测试管理账号和只读API账号。范围为本项目测试实例、回环监听、随机凭据仅私有目录；原服务器和MOP库不改。此时尚未收到本次明确确认，**未安装、未创建身份/令牌/数据库、未启动采集**；此前PG/KC许可不自动扩张到新Zabbix账号。相关步骤保持待许可，其余代码/测试继续。

只读预检：当前自己的执行环境为Debian13.6/x86_64；command -v未发现docker/php/nginx，可见dpkg-deb。未取得可用的cgroup memory.max，因此资源容量未确认，不能把宿主机内存推成测试容量。只有公开官方文档/包索引查询，未下载或运行新组件。官方[生命周期](https://www.zabbix.com/life_cycle_and_release_policy)列7.0 LTS完整支持至2027-06-30、有限支持至2029-06-30；官方[7.0要求](https://www.zabbix.com/documentation/7.0/en/manual/installation/requirements)和[Debian包目录](https://repo.zabbix.com/zabbix/7.0/debian/pool/main/z/zabbix/)可作后续版本/依赖核验来源。具体补丁包、签名/摘要、资源与真实兼容性尚待核验，不使用不稳定版本或关闭TLS/主机信任。

该候选仅开发测试，不替代客户Q03生产许可/版本决定、Q06阈值矩阵或原服务器环境。未知监控仍未知；不会把当前阈值数学诊断当采集引擎。

## 2026-10-04 01:18 B03现有Chromium启动探测

本次实际发现 `/usr/bin/chromium`，版本154.0.8037.57（Debian13）；不再将“未取得Playwright下载包”解释为系统没有浏览器。使用已安装Playwright1.62.1，要求Chromium沙箱开启，先普通启动、再通过执行权限审核路径启动空白页；两次均在process_singleton的socket创建阶段返回Operation not permitted，页面打开0。第二次使用项目私有XDG目录，仍失败；ptrace也被禁止。

未关闭沙箱、修改防火墙/端口/系统权限、开放公网、传入凭据或安装新软件。原云浏览器localhost阻塞、官方包下载失败保留为各自历史，不能混为新探测结果。B03仍阻塞当前真实DOM/移动视口验收；继续代码和隔离测试。证据见 [启动结果](../evidence/niko-browser-startup-blocker-20261004.json)。

## 2026-10-04 原指定库V19接续

当前Windows通过固定主机键SSH实读PG17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z；原mop_test从V15逐功能升至V19。四步各1次、重复0次、最终只读0次，58迁移/27进程保护通过；备份存本机忽略目录，未实际恢复。用途为容量草稿、联系人修订与批量作业，结构通过不代表本次实际Worker/页面已验；完整组件回归和浏览器结果后续单列。未改变已有共享组件生命周期/端口/全局设置。证据：[原库V19](../evidence/local-schema-v19-20261004.json)。

## 2026-10-04 Windows联系人Worker真实运行

已在原PG17.9/Keycloak26.2.4/MinIO上启动API/Web及照片、资产导入、联系人三个专用Worker；六真实OIDC及35租户GET通过。联系人实际批量2行成功、五角色重启后原键回放及新1行成功均有证；恢复脚本保留原明确启用的联系人角色，不擅自启用未登记Worker。原远程组件不重启、不新增账号/权限；未知监控/配额/OSS/通知及PoC仍待。完整根399PG+6MinIO，后续导出库另6只读PG，边界见[运行验证](../local-contact-validation-20261004.md)。

## 2026-10-04 V20异步导出结构独立执行

V20首次候选CASE语法失败完整回滚，只读确认仍V19/无新表函数，修正未应用资源后独立执行1次、重复0/只读0；28既有表等值指纹不变。V1–V20冻结，16新增PG结构回滚测试和2迁移策略通过，根1522报告1509执行含434PG/13跳过/6MinIO排除，73契约。无应用导出受理/Worker/OSS/下载/通知，原服务器V20未执行；见docs/asset-export-jobs.md，M3-7非DONE。

## 2026-10-04 导出排队任务正常停启与新JVM回放

受理提交a38c84a的CI全过。实际停止/重启仅本runner PG/KC/API，PG启动时间改变；26排队任务、0领取及原幂等/审计四表相同。新JVM读回26任务/回放26原收据，接受回调0、四表再比相同；随后6真实OIDC登录通过。首次不必要的Flyway历史runtime读取被拒，移除该探针且未提权。9新增纯门禁/122Python/73契约通过；不当Worker崩溃或OSS完成。见docs/asset-export-jobs.md。

## 2026-10-04 独立导出准备Worker实际进程与故障恢复

新独立模块只处理显式单job，不启动其他队列/Web/迁移/存储客户端。4个真实自有JVM验证：实际XLSX准备、源备注变化后保留原文件、新PG审计等待点SIGKILL(-9)无半文件、过期后fence2恢复；原收据QUEUEDv0保留，7无关表等值指纹不变，Worker全部结束。15Java/9新增Python门禁，根1604报告1591执行含482PG/13跳过/6MinIO排除，131Python/73契约通过。仍未发布或调用OSS/下载/通知，见docs/export-preparation-worker.md，M3-7非DONE。

## 2026-10-04 09:14 巡检模板页面B03复核

niko现有/usr/bin/chromium版本154.0.8037.57、Playwright1.62.1，保留chromiumSandbox=true启动仍因Operation not permitted/EPERM失败，应用页打开0。没有更换组件、禁用沙箱、变更系统/网络权限或创建新任务。新的巡检模板页面17项SFC/jsdom与此前99真实客户端HTTP分别成立，不能合并宣称真实浏览器验收；桌面/移动视口仍NOT_RUN。见[当前阻塞证据](../evidence/niko-inspection-page-browser-blocker-20261004.json)，继续不依赖浏览器的开发。

## 2026-10-04 09:41 既有联调栈在终端断连后恢复

QA73514传输恢复失败并返回Unknown process id；发起的新根日志不存在。源码/私有配置/既有PG数据仍在。只用原启动脚本恢复同一隔离组件，未手动移除PID、初始化数据库、创建凭据或迁移；PG17.11日志报告非正常关闭后的WAL自动恢复并ready，KC/API重新可用。新QA2689，V21只读check执行迁移0，6OIDC刷新及40HTTP核对36已知归档模板通过；不是故障前后全库字节等值证明，终端丢失原因未查明。见[恢复证据](../evidence/niko-inspection-session-recovery-http-20261004.json)，B01原服务器、B02 OSS、B03真实浏览器等不因此解除。

## 2026-10-04 10:11 niko V22独立结构验证

复用QA2689的原PG17.11，独立CLI显式V22一次/重复0/只读0，33旧表迁移窗口等值；18新增真实受限账号结构测试回滚，根1699执行含533真实PG通过。没有新账号/服务/原服务器操作，API0.35保持、不自动迁移；执行单表结构不代表巡检业务/API或B01/B02/B03依赖通过，见[实际证据](../evidence/niko-inspection-worksheet-schema-20261004.json)。

## 2026-10-04 10:44既有组件普通重启与执行单HTTP

QA2689主动正常退出后，以原脚本/原数据/原凭据重启现有PG17.11/KC26.2.4/API0.36，QA62871活动；PG日志有正常关库/再启动，9张巡检/审计/收据/身份表重启前后等值（75个旧执行单）。V22只读check、迁移0，6真实OIDC会话及124执行单HTTP通过，HTTP窗口28其他表等值。未做强制崩溃/真实存储/浏览器、未改原测试服务器。首次另一Web执行器丢失后在QA62871重跑1059Web通过；组件证据边界见[API验证](../evidence/niko-inspection-worksheet-api-20261004.json)。

## 2026-10-04 本地原指定库V22接续

本轮固定主机键SSH实读原PG17.9的mop_test为V19；应用停止后逐功能执行V20导出队列、V21模板修订、V22人工执行单，各1次/重复0次/最终只读0次。41张既有业务表行数与全行摘要保持一致，备份仅存本机忽略目录且未实际恢复。复用原运行/迁移角色，无新服务、账号、全局权限或端口变更。结构可用不等于导出发布、模板批准、执行单公开API或季度报告可用；Worker/HTTP/DOM另行留证。见[独立结构证据](../evidence/local-schema-v22-20261004.json)。


2026-10-04 Windows导出准备复用原PG17.9和mop_test_runtime，14次JVM尝试未新增账号/服务或迁移。最终自有项目源表临时锁已释放，所有验证JVM退出；XLSX和进程恢复范围见[证据](../evidence/local-export-preparation-worker-20261004.json)。发布、OSS、下载和通知仍未接通。
## 2026-10-04 11:34 执行单页面B03复核

niko自身Chromium154.0.8037.57配Playwright1.62.1、chromiumSandbox=true仍在启动时EPERM/Operation not permitted，应用页打开0；未降低沙箱/系统安全或建立代理。30输入/SFC模拟DOM测试与11实际来源HTTP分别通过，不能合并宣称浏览器/移动视觉验收。见[当前探测](../evidence/niko-worksheet-page-browser-probe-20261004.json)及[页面边界](../evidence/niko-inspection-worksheet-page-20261004.json)，继续不依赖该入口的开发。


2026-10-04 API0.36/V22本地五角色已连接原PG17.9/KC26.2.4/MinIO，ready实测8.253秒后200；只调项目启动等待窗口，未改共享组件。100行专用Windows联系人作业真实完成并归档，见[实际证据](../evidence/local-contact-maximum-worker-20261004.json)。通知/身份绑定/OSS保持原门禁。


2026-10-04 原指定PG17.9/KC26.2.4巡检模板、执行单API/请求模块及真实Edge桌面/390px已验；B03只对这两页面明确场景解除，物理设备、证据/通知/季度发布和客户确认仍待。没有新组件/账号；见[本地记录](../local-inspection-validation-20261004.md)。


2026-10-04 最新来源捕获只复用原PG17.9，13真实只读捕获/3选择/20清单已验，未新增接口/表/组件/身份；原V22及API0.36保持。Windows符号链接创建权限缺失只影响1条路径保护测试前置条件，未修改宿主机权限；该条明确SKIP，见[最终证据](../evidence/local-inspection-source-capture-20261004.json)。


## 2026-10-04 niko来源追溯新UI的浏览器路径

13:43 UTC已实际发现托管Chromium/CDP运行，按既有Vite回环5173/真实API8080打开来源页面；导航返回ERR_BLOCKED_BY_CLIENT，随后观测被浏览器URL安全策略拒绝。应用页打开0，新来源面板真实DOM/移动/WeChat验收NOT_RUN；不将此原因归为机器人检测，也不改协议、代理、端口或沙箱绕过。既有原Windows28项模板/执行单检查保留为旧页面独立证据，不能代替新增面板。仅关闭此轮拥有的Vite进程，PG/Keycloak/API不停止。详见docs/evidence/niko-inspection-source-ui-browser-blocker-20261004.json；其余19真实只读HTTP继续验证，无新组件/凭据/迁移。


## 2026-10-04 niko来源集合V23结构

只在已批准niko-local-test PG17.11执行独立V23一次、重复/检查0；48既有项目表迁移前后完整行hash一致。新增两张来源集合表及三触发器，已有runtime仅追加/读取，无新账号、组件、端口或代理。17真实runtime结构测试通过；原指定服务器本轮未操作，API/Worker不自动迁移。这里只证明冻结来源结构，不代表季度业务规则、正式报告/存储/附件发布，见docs/evidence/niko-inspection-source-collection-schema-20261004.json。


## 2026-10-04 niko来源集合API0.38

继续复用既有PG17.11/Keycloak26.2.4，普通拥有者守卫重启到固定API0.38工件，11表完整行在再次OIDC前等值（含88既有集合）。6登录/73HTTP通过，3新增合成集合留存且全未发布，父1执行单/1模板已归档；未改端口、身份、网络、存储或迁移。只读V23结构检查0执行。原服务器未操作，新集合UI及B03浏览器路径仍未验，季度规则待确认；见../evidence/niko-inspection-source-collection-api-20261004.json。


## 2026-10-04 集合页面组件真实HTTP边界

显式jsdom/Vue组件场景通过11次真实PG/OIDC HTTP（API0.38），不是浏览器导航或CSS/手机验收；未安装新组件、启动Vite、改地址/代理或绕过原URL策略。首轮在途等待断言失败造成未知请求，只读PG及原key/body恢复后保留该1集合，独立r2另1集合；无新父对象/账号/迁移/存储/删除。B03仍未解除，原Windows旧页面证据不能替代。见../evidence/niko-inspection-source-collection-page-20261004.json。


## 2026-10-04 V25变更报备草稿独立结构

继续复用niko-local-test既有PG17.11，独立CLI --apply-change-notice-drafts实际1次、重复/--check/旧--apply-backup-plan-drafts均0；53张旧表完整行指纹前后相同。新增三表四invoker守卫，没有新组件、账号、凭据、端口或代理；仅现有runtime具有所需草稿追加/读取和头版本更新权限。21真实PG结构测试通过，API0.39固定包未重启、Worker未启动，不自动迁移。审批/P3为null、执行/抑制false；原服务器、OSS、审批/SSH/通知联调均未因此完成。V1—V25冻结，后续修正新增V26，详见docs/evidence/niko-change-notice-schema-20261004.json。


## 2026-10-04 API0.40未审核报备草稿真实HTTP

复用既有PG17.11/KC26.2.4与V25；独立schema只读检查0迁移。旧拥有者runner正常退出后新固定API工件5cdbfa7620e72eb8caabf335a064e5138ee70d45ed6cda6fc492a7d066d36823启动，56张旧表在6OIDC新登录前完整行指纹等值；不称崩溃/故障恢复。83HTTP成功完成，2个自有合成草稿已归档，18修订/36设备引用/18成功审计（2创建），无pending原请求。审批/P3未知、不执行或抑制；原服务器/OSS未操作、无新账号或代理，API/Worker不自动迁移。当前completed控制.local/niko-test/change-notice-api-control.json禁止盲目重跑；需新场景才能再验证。见docs/evidence/niko-change-notice-api-20261004.json。


## 2026-10-04 API0.41本人工时跨工单只读查询

既有niko PG17.11/KC26.2.4/V25不变，普通拥有者守卫重启到固定工件14ebbdd17037c706ee9094f0a8e70fc1e36f1727046dcedc98b8caa0a52a8872；56旧表在6OIDC新登录前完整行等值，无DDL/新账号/凭据/端口/代理。47真实HTTP含旧接口7次合成数据准备写入，新/my/worklogs只GET；成功查询审计序号不变。3父登记/3本人工时/4修订留存，父状态/SLA仍未知；不是团队或经营口径。原服务器与OSS未操作，真实浏览器未验。详见docs/evidence/niko-own-worklog-api-20261004.json。

2026-10-14 长文本界面与列表打磨复用 lan-test-20261008 真实API，本机回环生产前端预览；实际ready接口HTTP200、ready=true、production_ready=false。没有新增基础组件或依赖、迁移、服务重启、业务写入及替代响应。本轮组件版本未重新盘点，不沿用历史版本冒充本轮证据，功能和UI证据见[长文本交互](../reference-ux-textareas-20261014.md)。

2026-10-14 reference-ux前端已按用户授权发布到lan-test-20261008。14个项目服务运行；Web切换之外13服务ID/镜像/启动时间/重启计数保持一致，API、Worker和基础组件均未更新，无迁移。公开91静态文件摘要一致，真实入口11项检查/12个页面视口、ready=true验证通过，production_ready=false保留。见[最终快照](../evidence/reference-ux-20261014/components-final.json)及[部署](../evidence/reference-ux-20261014/frontend-deployment.json)。
# 2026-10-23 production线入口安全探测

新增只读生产预检查工具，复用已登记业务入口，不创建替代组件。2026-10-23 06:56 UTC真实TLS证书校验失败，HTTP未满足永久同源HTTPS跳转；影响为无法经可信HTTPS核对生产依赖／会话属性，后续HTTPS检查NOT_RUN。正式TLS资源／信任链和数据库隔离批准仍待客户；候选为现有代理的获批证书配置，未获批、未实施。没有安装／停止服务、迁移、业务写入；本轮未重新盘点组件版本。见[运行与验证说明](../production-preflight.md)及[证据](../evidence/production-preflight-20261023/public-entry.json)。

附件扫描：现有登记未提供本轮可核验的获批扫描端点、版本、签名新鲜度和扫描策略；状态UNVERIFIED_NOT_ENABLED。影响为不能执行真实附件扫描／隔离发布验收。候选ClamD的有界流式适配代码已具备13项纯协议测试，不表示已批准ClamAV、已发现服务或已完成联调。未探测扫描端口、未安装或启动替代组件、未提交实际附件。未来优先盘点指定服务器已有能力，确认受控访问和隔离权限后接入；[接口和缺口](../attachment-scan-adapter.md)。

恢复演练：本线尚无获核验的隔离恢复命名空间、受限恢复权限、源备份／水位及演练窗口；状态NOT_RUN。已实现只读归档摘要和演练观测验证器，14项纯单元通过，不表示恢复可用。候选为指定测试服务器独立受限演练空间，须核验授权／容量后使用；未创建或恢复任何数据库，禁止以共享mop_test或实际租户替代。见[恢复验证工具](../recovery-drill-verifier.md)。

凭据轮换：尚无绑定到本线的获批KMS／凭据后端、受限scope及激活／撤销授权；状态BACKEND_NOT_CONFIGURED，影响为不能执行实际轮换和消费者重载验证。协调接口及16项纯单元已完成；优先复用指定环境已有秘密管理能力，产品／版本及原子代数语义仍需盘点，未把任何候选当作获批组件，未修改真实凭据。见[轮换协调接口](../credential-rotation-coordinator.md)。

# 2026-10-15 前端验收规范复用盘点

通过既有固定主机密钥SSH只读盘点指定LAN测试环境：14个项目服务运行，PostgreSQL17.9、Nginx1.31.6、Redis7.4.9、RabbitMQ4.3.2、MinIO RELEASE.2025-09-07T16-13-09Z、Zabbix7.0.31。API ready=true、production_ready=false。本轮本地前端经回环预览访问既有项目API；无新增组件、迁移、服务生命周期或全局配置变更。组件在线不表示业务/生产验收。见[盘点证据](../evidence/frontend-acceptance-20261015/components.json)。
# 2026-10-15 前端交互第二轮复核

本轮只读SSH观察指定LAN环境14个项目服务运行，API只读就绪检查ready=true、production_ready=false。API及各组件镜像标识与当日上一轮盘点一致，Web启动时间已变化，未据此声称整个环境生命周期不变。新增导航/网络提示仅在本地隔离前端验证，继续通过回环预览访问既有API；没有新增基础组件、业务写入、迁移或服务变更。版本证据仍引用[当日盘点](../evidence/frontend-acceptance-20261015/components.json)，本轮范围与结果见[第二轮记录](../frontend-usability-round2-20261015.md)。
# 2026-10-15 多分辨率布局验收

本轮继续复用指定LAN测试API，只读SSH盘点14个项目服务运行；就绪检查ready=true、production_ready=false。没有新增基础组件、账号、迁移或服务生命周期操作。管理员、客户和工程师测试会话仅用于页面读取与未提交的UI输入，业务写入0。组件版本沿用当日已盘点版本，未由样式修改升级；本轮布局、弹层及测试证据见[多分辨率记录](../responsive-layout-20261015.md)。
# 2026-10-15 登录页精简与背景资源

继续复用指定LAN测试API，真实项目账号登录及退出本人会话验证通过；只读盘点14个项目服务运行，ready=true、production_ready=false。本轮只改登录页样式、装饰背景与预加载，不增加基础组件，不执行迁移或服务变更。生成背景作为本地静态WebP资源发布，不接入第三方运行时图片服务。见[登录页记录](../login-redesign-20261015.md)。

## 2026-10-15 工单详情参考图联调

复用指定 lan-test-20261008 的真实工单/内部备注/客户评论/本人工时 API 与 PostgreSQL 17.9（V32），只读盘点 14 个项目容器运行、ready=true、production_ready=false。新增一个明确标记的虚构报障及其测试记录，无组件安装、迁移、服务重启或全局设置修改。本地仅构建和预览 Web；浏览器转发原始真实 API 响应，非 fixture 联调。正式流程/团队工时等属于尚未实现的业务接口，不登记为数据库故障或已完成能力。[组件证据](../evidence/ticket-detail-reference-20261015/components.json) · [运行与边界](../ticket-detail-reference-20261015.md)。


## 2026-10-19 V41 自有云端季度报告隔离验证

本轮使用用户授权的 dot 自有可丢弃 PostgreSQL 17.9 / ICU 76.1，仅监听回环；API 使用项目本地临时合成身份，Email/WeCom 禁用，不继承供应商秘密。未访问用户电脑或原指定服务器。首次 V41 应用及 JDBC DateStyle 测试设置失败原样保留；修正测试为 ISO, DMY 后另建全新数据库，独立 V1–V41、报告 apply/repeat/check=1/0/0、16 报告 PG +21 审批 PG、43 审批 HTTP +90 报告 HTTP 全部通过。两次临时数据库和凭据均已清理，成功场景 API 已停止；未向外发送消息。当前不是常驻环境，不表示原服务器迁移、浏览器视觉、OSS/PDF 或生产批准完成。见 [V41 独立验证](../evidence/inspection-quarterly-report-v41-20261019/README.md)。

## 2026-10-21 dot 临时 PostgreSQL 实验环境

从当前系统配置的 Debian 签名快照源下载并仅解包 postgresql-17、postgresql-client-17、libpq5 17.11-0+deb13u1，没有全局安装、系统服务或网络设置变更。临时 PostgreSQL 只监听 127.0.0.1，独立 mop_test 库与受限 migrator/runtime 身份用后清理；未连接原服务器。V1–V45 及重复/只读验证通过。实际 collation version 为 153.128；此环境不能冒充历史 PG17.9 或生产环境，带数据业务/HTTP 尚待验证。证据见 docs/evidence/integration-pg-20261021/attempt-1/。

## 2026-10-23 合同布局发布前盘点

指定 LAN 服务器只读盘点：SSH认证成功、14个既有服务运行、真实依赖ready、数据库V50。此次导航与首页仅复用现有API及采样，不新增组件；正式首页聚合、服务区间和磁盘排行来源仍未实现/未接入，影响和状态见 [合同布局记录](../contract-layout-20261023.md)。不以布局恢复宣称这些来源已完成联调。

最终发布 `effbe274` 后复核14服务真实ready；V50／0迁移，7基础组件生命周期和私有配置均未变化。未新增、安装或升级组件，未启用采集或远程执行。结果见 [发布与验后证据](../contract-layout-20261023.md)。

## 2026-10-23 备份任务发现只读验证

备份并行检出经固定主机密钥 SSH 隧道复用指定 LAN PostgreSQL 17.9（Debian 17.9-1.pgdg12+1），受限 mop_test_runtime 账号实际验证现有身份/设备读取、任务查询、分页及失效权限版本拒绝。业务写入、设备操作、迁移和部署均 0；未创建或恢复旧测试身份。补查 Flyway 管理历史被权限拒绝，保留证据且不扩大权限；本轮不重新宣称管理历史已核验。没有已核验的非空隔离备份任务/真实 OSS/KMS/采集完成证明，本轮相应验证 NOT_RUN，替代候选无批准；不另建组件或造数据。运行 grant 配置仍未启用。详见 [发现契约](../backup-task-discovery-20261023.md) 与 [验证记录](../evidence/backup-task-discovery-20261023/validation.json)。

## 2026-10-23 监控复发关联会话只读核查

本会话经既有固定主机密钥 SSH 复用指定 `lan-test-20261008` PostgreSQL 17.9/V50。受限运行角色 READ ONLY 事务通过工单去重配置读取及不存在范围的候选查询，业务写入/迁移/部署均 0。两租户中实际业务租户之外有效成员为 0，没有本轮核验的隔离写入命名空间；影响为新复发关联的真实写事务、并发关单和回滚验收未运行。候选为在已有 PostgreSQL 内核验独立项目测试范围及受限身份，状态 UNVERIFIED，不表示已批准或已创建；不恢复历史测试身份、不另建组件或使用 fixture 代替。见 [监控证据](../evidence/monitoring-recurrence-20261023.json)。

## 2026-10-23 资产导入事务验证

资产并行检出通过固定SSH主机信任、原主目录Git忽略私有资料及临时回环隧道复用 `lan-test-20261008` 既有 PostgreSQL 17.9，核验容器项目归属和运行状态。`environment.lock` 内用全新UUID隔离租户进行外层回滚测试，业务SQL使用既有受限运行角色；结束确认租户不存在。未安装/升级/停服/改变端口/执行迁移，未恢复旧测试身份或向真实业务租户写合成数据。

用途是33列来源导入的授权、引用、配额检查、事务回滚和回执；原始来源种子明确为隔离合成解析产物，未将对象存储/独立解析Worker作为本批已联调组件。真提交并发、崩溃恢复、500行60秒和正式HTTP/浏览器TC仍未验，无新增替代组件候选。详细边界和复验结果见 [资产导入说明](../cmdb-import-execution-20261023.md)、[资产进度](../parallel/assets-progress.md)。

2026-10-23 备份后处理调度增量只复用以上 PostgreSQL 原日志和原运行租约，不增加组件。代码已提供显式授权装配与有界轮询；当前 grant/真实证明来源/对象与密钥适配未启用，非空实库写调度和真实 OSS/KMS 仍 NOT_RUN。指定 PG 17.9 仅复验发现查询，不把组件连接或合成故障测试当成真实备份成功。见 [调度契约](../backup-task-scheduler-20261023.md) 与 [集成证据](../evidence/backup-task-scheduler-20261023/integration.json)。
## 2026-10-23 独立演示账号组件复用

按用户明确授权，在指定PG17.9/V50中建立三个全新DEMO租户及独立账号，复用既有MinIO导出存储、Zabbix和站内通知仓储。只新增3个项目Zabbix host和18个simple-check item，目标均为指定测试服务器192.168.1.215自身；原四台实际设备及其绑定保持，无触发器/外发动作/全局权限变更。监控数据为测试服务器实际采样，其他虚构业务均明确DEMO。

API/Web的演示渠道/页面输入支持已发布c3e1c35b，原有12个其他容器生命周期不变，14服务实际ready，0迁移。邮件/企微演示连接为停用配置，主动拒绝外发及连接检查；测试账号不取得真实租户渠道。未接入的CPU/内存、正式报告发布与备份执行不补造成功。43页、真实HTTP、数据双向隔离和回退说明见 [演示交付记录](../demo-account-20261023.md)。
