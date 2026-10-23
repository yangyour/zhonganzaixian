# 生产启用前检查工具

`tools/production_preflight.py` 是实际可运行的只读检查器，输出可机读证据，不切换应用 profile、不迁移、不部署。当前应用仍仅支持 `remote_test`／明确单元夹具，生产配置与数据库隔离正式装配尚未批准。检查器不把任何一串批准引用、`ready=true` 或绿色单元测试当作上线许可。

## 配置与调用契约

Python 3.10+，仅标准库。以 `deploy/examples/production-preflight.json` 为结构，在 Git 忽略的 `.local/` 中填写已确认值；未确认值保留 null。所有字段必须存在，未知字段、重复 JSON 键、非标准数字、带密码／查询串／路径的 URL 被拒绝。`public_origin` 只接受 HTTPS 标准 443 端口，当前工具不覆盖非标准入口。

```powershell
py -3 tools/production_preflight.py --config .local/production-preflight.json --output .local/preflight-config.json
py -3 tools/production_preflight.py --config .local/production-preflight.json --output .local/preflight-live.json --probe
```

默认仅验证配置结构。`--probe` 才会对声明的域名直接访问（不继承代理、无认证、无重定向跟随）；每次连接超时10秒、响应上限256KiB。只允许 TLS握手、HTTPS GET `/api/v1/health/ready`、HTTPS GET `/api/v1/auth/csrf`、HTTP HEAD `/api/v1/health/ready`。CSRF GET可能创建匿名短期会话；不登录、不持久保存 Cookie 或令牌、不写租户业务数据。可用 `--ca-file` 指向私有 CA 信任包，仍强制主机名／证书链校验及 TLS1.2+，不提供跳过校验选项。

检查内容：证书链及主机名、有效期大于30天、真实健康响应 `ready` 和 `durable` 为布尔 true、运行环境是否明确 production、HSTS至少一年、单个 host-only 会话 Cookie的 Secure／HttpOnly／Strict／根路径和非持久属性、HTTP301/308保持同域同路径跳转。TLS失败后HTTPS检查为NOT_RUN；不会降级明文读取依赖就绪。异常、响应原文、Cookie、token、配置引用原文不进报告；错误统一使用稳定原因码。

报告 `checks[].status` 为 PASS／FAIL／BLOCKED／NOT_RUN。`DECLARED`仅证明字段已声明，不证明制品、部署或批准真实性。批准引用始终为BLOCKED并要求人工核对；总结果保持BLOCKED、`production_ready=false`、`formal_tc_status=NOT_RUN`。这是证据收集接口，不是激活接口；后续经客户批准的生产装配／签收不能靠修改此布尔值实现。

退出码：2=已完成检查但存在生产门禁（当前正常结果），1=输入／输出不可用。输出采用排他创建，不覆盖旧证据；路径必须位于已有目录。无HTTP API契约变更。

## 本次验证

2026-10-23：17项纯验证／合成传输单元测试通过，覆盖错误响应、证书失败、过期边界、敏感信息隔离、Cookie、重定向及证据防覆盖。命令：`py -3 -m unittest discover -s tools -p test_production_preflight.py -v`。

同日06:56 UTC（02:56 EDT）对已有业务域名 `mop.all-online.com.cn` 进行真实无登录探测：证书校验失败，HTTP没有满足永久同源HTTPS跳转；HTTPS依赖就绪、Cookie、HSTS均未越过失败的TLS门禁重测。证据：[public-entry.json](evidence/production-preflight-20261023/public-entry.json)。探测目标是既有测试发布入口，不是新生产环境；组件版本未重新盘点。本轮未启动本地服务、未读写数据库、未安装组件、未部署、未运行正式TC。

现有入口须由获授权人员提供有效名称／信任链的证书与TLS终止配置；正式数据库隔离、组件许可及生产批准仍待客户。候选为现有反向代理上的获批证书，不表示已批准或已实施。工具完成不代表生产profile、RLS、凭据轮换、附件扫描、灾备和完整合同验收完成。
