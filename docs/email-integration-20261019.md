# 邮件通知接入与运行说明

2026-10-19，按用户提供并已验证的企业邮箱接入 MOP 邮件通知通道。用户随后明确选择将收件邮箱绑定到现有 `JiangFaMing` 平台账号。绑定已通过实际管理页面保存，停用/恢复后最终保持启用；它是管理员明确配置，不冒充邮箱归属验证或身份登录绑定。

## 已实现的行为

- 管理入口为“系统管理 → 邮件通知”，路由 `/email`。页面提供真实连接检查、固定内容/固定收件人的测试发送确认框、允许范围内的账号收件地址绑定、停用与最近100条发送记录。
- 项目通过 Angus/Jakarta Mail 使用 SMTP 465 隐式 TLS，校验服务器证书及主机名，只允许 TLS1.2/1.3；连接、读、写均设置10秒超时。主机、发件账号和收件白名单均来自服务端私有配置，请求不能覆盖主机、任意收件人或测试内容。
- `JiangFaMing` 可以在“工单通知”中明确将本人站内通知发送到本人绑定邮箱。来源消息、租户、发送人及绑定必须一致；跨租户、其他人的通知和其他发件账号记录统一404。管理员管理目标账号时复核其全部既有管理范围。
- 先以202耐久入队，提交一次 `PROCESSING` 领取后才调用 SMTP。发送前复核当前成员权限、启用状态、绑定版本和收件允许范围。重复请求使用原幂等键；来源消息与绑定组合永久去重。写操作沿用项目会话、CSRF、当前成员授权、乐观锁及审计。
- SMTP成功提交记录为 `ACCEPTED`，存储本项目生成的 `Message-ID` 和实际 SMTP 状态码，不伪装供应商送达回执。网络中断、提交结果不明确或领取进程中断记录为 `UNKNOWN`，不自动重发。明确拒绝记录为 `FAILED`；撤权/绑定变化记录为 `CANCELLED`。
- 自动业务通知保持关闭。现有Q14渠道×级别矩阵、Q18正式供应商规格/费用、通知偏好生效、退避/死信和其他渠道的完整验收仍分别登记。IMAP此前仅做连接验证，本次不读取或导入邮箱邮件内容。

## API与持久化

实际API契约已更新为0.59.0，生成器为 `tools/email-contract.mjs`，输出仍为 `contracts/local-api.openapi.json`。

|方法与路径（均位于 `/api/v1`）|行为|
|---|---|
|`GET /email/status`|当前授权租户的脱敏配置与最近检查|
|`POST /email/check`|真实SMTP认证及PG结构/权限检查，不发送邮件|
|`GET /email/bindings`、`PUT /email/bindings/{userId}`|管理员显式绑定/停用白名单收件地址，写入要求当前版本|
|`GET /my/email-binding`|读取本人绑定，未配置时返回null|
|`POST /email/test-messages`|仅接受 `expected_version=0`，固定测试收件人/主题/正文，202仅表示耐久入队|
|`POST /my/notifications/{id}/email`|本人来源通知转发，要求显式绑定及发送开关|
|`GET /email/deliveries`、`GET /email/deliveries/{id}`|本人记录；具备管理读取权限者可读本租户当前发件账号台账|

V39新增 `mop_email_binding`、`mop_email_delivery` 与不可变/版本/状态守卫，受限运行账号只有所需列的写权限。2026-10-19在指定 LAN PG17.9项目库独立应用一次，重复与只读复核均0次；原V1—V38历史保持，92张既有业务表完整行摘要不变。数据库备份仅保存在本机忽略目录，恢复演练未执行。V39 SHA256为 `3d1b4cd557b6c367b2a36e9f49a2154d8a925a8afe249c103cd59373f32a6dea`，已应用SQL保持字节冻结，后续修正使用V40或更高。见[迁移证据](evidence/email-migration-20261019.json)。

## 私有配置与秘密

本机 `deploy/private/email-runtime.json` 保存非秘密连接配置；口令保留在既有 `deploy/private/mail-credential.dpapi`，由当前Windows用户解密后，仅通过子进程环境变量 `MOP_EMAIL_PASSWORD` 交给本地API。配置、代码、记录和截图均不含明文口令，整个目录保持Git忽略。DPAPI文件不复制到Linux运行环境。

服务端配置字段如下；以下地址均为格式示例，不能作为真实联调或已批准接收人：

```json
{
  "host": "smtp.exmail.qq.com",
  "port": 465,
  "username": "sender@example.test",
  "sender": "sender@example.test",
  "sender_name": "MOP 运维服务门户",
  "test_recipient": "recipient@example.test",
  "allowed_recipients": ["recipient@example.test"]
}
```

Linux部署使用两个0400、归应用UID10001的只读秘密文件：配置文件包含 `password_file` 的绝对秘密引用，另一个文件保存口令。`MOP_EMAIL_CONFIG_FILE` 指向配置文件，不将口令写入Compose环境、应用工件或证据。口令只从环境变量或指定私有文件读取；两种来源同时存在会拒绝启动。

启动开关为 `MOP_EMAIL_ENABLED=true`、明确的 `MOP_EMAIL_TENANT_ID`，以及独立的 `MOP_EMAIL_DISPATCH_ENABLED`。未启用的通道不创建替身；已启用通道的PG或SMTP认证失败时，实际就绪检查返回503及脱敏原因。

## 本地复现与测试站发布

本机只启动业务API、前端及SSH转发，复用指定LAN数据库。先保留当前用户的私有配置，再在项目根目录执行：

```powershell
mvn.cmd -q -pl server/api -am dependency:build-classpath '-Dmdep.outputFile=target/email-classpath.txt'
py -3.11 tools/wecom-dev-tunnel.py --pg-port 15439 --proxy-port 18890
# 另一个终端；只做真实加密认证检查，不发送邮件：
py -3.11 tools/run-email-dev.py --probe
# 启动人工通知API；此开关只投递用户明确操作形成的台账，不生成自动业务通知：
py -3.11 tools/run-email-dev.py --dispatch
```

`run-email-dev.py` 默认API为127.0.0.1:18039、数据库转发15439；启动不执行迁移。前端将 `MOP_API_PROXY_TARGET` 指向该API后，可用既有Vite开发入口访问。新的classes应先按正常项目流程测试和构建。

测试站使用既有 `tools/update-lan-test-server.py`。新增 `--only-api-web` 只切换API/Web，其他应用及基础组件全部列为生命周期保护对象。首次额外指定 `--enable-email-manual --email-tenant-id <现有项目测试租户UUID>`，从本机加密凭据部署秘密文件。开启前按当前租户及发件账号检查QUEUED/PROCESSING积压，非空时停止，不自行取消或投递旧消息。候选API强制关闭邮件和企业微信发送分发，验证真实依赖后清理。常规后续更新保留现有配置，不再重复传启用参数；回退沿用既有私有事务流程。

## 验证与边界

- 26项邮件客户端/服务/控制器隔离测试通过，覆盖输入注入、固定收件范围、证书设置、未知结果不重试、权限和原请求恢复；不把协议替身当真实供应商证据。
- 6项指定PG真实测试通过，覆盖版本冲突/不可删除、原幂等收据、并发只领取一次、进程中断转UNKNOWN、来源与绑定去重/变更取消，以及接收人、发件账号、租户隔离。只写明确标识的 `.test` 合成记录，不连接SMTP。见[PG证据](evidence/email-persistence-20261019.json)。
- 全量Java报告3140项，实际执行2221项、跳过919项，无失败；跳过部分不计为联调成功。另有2228项Web测试、140项契约、20项部署保护、类型检查、生产构建及静态清单检查通过。新增契约保留全部原接口值，只增加邮件接口和版本。
- 实际Java客户端SMTP加密认证成功；真实页面完成连接检查、绑定保存及停用/恢复、桌面1440×1000与手机390×844检查，页面无横向溢出，宽表格仅在自身容器滚动。测试邮件确认框在两个尺寸均取消，没有形成发送请求。CSRF缺失403、会话缺失401、任意收件人/缺幂等键/查询覆盖400、只读用户管理访问403、其他租户404均实际验证。见[HTTP/UI证据](evidence/email-http-ui-20261019.json)。
- 本次没有发送实际邮件，真实SMTP提交、收件箱投递、邮件内容正确性及端到端送达仍为NOT_RUN；不可用合成ACCEPTED记录替代。完整D11及128项合同验收未完成。

供应商组件盘点见[组件记录](environment/component-inventory.md)。测试站实际发布与复核将在下方独立补充，避免把本地连接验证当作已发布。
