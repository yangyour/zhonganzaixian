# 防火墙 CPU、内存持续监控

2026-10-14用户更新防火墙密码并要求记录、继续完善。新密码仅保存于 Git 忽略的本机私有凭据及服务器受限 Worker 配置，未写入源码、报告或全局记忆。原 PASSWORD_EXPIRED 已解除；[首次读取](evidence/firewall-password-updated-read-20261014.json)取得 AF8.0.106.4646、CPU 12%、内存 58%。这些是该次读数，不是固定值或持续健康结论。

本批已将 CPU、内存接入指定测试站的持续采集、真实历史和页面：[基础网络与资源监控](http://192.168.1.215:10010/network-monitoring)。原四台设备的 24 项 ICMP/TCP 采集继续使用原组；防火墙新增两个资源指标和一个内部采集状态项。SCP、两台交换机的厂商详细指标仍按各自权限/资料边界继续，不因本批成功视为全部接入。

## 采集及持久化

新增项目 Worker `mop-lan-af-collector`，复用服务器已有 Java 镜像、Zabbix 7.0.31 和其 PostgreSQL 历史库，不新增数据库/缓存或替代基础组件。每 60 秒用真实 `AfClient` 登录、核对预期固件、读取 CPU/内存并注销；设备策略、账号及权限没有被修改。

Zabbix 独立组 `MOP AF API metrics test 20261014` 中只有本批资源主机，三个 trapper 项为 `mop.af.cpu[192.169.11.1]`、`mop.af.memory[192.169.11.1]`、`mop.af.state[192.169.11.1]`。原基础监控主机/组/项无需改动。测试历史配置 7 天、trends 关闭；这是测试配置，不代表正式留存或 SLA 规则。

Worker 使用单独身份，仅允许 `history.push`；MOP 使用另一个只读身份，仅允许 `host.get/item.get/history.get`。两者仅对该独立组授权，原基础监控读取身份仍只见原四台。trapper 允许来源固定为实际 Zabbix Web 容器地址、网关及回环地址，没有开放网段。依据 [Zabbix 7.0 history.push](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/history/push)；实际角色及拒绝行为见[权限范围证据](evidence/af-resource-access-scope-20261014.json)。

CPU/内存采用实际返回的百分比；三项按同一采集接收时刻发布。设备未提供原始采样时间，因此 `source_sampled_at=null`，历史含义明确为 `COLLECTOR_RECEIPT_TIME`。写入逐项检查回执；部分失败不算发布成功，也不重放结果未知的写入。

失败只发布采集状态，不填零。认证失败、密码过期、权限不足、固件变化或未确认的认证结果暂停设备重试，修复私有配置后显式重启本项目 Worker；暂停期间仍发布失败状态。已认证后的暂时读取失败可在后续周期重试。发布失败保留失败健康状态，不宣称持久化成功。Worker 有受限资源、自动重启及独立健康检查。

本批仍使用已授权 `TEST_INSECURE`，仅限指定设备连接；不修改系统全局证书信任。页面明确显示临时跳过证书校验，`tls_trust_verified=false`。真实认证和数据读取不替代证书身份确认。

## API 与页面

公开契约更新至 0.49.0，新增：

- `GET /api/v1/network-monitoring/{assetId}/resources`：当前值、采集状态、接收时刻及测试证书模式。
- `GET /api/v1/network-monitoring/{assetId}/resources/history?metric=cpu|memory&window=HOUR_1|HOURS_24`：最多 2000 个真实历史点，不补零，不声明整窗完整；超过 90 秒的采样间隔在资源图表中断开。

仅接受已有 MOP 资产标识；当前租户 `assets:read`、资产归属/IP、明确资源绑定均在来源读取前核验，读后再次核验成员与资产。跨租户/未绑定统一 404，外部 IP/item/tenant 参数及重复参数拒绝。已绑定防火墙的基础视图标为 `PARTIAL_API`，其含义是部分厂商指标接入，不是全设备健康。

快照必须同时满足三项配置一致、采样时刻一致、采集状态 OK 且年龄不超过 180 秒，才显示 FRESH。失败、缺失、超时或未来时间均不返回旧数值；启用后的来源、映射或新鲜样本不可用令 ready 返回 503。历史保留已确认的原始值和精度。180 秒是本次技术新鲜度界限，不是离线或 SLA 规则。

监控页增加 CPU/内存卡片、独立历史按钮、1h/24h 切换及来源时间说明，桌面四台并列、窄屏依次排列。切换设备时资源指标回到可用的基础指标，切换租户或请求失败清除旧值。页面每 30 秒刷新，后台采集不依赖浏览器或本机 SSH 通道。主资产台账的正式接入/健康状态、CPU 汇总投影以及告警规则未被本功能自动改写。

## 验证与部署

- 67 项相关 Java 测试通过：采集 Worker 4、发布回执 6、AF 协议 12、资源读取/租户授权 16、基础监控 24、运行模式 5。新增 26 项，均是隔离规则测试，与真实设备验证分开统计。
- 全部 1944 项 Web 测试通过；新增资源读取和页面场景 9 项。类型检查、正式构建、静态体积/安全策略检查通过，133 项既有契约检查及生成一致性通过。后续仅调整提示文字与四列布局，重新构建并完成实际页面检查。
- [候选 HTTP](evidence/af-resource-http-18012-20261014.json)与[部署后入口 HTTP](evidence/af-resource-http-10010-20261014.json)各 40 次检查，覆盖四台基础数据、防火墙当前值、两指标两窗口、多角色与跨租户 404。最少资源历史点分别为 19 和 24；点数不是测试数。两阶段各 10 份实际响应通过 [候选契约校验](evidence/af-resource-http-schema-18012-20261014.json)、[公开入口契约校验](evidence/af-resource-http-schema-10010-20261014.json)。
- [真实失败与恢复](evidence/af-resource-fault-recovery-20261014.json)：只向独立项目状态项注入采集失败，候选快照隐藏旧值、ready=503；真实 Worker 后续样本恢复 FRESH/ready。设备未被停机或修改。浏览器自身的错误场景另为单浏览器响应替换，不混作设备故障证据。
- [真实 Edge 页面检查](evidence/af-resource-ui-20261014/result.json)：14 项、5 张截图，1440×1000 与 390×844；当前值、四种历史组合、切换/刷新、异常恢复、手机布局、其他租户空列表通过，pageerror=0。手机宽度为仿真，不是物理手机验收。
- [独立采集 Worker 最终工件](evidence/af-resource-worker-final-20261014.json)与[API/Web 最终部署](evidence/af-resource-deployment-20261014.json)均留有 SHA256；87 个 Web 文件逐一核对。[初始页面部署](evidence/af-resource-deployment-initial-20261014.json)保留。公开入口已 ready，候选 API 已清理。
- [最初盘点](evidence/af-resource-components-before-20261014.json)为 13 个原服务，最终新增一个项目采集 Worker，共 [14 服务](evidence/af-resource-components-final-20261014.json)。原服务中仅 API/Web 更新，其他 11 个镜像/启动时间/重启次数保持；PG17.9/V31，无 SQL 迁移。原基础 24 项与新资源采集各有独立数据验证。

联调初期误用只支持数组结果的读取解码器处理写入回执；修正为对象回执后，又核实 Zabbix 7.0.31 实际成功回执的 itemid 为整数而文档样例为字符串，现按明确 item ID 集合接受二者并拒绝浮点/错 ID/部分失败。失败阶段健康状态保持未就绪；已实际落库的真实采样没有删除或改写。前端初次导航脚本仍按旧菜单定位导致超时，改用现有“资源与监控”分组后完成最终检查。

客户仍需确认正式证书与业务规则；SCP 读取权限/源范围、交换机详细接入及其他合同功能继续保留门禁。正式 128 项合同状态不因这两个资源指标上线而标为 DONE。

收尾期间同一工作区的并行任务完成导出链接恢复与Impeccable界面发布（eb15359、7f92e91、c848235）。本批保留其最终Web目录与构建，在最新公开界面上重新执行全部14项监控页面检查并更新5张截图；没有回滚其样式或配置。当前API与采集Worker仍为本批已验证工件，最新Web实际指针及复验见[最终运行版本](evidence/af-resource-live-release-final-20261014.json)。API/Web在窗口内均有项目更新，不能把其全部变更归因本功能；原其他11服务保持一致。
