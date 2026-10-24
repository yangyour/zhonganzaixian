# P02 原图二进制返回守卫

最新远端`e8362fd6`已实现附件隔离及发布版本前后核对，本次只补原图二进制响应的最后授权边界。`FileController.content`在对象读取、摘要/长度、当前照片授权和两次隔离发布版本检查之后，再复核HTTP会话、账号securityVersion及当前成员；检查通过后才写成功读取审计和返回字节。

普通JSON Advice不覆盖二进制converter，不能据此假设账号撤销已受保护。本次保留原grant、对象内容和release检查，不启用扫描、不改变附件发布状态、不访问实际存储或迁移。

26项定向Java检查通过，0失败/跳过：10项通过实际身份filter及byte[] converter验证账号修订、移除、会话ID/认证替换、失效、切租户、撤权、身份依赖故障、发布版本变化和正常下载；拒绝场景均无私有字节及成功读取审计。另16项旧文件/附件HTTP回归通过。来源为可控存储替身，实际MinIO/扫描业务与V52仍单列，不冒称真实原图或扫描完成。证据见[返回守卫测试](evidence/local-astra-ut-20261023/upstream-batch/return-guards.json)。
