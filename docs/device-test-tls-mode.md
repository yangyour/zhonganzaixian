# 设备 API 可配置测试证书模式

2026-10-14用户要求在暂不配置信任证书的情况下实现监测接入。已将临时例外从之前的单次 Python 探测落实为实际 Java 客户端配置，并发布检查工件到指定测试服务器验证。详细资源数据仍受设备账号状态和权限限制；本批未宣称 CPU/内存等已经采集成功。

## 实现与内部接口

新增 `DeviceHttpsTransport`，复用项目已有 OkHttp 5.3.2。`SYSTEM`、`CA_FILE` 维持证书/名称验证，`TEST_INSECURE` 只在当前客户端实例跳过链及名称校验；未修改 JVM 全局信任库、默认 SSLContext、主机名校验器或系统属性。临时模式严格限定两台已授权地址及 443，SCP 配置另外限定 SCP 地址。

SCP 的原 `ScpClient` 已接入该传输，完整资源接口仍要求显式租户/资产/资源池/项目范围。新增连接检查构造方式用于范围未知时验证账号与版本，禁止在这个客户端中读取资源页。离线 `ScpPreparationCheck` 明确输出当前 TLS 模式和是否启用证书验证，仍不将配置齐全当作联调成功。

新增 AF 读取适配器：账号登录、会话复用、版本、CPU 当前百分比、内存百分比、结束注销；依据用户 AF PDF 第 51–54、3163、3165–3169 页。兼容已观察到的 JSON/octet-stream 及文档 text/html 内容类型，但内容仍须严格 JSON。HTTP 200 中的 1010 密码过期、13 权限不足以及嵌套错误均阻止数据成功；原始错误正文、密码、token 不进入异常或公共报告。缺失指标不填零，未知采样时间不填当前时间。

请求不使用系统代理、不跟随重定向，限定认证/注销 POST，10 秒总请求时限和 2 MiB 响应上限。禁用连接重试，并将认证请求体标为一次发送、抑制 503 `Retry-After: 0` 隐式重发；这一额外路径来自[OkHttp 官方重试实现](https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)核对，并用当前依赖与隔离用例验证。

新增 `tools/check-device-api.py` 读取本机私有资料，校验项目归属及固定 SSH 主机信任后，复用现有 Java 镜像运行已构建 JAR。私有数据仅通过 SSH 标准输入进入一次性检查容器；不保存服务器凭据文件。运行说明和入口见[配置文档](../deploy/device-api/README.md)。原公开 API 0.48、Web 和 V1–V31 不变。

## 真实设备验证

最终工件对每个目标各执行一组“严格→测试模式→严格”，使用实际 Java 适配器，没有模拟设备响应：

|目标|严格模式（前/后）|测试模式|当前资源状态|
|---|---|---|---|
|SCP 192.168.1.179|两次均 TLS_NOT_VERIFIED|密码认证成功，版本读取 ACCESS_DENIED|权限仍不足；未读到版本/资源，未假填资源池或项目 ID|
|AF 192.169.11.1|两次均 TLS_NOT_VERIFIED|登录成功，版本读取 PASSWORD_EXPIRED，注销成功|密码仍过期；CPU/内存调用未越过失败门禁|

证据：[SCP 最终工件](evidence/scp-test-tls-runtime-20261014.json)、[AF 最终工件](evidence/firewall-test-tls-runtime-20261014.json)。同一进程恢复严格模式后仍失败，证明测试例外没有污染后续严格客户端。首次工件验证也取得相同结果，见 [SCP 初始轮](evidence/scp-test-tls-runtime-initial-20261014.json)、[AF 初始轮](evidence/firewall-test-tls-runtime-initial-20261014.json)；补充禁止 503 隐式重试与部分读取失败的退出码守卫后才执行最终轮，未静默重试认证。

本批发布的是独立验证工件与可复用适配器，尚未把厂商资源接入公开页面、持久采样或后台轮询。四台 24 项基础网络采集继续由既有 Zabbix 执行。客户仍需更新 AF 过期密码、补齐 SCP 对应只读权限及资源范围；证书身份仍未验证，正式信任配置继续待处理。

## 分层验证

- 94 项相关 Java 测试通过：AF 12、公共传输 9、SCP 45、检查状态 4、基础监控 24；其中本次新增 27 项。覆盖精确目标限制、严格/临时实例隔离、源错误脱敏、密码过期、禁止重放、范围门禁和缺失指标。
- 3 项私有配置工具测试通过：跨设备凭据拒绝、测试例外授权要求、检查不改原配置或填造资源范围。
- JAR 打包通过，真实部署的每个工件均上传后核对 SHA256；同一进程严格前后检查、实际密码认证和明确业务失败结果分别保留，不按“绿灯”伪造结果。
- [本批最初 12 项盘点](evidence/device-tls-components-before-20261014.json)及[包含已存在导出队列的 13 项盘点](evidence/device-tls-components-before-final-20261014.json)表明当前基线已为 PG17.9/V31。本次不沿用旧 V30 基线，不部署或回滚其他功能。最终盘点与比较见 [最终环境](evidence/device-tls-components-final-20261014.json)和[验证汇总](evidence/device-test-tls-validation-20261014.json)。

正式合同 128 项仍按原追踪保持 NOT_RUN；账号认证成功、临时越过 TLS 错误及模块测试均不等于完整设备监测验收。

133项现有API契约测试及生成一致性检查通过；[基础网络回读](evidence/device-test-tls-basic-network-20261014.json)确认四台24项仍为新鲜采样。检查入口在“版本成功、指标失败”时仍返回非零，避免部分成功误报就绪。离线[私有配置检查](evidence/scp-test-tls-config-20261014.json)仅剩DISABLED与资源范围待补，不发HTTP。
