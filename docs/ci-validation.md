# 工程验证工作流

[cloud-validation.yml](../.github/workflows/cloud-validation.yml)在push和pull_request时定义三个独立GitHub-hosted检查：JDK17执行四个纯Java脚本；JDK17/Maven3.9.x以严格制品校验完成reactor `verify`；Node22完成契约、前端单测、类型检查和构建。官方actions按已核验的完整commit固定，权限仅contents:read，checkout不保留凭据。

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
