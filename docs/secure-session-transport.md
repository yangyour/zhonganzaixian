# 显式安全会话传输模式

本功能是可启用的API传输保护，未改变 `remote_test`、迁移守卫或 `production_ready=false`。只有 `mop.security.require-https=true` 时注册最高优先级的过滤器；默认关闭，不改变当前服务器入口。此模式支持应用直接终止TLS，尚不接受代理传入的安全性声明。

启动前按有效配置校验：

| 设置 | 要求 |
| --- | --- |
| `server.ssl.enabled` | true，密钥／证书由受控私有配置提供 |
| `server.ssl.enabled-protocols` | 非空，只有TLSv1.2／TLSv1.3 |
| `server.servlet.session.cookie.name` | MOP_SESSION |
| `server.servlet.session.cookie.secure`／`http-only` | true |
| `server.servlet.session.cookie.same-site`／`path` | strict／`/` |
| `server.servlet.session.cookie.domain`／`max-age` | domain为空，max-age不设置 |
| `server.servlet.session.tracking-modes` | 只有cookie，禁止URL会话跟踪 |
| `server.forward-headers-strategy` | none，禁止framework/native隐式信任 |

缺项或弱化项直接启动失败；不读取密钥内容，不自动申请证书。现有 `ProfilePolicy` 仍拒绝production或混合profile。开启前必须先完成实际TLS资源与入口方案核验；此模式的配置通过不是证书校验或生产批准。代理TLS终止需要后续独立实现可信代理边界，不能通过放开转发头绕过。

API新增条件行为：显式开启时，所有非可信TLS请求在认证、CSRF生成和应用业务处理前返回HTTP400、`error.code=HTTPS_REQUIRED`，统一错误封套、trace_id和no-store。唯一明文例外是无Cookie／Authorization／现有会话的回环GET或HEAD `/api/v1/health/live`、`/api/v1/health/ready`；外部地址、POST、URL会话参数均拒绝。TLS请求继续既有CSRF／租户授权／审计流程并添加一年HSTS。传输层拒绝不创建会话、不中转明文凭据、不调用业务写入或审计仓储。契约附加项为 `contracts/local-api.openapi.json` 的 `x-mop-secure-session-transport`，既有API路径不变。

本地退出、OIDC退出完成及用户改密使用统一删除Cookie函数：host-only、根路径、HttpOnly、Strict、Max-Age=0；可信TLS请求上的删除Cookie保留Secure，不信任原始转发头。

验证（2026-10-23）：Maven API reactor编译成功，`SecureSessionTransportTest` 9项、`SessionServiceSecurityTest` 2项、`ProjectIdentitySecurityTest` 7项共18项实际通过。附带选择的历史 `FixtureSecurityAndWorkflowTest` 13项全部跳过，不能算通过。第一次测试把Mock请求scheme设成https，Spring Mock同时将isSecure视为true，导致1项断言失败；修正用例只伪造转发头后重跑通过，未弱化实际过滤器。API生成契约一致性通过。未启动TLS服务器／数据库、未跑真实登录／浏览器、未部署，正式TC为NOT_RUN；当前公网证书失败见[预检查证据](evidence/production-preflight-20261023/public-entry.json)。

```powershell
mvn.cmd -B -pl server/api -am test '-Dtest=SecureSessionTransportTest,SessionServiceSecurityTest,ProjectIdentitySecurityTest,FixtureSecurityAndWorkflowTest' '-Dsurefire.failIfNoSpecifiedTests=false'
node tools/build-api-contract.mjs --check
```
