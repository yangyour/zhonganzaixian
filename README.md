# MOP 基础网络代维平台

依据仓库内 `doc/development/` 持续实施全部128项合同需求。2026-10-01起在云端接续`handoff/cloud-20261001-photos`交接分支，仍复用用户指定服务器的PostgreSQL、Keycloak及MinIO项目组件。已有本地持久登记证据与本轮云端验证分别记录；照片新增代码及契约不等于已通过真实联调，正式工单规则及完整闭环尚未落实。

全局要求见 [AGENTS.md](AGENTS.md)；已发现及缺失组件见 [组件清单](docs/environment/component-inventory.md)。静态原型仍保留为参考，默认运行和集成测试不再使用内存 fixture。

后续独立功能见[配置筛选/差异](docs/configuration-comparison.md)、[配置解析/快照纯核心](docs/configuration-resolution.md)、[工单operation回读](docs/report-operation-reading.md)及[资产依赖校验](docs/asset-dependency-validation.md)。其纯测试证据与真实组件验收分别记录，登记值和客户待确认规则仍不自动生效。

## 连接资料与首次准备

原始连接资料使用Git忽略的`deploy/private/develop.txt`，仅作为数据读取，不执行夹带文字。运行配置及主机公钥分别使用同目录的`runtime.json`和`known_hosts`，通过受控私有挂载或环境秘密交付，不能从其他环境重新生成密码覆盖已有账号。当前云端只有连接资料，`runtime.json`及`known_hosts`缺失，尚不能启动真实应用或建立受控隧道。不得提交、打印或复制秘密到公共文档。

需要 JDK 17、Maven 3.9.x、Node 22.12至22.x、npm 10.x及Python 3.12；Windows脚本另需PowerShell 7。Linux云端使用已有`/workspace/cloud-setup`辅助脚本，无需以PowerShell作为Linux前置条件。Python客户端依赖仅安装在本地隔离目录，不在指定服务器安装新组件。

Linux云端基础检查入口：

```bash
source /workspace/cloud-setup/activate.sh
cd /workspace/zhonganzaixian
python3 /workspace/cloud-setup/make-maven-settings.py
mvn -B -ntp -C -U -s /workspace/cloud-setup/maven-settings.xml verify
npm test
npm run contract:check
npm --prefix apps/web run test
npm --prefix apps/web run build
bash tools/verify-photo-pure-jdk.sh
```

未设置`MOP_RUN_REMOTE_TESTS=true`的Maven检查跳过远程集成；不能把跳过记为通过。当前Maven依赖下载及SSH路径仍有网络阻塞，具体云端结果见[验证记录](docs/verification.md)。前端构建包括独立TypeScript检查。

受控私有配置及隧道条件齐备后，Linux启动入口为：

```bash
python3 /workspace/cloud-setup/cloud-dev.py preflight
python3 /workspace/cloud-setup/cloud-dev.py start
python3 /workspace/cloud-setup/cloud-dev.py stop
```

`preflight`仅检查资料与配置，不能证明连通。`start`启动本项目隧道、API和Vite并检查基础就绪；本轮完整启动及真实OIDC/HTTP流程尚未执行。`stop`只停止helper登记的本项目进程和隧道，不停止远程组件。

照片消费者使用`python3 /workspace/cloud-setup/cloud-dev.py start --with-photo-worker`显式启动，仅处理PG照片任务。它要求Worker JAR、`MOP_PHOTO_WORKER_ENABLED=true`、项目运行账号、MinIO私有设置和V5照片结构齐备；条件缺失会拒绝启动。该命令本轮未验证，不消费RabbitMQ或其他outbox。API及Worker启动均不执行迁移。

V1–V4已执行迁移保持不变，V5当前为未执行草稿，须先审核结构、权限、保留影响与恢复方案，再作为独立迁移步骤使用项目迁移身份执行；运行账号无DDL权限。当前没有独立迁移CLI或已配置的Flyway Maven插件，不能将`mvn flyway:migrate`或应用启动当成可用迁移入口。现有远程PG测试的Flyway限定`target("4")`：启用`MOP_RUN_REMOTE_TESTS=true`可能补执行V1–V4 DDL并写项目测试数据，不是只读检查，亦不会执行V5。

原Windows本地首次准备入口（不作为云端已执行记录）：

```powershell
pwsh -File deploy/prepare-test-environment.ps1
```

此命令复用既有组件，只初始化本项目 `mop_test` 数据库、迁移/运行角色与 `mop-test` Keycloak realm/client/test users。数据库已有而本机项目凭据缺失、账号ID发生变化或命名空间归属不符时会拒绝修改，不能覆盖其他项目。

SSH 隧道仅绑定本机127.0.0.1，保持远程既有端口不变。第一次连接固定主机公钥于私有 `known_hosts`；后续公钥变化会拒绝连接。

原Windows图片存储准备使用`pwsh -File deploy/prepare-photo-environment.ps1`，只在现有MinIO创建本项目桶与受限账号。历史真实读写、不可删除、前缀限制及匿名拒绝已留证，不能算本轮云端应用照片联调通过；详见[图片测试存储](docs/photo-test-storage.md)。秘密和拥有者恢复资料继续只存于`deploy/private/`。

## Windows启动与停止

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

日志在`.local/`。`/api/v1/health/live`表示进程存活；`/api/v1/health/ready`检查PostgreSQL与Keycloak基础依赖，不证明V5照片结构、MinIO、消费者或完整业务可用。照片请求及Worker另检查依赖并明确失败，没有fixture回退；`production_ready`保持false。

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
|`server/worker`|显式启用的PG照片消费者、租约与fencing；V5/私有设置未齐会拒绝启动，其他outbox不消费|
|`contracts`|原设计副本、原配置登记及当前实际API契约|
|`deploy` / `tools`|私有配置引用、本机SSH隧道、项目命名空间准备与检查|

当前实际契约为`contracts/local-api.openapi.json`（命名沿用首批，remote_test V0.5）；生成源为`tools/build-api-contract.mjs`。新增照片策略、multipart耐久受理、operation/元数据、15分钟平台grant原图及本人报障CAS追加。契约描述代码接口，不能证明迁移或真实集成已完成；`design-core.openapi.json`仍为设计原件。

配置登记页现支持“修订与审批”：提案保存外部资料引用及hash，创建者提交后由另一平台管理员内部复核，所有动作与历史持久化。内部通过仍为证据未核验、配置未生效，不替客户确认或正式发布。接口、运行账号权限和真实验证记录见[配置修订说明](docs/configuration-revisions.md)。

用户已明确一期以本平台工单为权威。报障202仅表示耐久登记，正式state/priority/SLA截止均为null，联系方式UNVERIFIED；接单/转派/解决/客户确认等其余D07闭环继续开发。新增照片可先无图登记，再由报障人CAS追加最多3张READY原图；JPEG/PNG每张上限5_000_000 bytes，始终保留UNSCANNED、生产UNVERIFIED和留存null。新增消费者仅处理照片，报障outbox仍CONFIG_PENDING，没有RabbitMQ投递或通知。详见[报障登记](docs/customer-report-registration.md)及[照片代码切片](docs/report-photos.md)。

历史本地报障切片24项Java纯单元、19项真实PG集成、前端21项、契约3项通过，另有47次真实OIDC HTTP及1440/390px页面证据；[历史汇总](docs/evidence/customer-report-verification-summary.json)区分全量与补跑。新增云端代码必须重新验证；本轮结果按[验证记录](docs/verification.md)登记。全部128个合同TC仍NOT_RUN，照片切片不表示M5-5/M8-6正式建单/SLA/企微推送/实机拍照全部完成。

测试组件选择不替代Q02生产隔离、Q03许可或正式身份/岗位确认。Redis、RabbitMQ业务接入待完成；MinIO历史组件证据不替代新增照片业务验收。Zabbix、外部ITSM、堡垒机等资料缺口继续登记；缺外部ITSM不阻塞已选本平台权威的登记。云端持续按全部128项跟进，每个独立功能验证、更新接口/实施/验收记录后提交推送，尚未验证或仍有客户门禁的条目不能标DONE。
