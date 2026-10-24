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

当前处于实现与验证阶段，尚未标记UI01完成。实际组件、HTTP、浏览器、正式TC分别留证。V1–V51冻结资源不改写；本功能不需要迁移。主干集成前重新fetch并取得本机排他锁，锁不代表与云端写者完成协调。
