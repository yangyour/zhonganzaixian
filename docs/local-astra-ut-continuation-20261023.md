# 本地单会话接续开发记录

用户于2026-10-23明确恢复一个本地新会话的剩余缺口开发，覆盖旧的停止和仅云端执行安排；不恢复旧六会话、不创建其他聊天或自动化。按照实际代码和证据逐项核销128项责任映射，覆盖数不是完成率。

## 仓库与基线

| 项目 | 已核对值 |
| --- | --- |
| 客户／项目 | 众安在线 MOP |
| 当前模块 | UI01：合同 M1-1、M1-6，后续继续总清单 |
| 原仓库（只读参考） | `E:\work\wxw9\zhonganzaixian\mop-platform` |
| 独立检出 | `E:\work\wxw9\zhonganzaixian\.parallel\local-astra-ut-20261023` |
| 规范 origin | `https://github.com/yangyour/zhonganzaixian.git` |
| 组织路径 | `yangyour/zhonganzaixian` |
| 起始功能分支 | `codex/local-astra-ut-ui01-20261023` |
| 起始上游／fetch后主干 | `origin/main`／`6ca0569a` |
| 归属依据 | 用户本次明确指定、原仓库与独立克隆实际 Git 配置一致 |

原仓库的 `apps/web/qa/synthetic-routes.ts`、`apps/web/src/pages/AuditPage.vue`、`apps/web/src/pages/TenantInventoryPage.vue` 已有未提交修改，不带入本线。首次运行 `py -3 tools/check_implementation_gaps.py` 通过：128项、25组、未分配0，`completion_verified=false`。

`development-closeout-and-remaining-20261023.md` 是历史快照；最新main已有待办读取返回前授权、事件/账号读取守卫、角色矩阵打印、搜索弹窗语义修复、备份只核对模式及数据库守卫、公共监控模拟隔离。沿用已有实现，不重复记为本线成果。

## 指定环境预检

2026-10-23本地只读探测：`lan-test-connection.json` 的V50历史入口在SSH协议banner阶段断开，尚未验证身份或数据库版本；不能将旧V50记录当作本次实测。`develop.txt` 与固定主机信任的旧入口可连，真实PG17.9、`mop_test` 最新V37；该入口不满足当前V50契约，未迁移、未写入，不用作替代联调环境。当前工作站默认物理网关在另一网段，仍保留现有代理/VPN设置。

首次失败保存在[首次预检](evidence/local-astra-ut-20261023/environment-preflight-attempt-1.json)。用户回复“已恢复”后再次只读探测成功：固定主机信任、`mop-lan-test`归属匹配，PG17.9、50项成功迁移／最新V50，`mop_test_runtime`无superuser/createdb/createrole/bypassrls能力且工单表可读。连接阻塞解除；这只证明组件路径可用，不等于业务联调已通过。无安装、写入或迁移，见[恢复后预检](evidence/local-astra-ut-20261023/environment-preflight.json)。

## UI01 实施边界

新增兼容投影，在数据库筛选完整人群时排除 `CLOSED/CANCELLED/RESOLVED`，旧V1/V2保持。V42的 `PENDING_CUSTOMER` 仍保留，不根据中文标签扩大过滤。首页前五条、数量与SLA、完整分页使用一致授权谓词和单次数据库快照；分页请求各自重新读取，不声称跨请求仍为同一个冻结快照。SLA以服务端冻结输入和时钟为准，未知不转零、0剩余不自行推断违约。

UI01功能提交`4e034a71`已通过分层验证，集成提交`d53697df`已推送主干，正常合并最新`f5fc7637`并保留压缩OpenAPI格式。实际组件、HTTP、浏览器、正式TC分别留证；UI01总组仍保留完整V42监控t2和正式验收边界。V1–V51冻结资源未改写；本功能无迁移。集成锁已按归属释放，不把本机锁当作云端协调。

## 2026-10-23 22:08 EDT 远端去重核对

按用户新增要求，在恢复下一项前暂停新增改动并`git fetch origin --prune`。核对全部60个远端引用，`origin/main=d53697df57a848c49b843e621cb0c50bf789065b`，自本轮UI01集成后新增提交0；除以下六份WIP外，所有远端分支提交均为main祖先。

| 范围 | WIP当前提交 | 与交接一致 | 主干新增代表接口／领域类 |
| --- | --- | --- | --- |
| A05 | `3b219461` | 是 | 尚无AssetLedgerFactsController |
| M01 | `1b721119` | 是 | 尚无MonitoringRecoveryController |
| N01/N02 | `e7fa879a` | 是 | 尚无NotificationRoutingController |
| P01 | `db3d58ca` | 是 | 尚无ProductionReadinessController |
| P02 | `678a5a16` | 是 | 尚无AttachmentSecurityController |
| R02 | `ad7a2fc3` | 是 | 尚无ReportPublication |

每份WIP相对main只有原保留的1个独有草稿提交；这证明没有检测到新的远端实现，不表示草稿可直接发布，也不排除别人尚未推送的工作。本地A05保留原草稿并补已确定的成员复核、核心漂移、时间校验和验证；已在main的安全守卫、打印、搜索、备份模式和UI01不重复实施。后续每功能开始及提交/集成前再次fetch，检测重叠时先比对再继续。精确引用与检查结果见[远端去重证据](evidence/local-astra-ut-20261023/remote-dedup-check.json)。
