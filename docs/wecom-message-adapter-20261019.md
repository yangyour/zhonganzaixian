# D11 企业微信单次消息提交适配器（2026-10-19）

## 本批可执行范围

新增 `WeComMessageClient`、`WeComHttpsTransport` 和 `ChannelSubmissionResult`，实现显式配置、单租户、单接收人、一次应用文本消息提交及安全结果归类。这是可注入、可测试的服务端适配器；没有注册 Spring Bean、启动 Worker、读取真实私有文件或发送任何真实消息。配置缺失继续有明确结果，不静默跳过或虚构成功。

- 配置含租户 UUID、AgentID、当前 access_token 的秘密引用、启用标记、连接和完整响应超时。所有未确认配置可以为 null；未完整配置禁止发送，不填客户业务默认值。
- `TokenResolver` 接收租户、AgentID、引用，必须返回该应用当前有效 token。CorpSecret、CorpID 鉴权获取、按 `expires_in` 缓存和并发刷新不在本批；不能把静态归档的 Secret 当成 access_token。未验证现有真实凭据。
- 单次 `Command` 绑定租户/投递/请求 UUID，接收一个已验证的应用成员 ID 及已授权文本。禁止 `@all`、`|` 扩展、部门/标签广播；UTF-8 文本最多 2048 字节，不截断内容。成员绑定及当前授权必须由调用层验证，字符串格式通过不证明真实账号存在。
- 生产 transport 固定使用 `https://qyapi.weixin.qq.com/cgi-bin/message/send`，使用 JVM 标准 TLS 校验、不跟随重定向、不提供任意 URL、无手工重试。明确完整响应 deadline（包括慢响应体）及 64 KiB 响应上限；超时取消异步请求不证明供应商没有执行。
- 请求使用 Jackson 序列化；响应严格拒绝重复 JSON 字段、尾随内容、缺失/错误类型 errcode 等歧义。HTTP 非 200、超大/畸形/空响应、异常、超时、连接丢失统一 UNKNOWN，不根据 HTTP 429/5xx 推断“肯定未发送”。中断标记保留。
- 200 + 整数 errcode=0 只表示 ACCEPTED；单接收人的 invaliduser/unlicenseduser 明确匹配该成员时为 REJECTED，其他不一致字段为 UNKNOWN。明确非零供应商 errcode 为 REJECTED；此类结果仍没有自动重试权限。
- 不保留或回显 access_token、接收账号、正文、errmsg、msgid 或原始响应。公开诊断字符串脱敏，网络异常丢弃原始 message/cause。生产运行还必须禁用可能记录带 token URL 的 JDK HTTP 调试日志、代理请求日志；适配器本身不写日志。

协议参考：[企业微信官方应用消息接口](https://developer.work.weixin.qq.com/document/path/90236)。本轮研究工具无法打开官方页面；当前实现以仓库 D11 协议说明和严格隔离协议测试为依据，未完成官方在线兼容核验或真实接口验收。

## 结果与既有领域模型

`ChannelSubmissionResult` 仅保存不透明相关 ID、枚举结果/原因及观察时间。调用 `apply` 前必须已有持久化的 `DeliveryReceiptState.start` 尝试；检查租户/投递、WECHAT_WORK 渠道、请求 ID、版本和时间，跨租户/投递为 404。

- ACCEPTED → 既有 ACCEPTED 回执，送达数仍为 0。
- REJECTED → FAILED 回执，需上层明确死信处置才可新请求重放。
- UNKNOWN、NOT_SUBMITTED → 保留现有 UNKNOWN 尝试，不制造 FAILED 或 DELIVERED，也不解除未知结果的重放门禁。上层应单独持久化结果原因，不能只保留领域投影而丢失 NOT_SUBMITTED 区别。
- 本地 receiptId 应由耐久观察记录提供；同 ID/同事实重放复用既有幂等规则。该 ID 不是伪造供应商回执 ID。
- 本批没有生成 DELIVERED 的路径。已有独立送达证据不会被后到的提交受理撤销。

本对象的 `apply` 目前仅服务企微提交；不是其他渠道的通用回调验签器。纯模型不能证明数据库 CAS、跨进程去重或实际恰好一次；请勿把内存结果当作耐久完成。

## 必须由后续集成补齐

1. 通道配置/授权成员绑定、有效矩阵与偏好路由的持久化和 API/UI；不修改 V26 草稿或将 V34 外部 NOT_CONFIGURED 硬编码直接改为成功。
2. token 生命周期管理：按租户/企业/应用隔离秘密引用、获取 token、使用返回 expires_in、安全提前刷新及并发锁；真实凭据有效性单独验收。
3. 独立 Worker 的耐久投递/尝试/结果记录、原子领取租约、发送前提交、CAS/幂等、UNKNOWN 隔离核查、明确预算/退避、死信权限与回放审计。未完成这些步骤前不要把适配器接入自动发送。
4. 回调验签、防重放、真实请求映射与供应商送达语义。不能因为拿到 msgid 或 errcode=0 就计入 delivered。
5. 其他外部渠道、短信额度、H5/OAuth 及合同 P95/100 条真实样本。Q14/Q17/Q18 正式业务值继续单独配置和验收。

本批未改共享 API 契约、能力目录、根 POM/package、共享进度文档或任何迁移；不要求本批发布配置或凭据到服务器。

## 本批验证

在 dot 云工作树，使用 `/tmp/dot-tools/jdk/bin/javac --release 17`、JUnit Console 和项目 Spring Boot 3.5.16 管理的 Jackson（core/databind 2.21.4、annotations 2.21）运行隔离测试：

- `WeComMessageClientTest`：53 个参数展开后用例
- `ChannelSubmissionResultTest`：7 个用例
- `DeliveryReceiptStateTest`：原有 24 个回归
- 合计 84，通过 84，失败/错误/跳过 0

编译范围是上述三个新增生产类、既有 DomainException/DeliveryReceiptState 及三个测试类；不是完整 Maven、数据库、HTTP 真实服务或浏览器集成验收。所有响应/令牌/成员标识均为明确合成数据。没有真实外部连接、获取 token、发送消息、迁移、部署、推送或用户电脑操作。
