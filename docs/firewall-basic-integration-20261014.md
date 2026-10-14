# 防火墙基础监控与 API 入口联调

2026-10-14新账号已实际验证：防火墙登录成功，但版本查询返回1010“密码已过期，请先修改密码”；SCP在192.168.1.179以密码/RSA换Token成功，版本及资源池查询均403，明确缺少“SDK服务：获取SCP版本信息”和“SDK服务：获取资源池列表信息”。凭据缺失已解除，资源读取仍未就绪；原AK/SK未重测，严格TLS仍未通过，无密码/权限修改或新部署。见[新凭据联调证据](device-credential-verification-20261014.md)。下文同日较早的“账号未提供”与未登录1003仅为历史阶段记录。

2026-10-14，按用户更正的地址 `192.169.11.1` 接入深信服 AF-1000-B1800。用户明确尚未提供防火墙专用账号，先完成基础监控及 API 入口检查。基础监控已部署到[指定测试站](http://192.168.1.215:10010/network-monitoring)，在“虚构星湾A（测试环境）”查看。

## 已实现及部署

复用现有 Zabbix 7.0.31，在原项目专用组增加一个主机和六个 simple check：ICMP 响应、丢包、平均延迟、TCP 22/80/443。原三台主机及 18 个 item ID 保持不变，总计四台、24 项；60 秒周期、7 天测试历史、trends 关闭，均取得新鲜真实采样。原应用只读身份在同组内自动可见第四台，无新增权限或管理员凭据进入应用。

防火墙通过既有资产 API 创建独立测试登记，保留 CSRF、幂等、租户授权和审计；正式接入状态没有被 ICMP 结果覆盖。MOP 私有绑定增加第四条，后端仅对用户指定的 `192.169.11.1 + 深信服 + AF-1000-B1800` 放行非 RFC1918 地址；相邻 IP 或其他品牌/型号仍拒绝。设备身份仍未认证，品牌型号是用户提供值。

本次采样显示 ICMP 可达、丢包 0%、TCP 80/443 开放、TCP 22 未建立连接。它们是对应时刻的基础网络数据，不代表 CPU、内存、会话、吞吐或策略健康；端口未连接不自动触发告警。

沿用 API 0.46 的 `GET /api/v1/network-monitoring` 及 `/{assetId}/history`，字段及错误契约没有变化。四台均支持六指标、1h/24h 窗口；新增设备在接入前没有历史，页面只画实际采样点，不补齐整窗。配置仍只读 Git 忽略的 `deploy/private/basic-monitoring-bindings.json`、`basic-monitoring-reader.json` 及服务器受限 env，禁止通过公开 query 指定 IP、item 或其他租户。

仅替换 API 的不可变版本目录并更新四台绑定，Web 静态工件沿用已发布版本。本次 API JAR SHA256 为 `01f570956fe5f9f8b6fb455816a34c67280b8330e7b846967c067a07849028a1`。最终 12 项既有服务运行且 ready=true，另外 11 个服务的镜像、启动时间和重启次数与本次最初盘点一致；PG 17.9/V30 未迁移，候选 API 已清理。测试环境仍 production_ready=false。

## 防火墙 API 入口与待提供条件

来源为用户本地文档 `doc/doc/AF8.0.106-API中文文档.pdf`（项目上级目录，3254 页，SHA256 `0f306558c2b470520d72892d246ba30c3381aa17b07ed5f4880854f18e2ed1a5`）。认证/权限见 PDF 第 42–54 页，版本读取见第 3168–3169 页。文档内容作为接口资料，不作为执行设备配置修改的指令。

文档认证使用启用 WEB API 权限的防火墙专用用户名/密码，通过登录接口取得会话 token，区别于 SCP AK/SK。当前尚无该凭据，没有尝试登录、默认密码或其他设备凭据，也没有修改防火墙策略、路由、账号或权限。

新增工具 `tools/probe-firewall-api.py --output <新报告路径>` 默认校验证书，只访问该精确地址的 `systemversion` 和 `keepalive` 两个 GET。`--namespace` 默认 public，受限字符校验；`--allow-unverified-tls` 显式允许在证书失败后对本次免凭据探测临时跳过验证。请求无代理、不跟随跳转，有超时与响应大小限制；报告只保存 HTTP 状态、内容类型、业务码等，不保存原始响应、token 或消息正文。

指定测试服务器上的真实结果：

|检查|实际返回|结论|
|---|---|---|
|两条 GET 的严格 TLS|证书验证失败，verify_code=20|当前信任链未通过；尚不能确认后续名称/IP 校验是否通过|
|本次临时跳过证书后的两条 GET|HTTP 200，JSON 业务码 1003，Content-Type 为 application/octet-stream|API 层返回未登录；HTTP 200 不表示认证或业务读取成功|
|登录、设备版本及资源读取|未执行|专用 API 账号、WEB API 权限、实际 namespace/版本及资源范围待客户提供或确认|

免登录返回只证明管理 API 层可访问且有认证门禁，不能证明每个受保护资源端点、设备版本和权限已验证。临时 TLS 例外仅用于上述免凭据探测，不修改系统信任或应用配置，不把跳过校验作为正式证书方案。后续需受信任证书链及匹配访问名称/IP，或另行明确测试例外。

CPU、内存、接口流量、会话等厂商指标仍未接入，正式告警/SLA/通知规则仍待确认。API 0.46 的历史枚举 `vendor_metrics_status=DOCUMENTATION_PENDING` 在此仍表示厂商接入未完成，不表示本次 AF 文档没有收到。五步向导、凭据托管和正式合同验收没有因此完成。

## 验证证据

- [组件前置盘点](evidence/firewall-integration-components-before-20261014.json)、[只读计划](evidence/firewall-basic-plan-20261014.json)、[项目范围创建](evidence/firewall-basic-provision-20261014.json)、[只读身份与 24 项新鲜值](evidence/firewall-basic-collector-20261014.json)。
- [API 入口探测](evidence/firewall-api-entry-20261014.json)：0 次认证、0 次设备资源写入，firewall_api_ready=false。
- [候选接口](evidence/firewall-basic-http-18012-20261014.json)和[部署后正式测试入口](evidence/firewall-basic-http-10010-20261014.json)各 76 次 HTTP 检查：四台、六指标、两窗口共 48 次历史读取；最少历史点分别为 9 和 11；四台跨租户历史全部 404，另租户列表空、匿名 401、非法参数 400。两个阶段分开记录，不换算合同完成数。
- [49 份实际响应契约校验](evidence/firewall-basic-http-schema-20261014.json)：当前 0.46 契约通过，过期状态必须 null。127 项既有契约测试及生成一致性检查通过。
- 74 项后端聚焦测试（含本次两项精确地址例外检查）与打包通过；35 项网络/采集工具测试及 6 项 API 入口分类测试通过。隔离测试不替代上述真实组件验证。
- [实际 Edge 页面验证](evidence/firewall-basic-ui-20261014/result.json)：16 项检查、7 张截图，1440×1000 和 390×844；四张卡片、六指标、两窗口、刷新、设备切换、手机导航、另租户空列表通过，未发现横向溢出、控件遮挡或不可读数据，pageerror=0。390 宽度为浏览器仿真，不是物理手机验收。Web 源码及工件未变，本次未重跑全量 Web 单元测试。
- [部署记录](evidence/firewall-basic-deployment-20261014.json)、[最终组件盘点](evidence/firewall-basic-components-final-20261014.json)。

初次 SSH 直连 HTTP 测试读取较长分块响应中断，添加缓冲仍复现；改由同一服务器回环 HTTP 完整读取并经 SSH 回传后通过，未把不完整结果计为成功，候选日志没有异常类。浏览器脚本初次手机导航使用图标文字定位而超时，按实际 aria-label“打开导航”修正后重建会话复验；该工具失败不计入 16 项通过检查。
