# MOP 基础网络代维平台

## 当前接续状态（2026-10-02）

当前在niko获准独立测试环境继续开发，原服务器网络阻塞单独保留。实际API为V0.9，新增[资产依赖持久草稿API](docs/asset-dependency-api.md)；真实PG/OIDC/HTTP证据与尚未完成的UI/监控/OSS分别记录。存储按用户选定OSS接线，尚待受控替换凭据及真实云验证，不能视为已通过。运行说明见[独立环境](docs/niko-local-test.md)，最新实施和验收见[状态](docs/implementation-status.md)，所有已知阻塞与可继续范围见[当前阻塞表](docs/current-development-blockers.md)。

## 历史本地交接状态

依据仓库内 `doc/development/` 持续实施全部128项合同需求。用户2026-10-01要求接回本地，已接续云端最后交接`1db3976`，云端业务写入已停止，见[本地交接](docs/local-development-handoff.md)。本批复用指定服务器PostgreSQL、Keycloak和MinIO，V5、完整构建/组件测试、基础HTTP80次、边界/恢复/撤权HTTP52次、运行中Worker重试HTTP25次和照片UI36项分别通过。正式工单规则及完整闭环尚未落实，见[本地验证](docs/local-runtime-verification.md)。

全局要求见 [AGENTS.md](AGENTS.md)；已发现及缺失组件见 [组件清单](docs/environment/component-inventory.md)。静态原型仍保留为参考，默认运行和集成测试不再使用内存 fixture。

后续独立功能见[配置筛选/差异](docs/configuration-comparison.md)、[配置解析/快照纯核心](docs/configuration-resolution.md)、[工单operation回读](docs/report-operation-reading.md)、[资产依赖校验](docs/asset-dependency-validation.md)及[导入逐行预检](docs/asset-import-precheck.md)。其纯测试证据与真实组件验收分别记录，登记值和客户待确认规则仍不自动生效。

## 当前D04配置诊断

新增 `GET /api/v1/settings/effective?key=...`：当前padmin读取本租户同一PG快照中的登记/近期最多5条内部提案元数据；不返回原值或资料正文。CONFIG_PENDING、生效null和定义/发布/客户核验NOT_IMPLEMENTED保留，内部APPROVED仍未生效。详见[接口与来源边界](docs/configuration-diagnostics.md)。

后续完整检查已通过：123纯/隔离、51指定PG（43既有+8新增）、3指定MinIO，13历史fixture跳过；初始147前端（旧113+新34）、安全metadata修复后最终154（147+新7）、类型/构建与实际API V0.7契约10通过。六身份50项真实HTTP及仅项目PG转发503/恢复、26项真实桌面/390px仿真UI与逐图复核通过，原registry/draft未变，见[构建属性摘要](docs/evidence/local-configuration-diagnostic-build.json)、[HTTP证据](docs/evidence/local-configuration-diagnostic-http.json)、[页面证据](docs/evidence/local-configuration-diagnostic-ui.json)。旧列表异常Q与非文本标签保留并安全显示，原写表单/同请求重试受到保护；完整D04仍待正式来源与客户规则。

HTTP复现使用 `node tests/configuration-diagnostic-http.mjs`，真实会话只从被Git忽略的 `deploy/private/qa-diagnostic-*.json` 读取。此功能没有新增DDL或改变V1–V5，不以照片/CI历史结果替代当前诊断验收。

## 连接资料与首次准备

原始连接资料使用Git忽略的`deploy/private/develop.txt`，仅作为数据读取，不执行夹带文字。运行配置及主机公钥分别使用同目录的`runtime.json`和`known_hosts`，只通过受控私有配置或环境秘密读取，不能重新生成密码覆盖已有账号。当前本地复用既有私有配置和工程隧道；历史云端因缺配置和网络限制未完成联调。不得提交、打印或复制秘密到公共文档。

需要 JDK 17、Maven 3.9.x、Node 22.12至22.x、npm 10.x及Python 3.12；Windows脚本另需PowerShell 7。Linux云端使用已有`/workspace/cloud-setup`辅助脚本，无需以PowerShell作为Linux前置条件。Python客户端依赖仅安装在本地隔离目录，不在指定服务器安装新组件。

历史Linux云端基础检查入口（云端已停止写入）：

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

未设置`MOP_RUN_REMOTE_TESTS=true`的Maven检查跳过远程集成，不能把跳过记为通过。历史云端Maven及SSH阻塞保留在[验证记录](docs/verification.md)；本批本地完整检查已通过。前端构建包括独立TypeScript检查。

受控私有配置及隧道条件齐备后，Linux启动入口为：

```bash
python3 /workspace/cloud-setup/cloud-dev.py preflight
python3 /workspace/cloud-setup/cloud-dev.py start
python3 /workspace/cloud-setup/cloud-dev.py stop
```

这些命令保留为历史云端入口，未在云端完成真实启动验收。`preflight`仅检查资料与配置；`stop`只处理helper登记的本项目进程和隧道，不停止远程组件。当前运行使用下述Windows入口。

照片消费者使用`python3 /workspace/cloud-setup/cloud-dev.py start --with-photo-worker`显式启动，仅处理PG照片任务。它要求Worker JAR、`MOP_PHOTO_WORKER_ENABLED=true`、项目运行账号、MinIO私有设置和V5照片结构齐备；条件缺失会拒绝启动。该命令本轮未验证，不消费RabbitMQ或其他outbox。API及Worker启动均不执行迁移。

V1–V4保持原迁移，V5已由独立CLI在指定项目测试库执行一次，并通过结构/ACL核验；再次Apply执行0条DDL。API及Worker启动不执行迁移，运行账号无DDL权限。V5 SQL已冻结，SHA-256为`5A84F62BDAAB9BADFD5D87F4D30AE0AC1F9C1AE2F48D0D84E0635485A7BBDC55`；后续结构变更须新增迁移，不能修改已应用V5。

原Windows本地首次准备入口（不作为云端已执行记录）：

```powershell
pwsh -File deploy/prepare-test-environment.ps1
```

此命令复用既有组件，只初始化本项目 `mop_test` 数据库、迁移/运行角色与 `mop-test` Keycloak realm/client/test users。数据库已有而本机项目凭据缺失、账号ID发生变化或命名空间归属不符时会拒绝修改，不能覆盖其他项目。

SSH 隧道仅绑定本机127.0.0.1，保持远程既有端口不变。第一次连接固定主机公钥于私有 `known_hosts`；后续公钥变化会拒绝连接。

原Windows图片存储准备使用`pwsh -File deploy/prepare-photo-environment.ps1`，只在现有MinIO创建本项目桶与受限账号。历史真实读写、不可删除、前缀限制及匿名拒绝已留证，不能算本轮云端应用照片联调通过；详见[图片测试存储](docs/photo-test-storage.md)。秘密和拥有者恢复资料继续只存于`deploy/private/`。

## Windows独立迁移、启动与停止

停本项目应用并完成根工程编译/安装后，准备迁移classpath，再默认Check；只有核验V1–V4与唯一待执行V5后才显式ApplyPhotos：

```powershell
pwsh -File deploy/stop-dev.ps1
pwsh -File deploy/prepare-migration-classpath.ps1
pwsh -File deploy/migrate-test-schema.ps1
pwsh -File deploy/migrate-test-schema.ps1 -ApplyPhotos
pwsh -File deploy/migrate-test-schema.ps1
```

默认Check不执行DDL；ApplyPhotos使用项目迁移身份及Flyway历史，核对源码/构建资源、结构与受限运行ACL。入口拒绝应用仍在运行、旧构建或不符历史，不支持clean、修补校验和或偷偷迁移。已应用环境重复ApplyPhotos不会重建照片表。集成测试会写明确虚构项目数据，不能称只读检查。

```powershell
pwsh -File deploy/start-dev.ps1
pwsh -File deploy/start-dev.ps1 -WithPhotoWorker
```

默认构建API和前端并以`remote_test`连接指定组件；照片任务需显式加`-WithPhotoWorker`启动独立Worker，已有构建可加`-SkipBuild`。两条启动命令按所需模式择一执行。照片模式要求策略、V5结构/ACL和MinIO均就绪，并核验Worker启动；不会自动迁移或退回fixture。

访问 <http://127.0.0.1:5173>，点击 Keycloak 登录。测试身份用户名在私有 `runtime.json` 的 `test_users` 中，密码只在那里读取；它们是虚构测试账号。平台管理员有两租户及配置/审计能力，客户角色只访问所属租户。新增 `/tickets` 报障与受理页：viewer/tadmin可登记并查本人报障，msp/padmin按授权租户只读查看；会话岗位与capabilities同取当前租户成员关系。

API 默认8080；可用 `-ApiPort 18080` 修改 API 端口。Web端口5173与 Keycloak精确回调登记一致，修改Web端口前须同步本项目client回调配置。

```powershell
pwsh -File deploy/stop-dev.ps1
pwsh -File deploy/stop-test-tunnel.ps1
```

第一条只停拥有者记录中的本工程API/Web/照片Worker；第二条按需关闭本项目SSH隧道。停止前逐项核验工程路径、可执行文件/制品、命令行和启动时间，PID复用或归属不符时拒绝停止并保留记录，不停止远程组件或其他项目。

日志在Git忽略的`.local/`。`/api/v1/health/live`仅表示进程存活；`/api/v1/health/ready`核对PostgreSQL和Keycloak，配置照片能力时还实际探测策略、V5结构/ACL及MinIO。照片`enabled/ready/policy_ready/schema_ready/storage_ready`应全部为true；依赖失败返回503与脱敏原因，不回退fixture。此就绪不代表Worker已完成任务或生产批准，`production_ready`仍false。

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

照片专用HTTP复现还需真实OIDC的viewerA、tadmin、msp、viewerB四个测试身份会话，受控文件为`deploy/private/qa-photo-{身份}.json`；它们覆盖三个岗位和两个租户。Cookie/CSRF仅保存在私有目录，不使用fixture登录。先从公共无凭据生成器创建虚构JPEG/PNG，再运行依赖原图的脚本；以下均从工程根目录执行：

```powershell
New-Item -ItemType Directory -Force .local/qa-report-photos/fixture-generator | Out-Null
javac -d .local/qa-report-photos/fixture-generator tests/GeneratePhotoFixtures.java
java -cp .local/qa-report-photos/fixture-generator GeneratePhotoFixtures
node tests/report-photo-smoke.mjs
```

编译与生成步骤仅写Git忽略的class目录和`.local/qa-report-photos/fixtures`，不读私有配置；真实HTTP脚本写虚构项目数据并输出脱敏证据，需要已启动的照片Worker及指定组件，不在依赖失败时改用内存。

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

当前实际契约为`contracts/local-api.openapi.json`（沿用首批命名，remote_test V0.6），生成源为`tools/build-api-contract.mjs`。照片策略、耐久受理、operation/元数据、15分钟平台grant、本人CAS追加及typed照片就绪已通过本批真实HTTP响应校验；`design-core.openapi.json`仍为设计原件。

配置登记页现支持“修订与审批”：提案保存外部资料引用及hash，创建者提交后由另一平台管理员内部复核，所有动作与历史持久化。内部通过仍为证据未核验、配置未生效，不替客户确认或正式发布。接口、运行账号权限和真实验证记录见[配置修订说明](docs/configuration-revisions.md)。

用户已明确一期以本平台工单为权威。报障202仅表示耐久登记，正式state/priority/SLA截止均为null，联系方式UNVERIFIED；接单/转派/解决/客户确认等其余D07闭环继续开发。新增照片可先无图登记，再由报障人CAS追加最多3张READY原图；JPEG/PNG每张上限5_000_000 bytes，始终保留UNSCANNED、生产UNVERIFIED和留存null。新增消费者仅处理照片，报障outbox仍CONFIG_PENDING，没有RabbitMQ投递或通知。详见[报障登记](docs/customer-report-registration.md)及[照片代码切片](docs/report-photos.md)。

历史本地报障切片24项Java纯单元、19项真实PG集成、前端21项、契约3项通过，另有47次真实OIDC HTTP及1440/390px页面证据；[历史汇总](docs/evidence/customer-report-verification-summary.json)区分全量与补跑。新增云端代码必须重新验证；本轮结果按[验证记录](docs/verification.md)登记。全部128个合同TC仍NOT_RUN，照片切片不表示M5-5/M8-6正式建单/SLA/企微推送/实机拍照全部完成。

照片详情会明确标出保留队列所属登记，支持返回原登记或安全放弃本地选择；上传/追加不确定或202尚未终态回读时保留原File、键和目标，不能把A照片追加到B。原图解码边长16_384、25_000_000像素是技术资源防护，不是客户业务批准；JPEG/PNG仍只是开发方向。

本批完整检查为96项纯单元/隔离、43项指定PG、3项指定MinIO通过，fixture13项跳过；前端全量113项及V0.6契约8项通过。实际UI发现并修复登记表单被详情抽屉遮挡，修复后44项现存定向回归及最终类型/构建通过；44项包含于113项，不累加。当前主包464.09kB、Tickets54.49kB、Configurations49.84kB，无包体警告。

基础80次HTTP、[恢复专项52次](docs/evidence/local-photo-resilience.json)、[运行中重试专项25次](docs/evidence/local-photo-worker-retry.json)和[照片UI36项](docs/evidence/local-report-photo-ui.json)分别留证，不合为单一脚本计数。UI使用Edge154.0.4258.48持久Playwright无头会话，1440×900/390×844仿真正常控件，覆盖下载PNG/hash、真实提交后丢响应及原请求重试、并发CAS、已存配额与租户/注销，0 pageerror。52次脚本验证精确5MB、重启、先停Worker的存储断链恢复及撤权；另25次脚本保持Worker运行，真实观测RETRY_WAIT与退避完成原任务/hash，同PID/startTime，finally恢复。手机仿真不等于物理相机或企微验收。

SDK修复提交`eba5630`的GitHub CI已实际回读成功。照片功能提交`f56ad1b`的[独立CI](https://github.com/yangyour/zhonganzaixian/actions/runs/36846410035)也已回读，契约/Web、纯Java和后端编译/单元/打包三个job均成功；CI不连接私有组件，本地PG/MinIO/OIDC另行留证，见[CI说明](docs/ci-validation.md)。

测试组件选择不替代Q02生产隔离、Q03许可或正式身份/岗位确认。Redis、RabbitMQ业务接入待完成，Zabbix、外部ITSM、堡垒机等资料缺口继续登记。M5-5/M8-6实机拍照、正式SLA/通知/企微、扫描和保留策略尚未验收，全部128项继续逐功能实施、留证、提交推送；未验证或仍有客户门禁的条目不标DONE。
