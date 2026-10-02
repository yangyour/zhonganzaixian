# 指定测试环境配置的private仓库接续（2026-10-02）

用户最新直接要求把 `deploy/private/` 上传Git，并声明仓库仅由本人使用。本次核验 `yangyour/zhonganzaixian` 可见性为 private；按该授权提交以下6个现有配置文件的原始内容，未加密。旧文档中“所有私有配置未上传”是此前状态，最新例外见 `AGENTS.md`。

|文件|用途|
|---|---|
|`deploy/private/develop.txt`|指定测试服务器的连接资料，只作为数据读取，不执行其中命令|
|`deploy/private/runtime.json`|指定PG、Keycloak、MinIO等运行配置和虚构测试身份|
|`deploy/private/known_hosts`|已固定的SSH主机密钥，不能静默跳过核验|
|`deploy/private/existing-components.json`|已有共享组件的私有连接与盘点资料|
|`deploy/private/photo-namespace.json`|项目照片MinIO命名空间、受限账号和拥有权记录|
|`deploy/private/asset-import-namespace.json`|项目导入MinIO命名空间、受限账号和拥有权记录|

这些文件含真实连接资料或凭据，只允许存在于这个private仓库及受控运行配置中。不得打印内容、附到聊天、写入源码或公共文档/证据；不得上传其他项目或个人账号秘密。QA会话/cookie、`runtime-before-*`历史备份和生成的QA样本继续忽略，不作为跨机器运行配置。

Dot在自己的现有工作区取得分支 `handoff/cloud-20261001-photos` 的最新配置后，核对JSON格式、SSH固定主机密钥及项目命名空间。Windows本机的绝对JAR路径不是Dot云电脑的有效路径；应按实际已构建的解析器位置设置本机环境覆盖，不假称复制原文件即可启动。沿用受限运行账号和SSH隧道；不创建其他数据库、缓存、队列、监控替代指定组件，不安装/停止服务器既有服务。

取得配置不代表测试环境联调完成。必须先核验真实组件连通、实际就绪状态，再运行相关集成/HTTP/UI检查并记录结果；不足的网络/权限/组件继续登记。既有V1–V6不可改字节，启动不自动迁移；不重复创建或接管已有命名空间。不创建或委派Codex/Work任务来获取或使用这些资料。

之后每次提交这些配置都应确认仓库仍为private；改为public或推送被保护拒绝时停止并报告，不自行改变权限或绕过保护。此次只改变配置的版本管理与接续方式，不旋转密码、改动远程服务、执行迁移或宣称生产批准。
