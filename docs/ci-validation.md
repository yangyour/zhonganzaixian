# 工程验证工作流

## 2026-10-21 按用户要求移除 Cloud validation

用户明确要求删除 `Cloud validation` 工作流。本次移除 `.github/workflows/cloud-validation.yml`，同时移除仅用于校验该文件中 Maven 安装步骤的 `tools/test_ci_maven_pin.py`，避免本地测试继续读取已删除文件。对应 GitHub Actions 工作流停用，以阻止仍保留旧配置的其他分支再次触发它。`Private tracked source recovery` 工作流保持原样，业务测试源码和本地验证工具继续保留。

本次验证范围为删除文件及引用检查、其他工作流字节一致性与 `git diff --check`；不执行应用、数据库、迁移、部署或外部组件联调。删除工作流不构成业务测试或合同验收通过。下文保留历史配置与验证证据，均对应各自原始提交。

## 历史配置与验证记录

[历史 cloud-validation.yml](https://github.com/yangyour/zhonganzaixian/blob/b9ee49d249b16a2e7be0fe6310d07f2bbd13371e/.github/workflows/cloud-validation.yml)在push和pull_request时定义三个独立GitHub-hosted检查：JDK17执行四个纯Java脚本；JDK17/Maven3.9.x以严格制品校验完成reactor `verify`；Node22完成契约、前端单测、类型检查和构建。官方actions按已核验的完整commit固定，权限仅contents:read，checkout不保留凭据。

远端测试开关固定false，历史整套fixture不启用；不启动应用、数据库或其他服务，不执行迁移或部署。Surefire摘要读取实际XML，缺失/零结果为NOT_RUN，全部跳过为SKIPPED；禁用套件被执行，或Maven成功却零测试执行，均使检查失败。跳过的PG/MinIO测试不表示指定服务器联调通过。

本地实际验证了工作流结构、shell/Python语法、action版本及输入、Maven3.9.x版本门禁。另在隔离目录复制公开前端源码和公共npm缓存，真实完成离线锁定安装、esbuild/Rollup执行、vue-tsc及Vite构建；原锁和工作区node_modules未修改。CI使用官方npm registry覆盖锁内旧host，保持integrity，不需要关闭TLS。记录见[配置检查](evidence/cloud-ci-validation.json)、[官方action核验](evidence/cloud-ci-action-refs.json)和[隔离构建](evidence/cloud-ci-isolated-web-build.json)。原始本地日志受Git忽略，不随此证据交付。

2026-10-01通过GitHub connector实际回读SDK修复提交`eba56305e37621b363c5d4355c075080da0cba83`的push运行：[run 36836074365](https://github.com/yangyour/zhonganzaixian/actions/runs/36836074365)为`completed/success`。

|实际job|结果|验证范围|
|---|---|---|
|Contracts and Web unit tests, typecheck and build|completed/success|锁定安装、实际API契约、Web单元、类型检查和构建步骤成功|
|Pure Java source checks|completed/success|Java工具链及已提交纯Java检查步骤成功|
|Backend compile, unit tests and packaging|completed/success|Maven工具链、严格制品校验的全模块verify和JUnit边界摘要步骤成功|

[公开回读摘要](evidence/github-eba5630-verification.json)仅含commit/run/job状态及28条步骤摘要，不复制原始actor、runner身份或日志。该结果只对应`eba5630`旧提交。照片功能提交`f56ad1baf8c943f5bec8e7bc658ff347f9bc5a99`的[run 36846410035](https://github.com/yangyour/zhonganzaixian/actions/runs/36846410035)已另外回读，push、completed/success，三个job及28条步骤全部成功；见[照片提交安全摘要](evidence/github-f56ad1b-verification.json)。CI固定`MOP_RUN_REMOTE_TESTS=false`，不连接私有PG/MinIO/OIDC，不执行应用、迁移或组件联调；本地真实组件结果另见[本地验证](local-runtime-verification.md)。

历史`1db3976`、`d4375f7`对应运行失败仍保留，不能因后续成功改写成通过。此前gh读取受HTTP传输/代理阻断的NOT_OBSERVED仅描述当时读取渠道，不是当前GitHub状态。本批实际回读不替代完整M5-5/M8-6或128项合同验收。

2026-10-02追加：Pure Java job同时运行5项SSH信任策略隔离测试，使用临时Python环境及现有锁定requirements；mock客户端和临时ROOT，不读取真实私有配置、不连接SSH服务器。验证固定键缺失时拒绝，禁止自动登记/重写known_hosts；不据此声明真实SSH或组件已连通。

## 2026-10-07 Maven 运行器版本漂移修复

提交 `6a2cee1ab1715a5d1d42f6aaaaa2b5d8b45253eb` 的[运行37566895157](https://github.com/yangyour/zhonganzaixian/actions/runs/37566895157)在后端工具链检查失败：运行器预装 Maven 3.10.0，项目要求3.9.x；后端 Maven 测试根本未启动，不能记为代码回归通过。该次前端/契约、纯Java/SSH和Windows保护作业成功。

新增独立安装步骤，在临时目录下载固定 Apache Maven 3.9.9，使用[官方SHA512](https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz.sha512)硬编码校验，校验成功才解包并通过 GITHUB_PATH 供下一步骤使用。保留原3.9.x门禁、严格制品检查、远端测试禁用边界及只读权限，不扩大为接受任意运行器版本。

本地真实下载的压缩包摘要与官方值相同，解包版本实际为3.9.9/JDK17；新增两个凭据无关保护测试检查固定源/校验顺序，并验证坏摘要在解包和PATH修改前失败。全部169个Python测试通过；Bash语法检查通过，actionlint/shellcheck本地不可用。后续准确提交的CI结果另行回读；本记录不预先声明CI成功。
