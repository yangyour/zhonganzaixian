# MOP 基础网络代维平台

依据仓库内 `doc/development/` 开发。当前本地应用复用用户指定服务器的真实测试组件：PostgreSQL 持久仓储与 Keycloak OIDC 登录。资产、配置草稿/修订、报障登记/操作/历史/outbox意图、幂等记录及审计保存在远程独立测试数据库；正式工单处理规则尚未落实，持久登记不表示业务闭环完成。

全局要求见 [AGENTS.md](AGENTS.md)；已发现及缺失组件见 [组件清单](docs/environment/component-inventory.md)。静态原型仍保留为参考，默认运行和集成测试不再使用内存 fixture。

## 连接资料与首次准备

原始 `develop.txt` 已原样保存到 `deploy/private/develop.txt`，整个目录排除 Git。该文件仅作为连接资料读取，不执行其中的描述文字。运行所需的数据库、迁移账号、OIDC secret 和测试账号密码保存在同目录的 `runtime.json`，不得提交、打印或复制到公共文档。

需要 JDK 17、Maven 3.9.x、Node 22.12至22.x、npm 10.x、PowerShell 7与 Python 3.12。Python 客户端依赖仅安装在 `.local/python-deps`，不在服务器安装新组件。

首次配置或恢复本机私有运行配置：

```powershell
pwsh -File deploy/prepare-test-environment.ps1
```

此命令复用既有组件，只初始化本项目 `mop_test` 数据库、迁移/运行角色与 `mop-test` Keycloak realm/client/test users。数据库已有而本机项目凭据缺失、账号ID发生变化或命名空间归属不符时会拒绝修改，不能覆盖其他项目。

SSH 隧道仅绑定本机127.0.0.1，保持远程既有端口不变。第一次连接固定主机公钥于私有 `known_hosts`；后续公钥变化会拒绝连接。

图片存储组件准备使用`pwsh -File deploy/prepare-photo-environment.ps1`，只在现有MinIO创建本项目桶与受限账号。真实读写、不可删除、前缀限制及匿名拒绝已留证，应用照片上传/补传联调仍在实施；详见[图片测试存储](docs/photo-test-storage.md)。秘密和拥有者恢复资料继续只存于`deploy/private/`。

## 启动与停止

```powershell
pwsh -File deploy/start-dev.ps1
```

默认构建 API、安装前端依赖、复用或启动本项目 SSH 隧道，再注入私有运行配置启动 `remote_test`。已有构建可加 `-SkipBuild`。

访问 <http://127.0.0.1:5173>，点击 Keycloak 登录。测试身份用户名在私有 `runtime.json` 的 `test_users` 中，密码只在那里读取；它们是虚构测试账号。平台管理员有两租户及配置/审计能力，客户角色只访问所属租户。新增 `/tickets` 报障与受理页：viewer/tadmin可登记并查本人报障，msp/padmin按授权租户只读查看；会话岗位与capabilities同取当前租户成员关系。

API 默认8080；可用 `-ApiPort 18080` 修改 API 端口。Web端口5173与 Keycloak精确回调登记一致，修改Web端口前须同步本项目client回调配置。

```powershell
pwsh -File deploy/stop-dev.ps1
pwsh -File deploy/stop-test-tunnel.ps1
```

第一条只停本工程 API/Web；第二条可按需关闭本机 SSH 隧道。不会停止远程 PostgreSQL/Keycloak 或其他项目。停止脚本核验工程路径、进程命令与启动时间。

日志在 `.local/`。`/api/v1/health/live` 表示进程存活；`/api/v1/health/ready` 实时检查远程PostgreSQL与Keycloak，连接失败返回503，没有fixture回退。`production_ready` 保持 false。

## 检查

停止正在运行的本工程 API 后执行：

```powershell
pwsh -File deploy/check.ps1 -Install
```

检查脚本读取私有运行配置，设置 `MOP_RUN_REMOTE_TESTS=true`，使用指定远程PostgreSQL执行事务、持久幂等、并发CAS、租户范围及真实角色权限测试；同时执行纯单元、契约、前端客户端、类型检查与构建。历史fixture工作流默认跳过，不作为本轮组件联调证据。

已启动本地应用后执行真实登录与HTTP契约联调：

```powershell
npm.cmd ci
npm.cmd run test:api
```

脚本用私有测试身份在已安装浏览器中执行Keycloak授权码/PKCE登录，将临时Cookie/CSRF仅存入被Git忽略的私有会话文件；随后运行HTTP契约检查。它不使用fixture登录、密码直发业务API或token日志。会在项目测试库创建明确命名的测试草稿。

重启API后可重新捕获真实会话，验证上一轮数据与幂等结果保留：

```powershell
node tools/capture-test-session.mjs
node tests/api-smoke.mjs http://127.0.0.1:8080/api/v1 --verify-persistence
```

真实联调与重启结果见 [验证记录](docs/verification.md)。待确认规则仍为null，保存配置草稿不产生批准；监控接入与配置发布保持CONFIG_PENDING。

## 代码与后续范围

|目录|职责|
|---|---|
|`apps/web`|Vue/TypeScript/Element Plus管理端、Keycloak登录入口、会话权限与请求取消|
|`server/api`|Spring Security OIDC/PKCE、Cookie/CSRF、租户授权、统一HTTP错误及依赖就绪|
|`server/domain`|PlatformRepository、资产及上下文约定、运行模式守卫|
|`server/persistence`|PostgreSQL JDBC事务仓储、Flyway迁移、幂等与审计链|
|`server/adapters`|监控接口；Zabbix组件缺失仍不执行真实采集|
|`server/worker`|耐久任务消费者尚未实现，正常运行明确拒绝，不伪装已有Worker|
|`contracts`|原设计副本、原配置登记及当前实际API契约|
|`deploy` / `tools`|私有配置引用、本机SSH隧道、项目命名空间准备与检查|

当前实际契约为 `contracts/local-api.openapi.json`（命名沿用首批，内容已更新为remote_test V0.4）；生成源为 `tools/build-api-contract.mjs`。`design-core.openapi.json` 仍是设计原件，未实现路径不可视为可调用。

配置登记页现支持“修订与审批”：提案保存外部资料引用及hash，创建者提交后由另一平台管理员内部复核，所有动作与历史持久化。内部通过仍为证据未核验、配置未生效，不替客户确认或正式发布。接口、运行账号权限和真实验证记录见[配置修订说明](docs/configuration-revisions.md)。

用户已明确一期以本平台工单为权威。新增客户报障支持真实PostgreSQL持久登记、operation与登记详情查询；202只代表“报障已耐久登记，正式工单规则待确认”。正式state/priority/SLA截止均为null，联系方式UNVERIFIED，图片上传及补传尚未实现，接单/转派/解决/客户确认等其余D07闭环继续开发。outbox仅保存CONFIG_PENDING意图，没有RabbitMQ投递或Worker执行；缺少外部ITSM不阻塞本平台这一登记切片。详见[客户报障登记说明](docs/customer-report-registration.md)。

本轮24项Java纯单元、19项真实PG集成、前端21项、契约3项通过。真实OIDC下47次HTTP响应符合实际契约，会话岗位降级/恢复及1440/390px浏览器验收通过；[本轮汇总](docs/evidence/customer-report-verification-summary.json)区分全量检查与会话修正补跑。报障专用HTTP脚本及私有会话准备步骤见[登记说明](docs/customer-report-registration.md)，这些结果不代表完整工单验收完成。

V1–V4迁移沿用已执行版本，禁止修改；后续结构修订必须追加迁移。测试数据库选择不替代客户正式Q02物理隔离批准，Keycloak测试域不替代正式身份/岗位确认。Redis、RabbitMQ业务接入待完成；MinIO项目命名空间和受限账号已准备并验证，API照片业务仍在实施。Zabbix、外部ITSM、堡垒机缺失保持清单状态。用户最新要求切到云端继续剩余开发，逐功能验证/提交与指定测试组件规则保持一致。
