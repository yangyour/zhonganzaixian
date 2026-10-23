# 报告与经营数据开发进度

## 归属与执行范围（2026-10-23）

- 项目：众安在线 MOP；报告、首页与经营聚合后端。SLA 基础规则归监控会话。
- 独立检出：`E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\reports`。
- 规范 origin：`https://github.com/yangyour/zhonganzaixian.git`，组织 `yangyour/zhonganzaixian`。
- 分支：`codex/launch-reports-20261023`；开始时上游 `origin/main`、HEAD 与最新 main 均为 `05ec77ba9fbbca99e2b1e4db739c2c0aafcb645d`。
- 依据：本检出 AGENTS、并行协调文档及两份 launch-work 清单；原主工作区与前端视觉文件只读。
- 18215/15215 在开始时未监听；本功能无需启动 API/Web。本地只启动到既有服务器 PG 的临时回环 SSH 转发，验证后关闭。

## 已完成增量：季度报告原子来源捕获

新增 `POST /api/v1/inspection-quarterly-reports/capture`，将明确执行单修订和预期摘要的校验、不可变来源集合、季度 DRAFT、两条审计及幂等收据放入同一个已有 PostgreSQL 事务。复用 V23/V41，未新增或修改迁移。详情与 PDF 草稿沿用同一已冻结文档及既有摘要复核。

完整接口、运行命令与边界见 [原子捕获说明](../report-capture-20261023.md)。代码已实现且真实 PG 切片已验证；仍是 DRAFT，没有审批、正式发布、客户签字或真实设备体检结论。

验证：82 项聚焦 Java 单元/隔离测试、7 项指定 LAN PostgreSQL 17.9 集成、196 项契约测试通过，生成契约一致。新建两处独立 `SYNTHETIC_REPORT_CAPTURE_*` 租户，仅新建无本地口令/外部身份绑定的测试主体；结束停用该主体及其成员关系，保留不可变测试证据。实际业务租户七张相关表指纹和 V50 迁移历史不变。新增接口真实 TCP HTTP/浏览器及正式合同 TC 未执行。

首轮 Maven 参数拆分、Mockito 重设异常桩和契约测试 URN 格式问题均已修正；失败未登记为通过。最终 [Java](../evidence/report-capture-20261023/java-tests.json)、[PG](../evidence/report-capture-20261023/postgresql.json)、[组件](../evidence/report-capture-20261023/components.json) 证据分别留存。

## 后续范围

- 推进已有真实业务表的首页事实聚合，未知健康率、SLA 正式统计、成本及人效口径继续为 null。
- 统一跨业务指标快照、月报/体检模板、审批发布、正式网页/PDF 同源交付和阅读记录尚未完成；不能把本次原子季度草稿标为整条报告交付链 DONE。
- 正式评分、审核人/接收人、成本人员来源及客户报告样式/重生成规则保持待确认；通用代码继续配置化推进。

## 第二个增量：首页非终态工单聚合

新增 `GET /api/v1/dashboard/pending-tickets`。同一只读快照统一读取 V33/V42 当前状态并给出已知非终态、终态、未配置数量与最近五条；未配置登记存在时完整待办数为 null。客户角色沿用本人报障可见性。未知 SLA/健康率保持 null，没有接管前端视觉。详见 [接口与验收边界](../dashboard-pending-tickets-20261023.md)。

4 项新隔离命名空间真实 PG 通过，覆盖混合状态、客户/租户隔离及查询期间并发插入的快照一致性；新增测试主体均停用，实际客户十表/迁移历史不变。最终 128 项首页/季度报告/PDF 聚焦 Java、197 项契约通过。V42 状态分类已单元验证，本次 PG 未构造 V42 业务流转，TCP HTTP/浏览器/正式 TC 未执行。

第一项功能提交 `056abff7` 已经顺序合入 main `cdcd45c3`，合并后额外 PDF 9 项回归通过；本分支同步吸收同期其他五条线的提交。第二项按相同锁流程独立提交集成，具体提交以 Git 历史为准。
