# production 会话进度

## 仓库与授权核对

2026-10-23，本线负责众安在线MOP生产安全与验收工具。独立工作目录 `E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\production`；规范origin `https://github.com/yangyour/zhonganzaixian.git`（`yangyour/zhonganzaixian`）；分支 `codex/launch-production-20261023`，起始上游 `origin/main`，fetch后HEAD及远端基线 `05ec77ba`。依据本检出AGENTS首节和 `docs/parallel-development-20261023.md`。原主目录只读，未编辑现行前端样式／导航／AssetsPage.vue／AppTable.vue。开始时18216／15216无监听，本功能没有启动进程。

## P01 只读生产启用前检查

- 代码：严格无凭据manifest、显式TLS／HTTPS健康／匿名Cookie／HTTP跳转探测、超时与响应上限、脱敏机器报告、独占证据文件。当前test runtime与生产批准分开报告，生产门禁保留。
- 验证：17项纯单元通过；真实公共入口证书校验失败、HTTP跳转未满足，HTTPS后续检查NOT_RUN。见 [接口和运行说明](../production-preflight.md)、[真实证据](../evidence/production-preflight-20261023/public-entry.json)。
- 未完成：正式生产profile/数据库隔离方案、实际TLS修复、生产批准、正式TC；未迁移、部署、写业务数据、安装／停止组件。
- 按本功能单独提交推送，再持integration.lock集成最新main；提交hash以Git历史为准，后续功能续记。

P01已在 `ab441b95` 独立提交推送，并持integration.lock正常快进合入main；锁已释放。

## P02 安全会话传输保护

- 代码：显式开关、启动设置守卫、直接TLS请求过滤、无凭据回环健康例外；防转发头绕过，退出／改密删除Cookie保留可信传输属性。
- 测试：API reactor成功，18项实际通过、13项历史fixture跳过，0失败；第一次Mock scheme断言失败及修正见[运行和API说明](../secure-session-transport.md)。生成契约一致性通过。
- 真实联调：未启用、未部署、未启动新服务；实际TLS资源／代理边界尚待核验；不声明production profile或正式TC完成。与本项无关的组件沿用P01清单，无新安装／配置变更。

后续确定范围：制品和回退保护、凭据／附件安全边界、恢复演练与合同验收工具。真实外部执行、设备备份、删除、M7均不在本线启用范围。
