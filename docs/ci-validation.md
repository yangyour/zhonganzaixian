# 工程验证工作流

[cloud-validation.yml](../.github/workflows/cloud-validation.yml)在push和pull_request时定义三个独立GitHub-hosted检查：JDK17执行四个纯Java脚本；JDK17/Maven3.9.x以严格制品校验完成reactor `verify`；Node22完成契约、前端单测、类型检查和构建。官方actions按已核验的完整commit固定，权限仅contents:read，checkout不保留凭据。

远端测试开关固定false，历史整套fixture不启用；不启动应用、数据库或其他服务，不执行迁移或部署。Surefire摘要读取实际XML，缺失/零结果为NOT_RUN，全部跳过为SKIPPED；禁用套件被执行，或Maven成功却零测试执行，均使检查失败。跳过的PG/MinIO测试不表示指定服务器联调通过。

本地实际验证了工作流结构、shell/Python语法、action版本及输入、Maven3.9.x版本门禁。另在隔离目录复制公开前端源码和公共npm缓存，真实完成离线锁定安装、esbuild/Rollup执行、vue-tsc及Vite构建；原锁和工作区node_modules未修改。CI使用官方npm registry覆盖锁内旧host，保持integrity，不需要关闭TLS。记录见[配置检查](evidence/cloud-ci-validation.json)、[官方action核验](evidence/cloud-ci-action-refs.json)和[隔离构建](evidence/cloud-ci-isolated-web-build.json)。原始本地日志受Git忽略，不随此证据交付。

GitHub Actions运行结果为NOT_OBSERVED：当前gh读取在HTTP传输/代理层被拒绝，没有收到GitHub状态或响应头，不能判定token权限或源站状态。正常push可能触发检查，但尚未回读任何job的真实结果；完整后端构建/JUnit结果仍未核验。运行API/UI、指定PG/MinIO/OIDC、迁移及128项合同验收仍未完成，真实联调继续使用指定服务器。
