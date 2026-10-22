# 已实现功能真实验收

2026-10-22 起，JiangFaMing 所在的 `55000000-0000-4000-8000-000000000001` 已切为实际设备对接环境，禁止向此租户写入任何 SYNTHETIC、FAKE、fixture 或虚构验收数据。原六个测试账号已停用，不得为重跑历史验收而恢复它们或借用 JiangFaMing 的凭据。`runtime.mjs` 在发送请求前阻止对此租户及未知租户的合成业务写入；真实账号的只读验证仍可进行。需要写入的集成验收须先准备明确隔离、获授权的命名空间和专用测试身份；下文原运行说明属于历史用法，不能直接对现站重跑。见[真实数据切换记录](../../docs/real-data-cutover-20261022.md)。

在仓库根目录运行：

```powershell
node tools/acceptance/run.mjs identity resources services configuration intake readmodels
```

可只选择其中一个或多个分组。脚本仅接受私有配置中已核对的指定LAN测试环境，采用项目本地登录；密码、Cookie、CSRF、请求正文和签名下载URL不写证据。首次执行须保有Git忽略的 `deploy/private/lan-test-runtime.json`，既有API/Worker/PG/MinIO/监控必须就绪，不启动替代组件。

这是真实写入验收：每次生成独立run ID和 `SYNTHETIC_ACCEPTANCE_` 数据，使用已有受限测试身份、真实API和当前OpenAPI响应Schema。正常组序先生成虚构资产供服务草稿引用；仅执行services时可只读引用同租户已有资产，不修改设备。

身份用例结束停用本轮账号/角色；支持归档的联系人、备份/变更/巡检草稿按返回ID、租户和标记复核后归档；配置提案仅新增后撤回，不批准或发布生效。不可删除的历史、来源、文件和审计按现有机制保留。遇到依赖故障记录FAIL/BLOCKED，不启用组件、不接管历史任务、不自动重发未知写入。

每个业务case独立记录，后续独立case继续执行。ENV为环境准备，CLEAN为清理，均不计业务功能数；退出码1表示存在FAIL或BLOCKED。证据按独立UUID保存到本轮目录，不覆盖原记录。后续运行同时记录源码提交和各验收脚本SHA256，避免将不同脚本版本当作同一轮结果。

PASS仅指各场景实际声明的子功能，不是128项合同整项验收或生产批准。详细范围和首次失败链见 `docs/feature-acceptance-20261014.md`。
