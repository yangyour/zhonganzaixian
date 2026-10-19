# 企业微信通知与登录接入开发

本轮按用户要求在本地开发消息通知和企业微信登录，使用已提供的应用资料以及指定 LAN 测试服务器。Secret 继续仅从本机 `deploy/private/wecom-app.json` 读取。用户已授权向指定成员发送一条固定内容的 MOP 联调测试消息，该授权不扩大为任意通知或群发。

## 接口客户端

新增 `WeComClient` 和 `WeComCredentials`，提供应用读取、成员校验、文本消息接口与网页授权码换成员身份。接口固定为企业微信官方 HTTPS 源，保留证书校验，禁止重定向，限制响应大小；错误仅输出分类与供应商错误码，不回显密钥、access_token、原始响应或含密请求地址。

应用凭证按接口返回的 `expires_in` 缓存并提前刷新，同进程并发刷新串行化。仅明确的 token 失效响应允许刷新后重试一次；消息发送超时、网络断开或无法确认响应时保留 UNKNOWN，不自动重复发送。接口 errcode=0 记录为 ACCEPTED，不代表用户已收到或已读。

## 指定环境真实检查

2026-10-19，本机直连能取得应用凭证，但读取应用与成员返回 60020。通过指定 LAN 测试服务器执行相同只读请求成功。`tools/wecom-dev-tunnel.py` 因此提供本机回环 HTTP CONNECT，仅允许 `qyapi.weixin.qq.com:443`，通过固定主机信任 SSH 使用指定服务器出口；同时可将该服务器现有 PostgreSQL 回环端口转发到本机 15438。不启动或替换数据库、缓存、消息或监控组件。

真实 Java 客户端经该 SSH 出口校验：应用 ID 与配置匹配，应用未停用，指定成员存在且状态为已激活。客户端 13 项隔离协议/输入校验测试通过；真实检查与单元替身结果分别记录在[客户端证据](evidence/wecom-adapter-20261019.json)。当前步骤没有发送消息。

## 本地检查入口

启动 `py -3 tools/wecom-dev-tunnel.py`，通过环境变量设置 `MOP_WECOM_CONFIG_FILE` 为本机私有文件绝对路径、`MOP_WECOM_PROXY_URL` 为 `http://127.0.0.1:18889`。可选 `MOP_WECOM_PROBE_USER_ID` 指定只读校验成员，运行 Java 入口 `cn.mop.adapters.wecom.WeComProbeMain`。进程只输出脱敏检查结果；隧道中断时请求失败，不回退到替身。

消息耐久派发、用户绑定、登录 API 与页面继续在此基础上接线，尚不据客户端检查标记完整 D11 验收通过。
