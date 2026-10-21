# D10 报备审批核心（2026-10-19）

接续基线：`6f5558a`。独立分支：`dot/d10-change-approval-20261019`。

## 实现

`ChangeNoticeApproval` 提供绑定不可变报备修订的审批状态机：

- DRAFT → IN_REVIEW：仅申请人提交。
- DRAFT / IN_REVIEW → WITHDRAWN：仅申请人撤回。
- IN_REVIEW → APPROVED / REJECTED：仅当前有效审批资格主体；审批人不能等于申请人。
- APPROVED / REJECTED / WITHDRAWN 为该审批修订的终态，不支持直接覆盖历史；后续修改应建立新修订。
- 审批主体的实时成员状态、权限版本、租户及审批配置发布 ID 必须由服务端事务加载；没有 Q09 生效配置时返回 CONFIGURATION_PENDING，不内置一个默认审批角色。
- 拒绝需要非空有效 Unicode 说明；批准、提交和撤回可不提供说明，不新增客户未要求的必填评论。输入原文保留在返回事件中。
- 版本 CAS、时间倒退、UTC 毫秒精度、时间范围、版本溢出与快照状态一致性均有约束。
- 返回新快照和审计事件，绑定相同 notice ID、草稿修订、SHA-256、版本和操作人权限版本；不修改输入快照。
- 审批通过仍 `effective=false`，不允许设备执行或监控静默。审批不等于客户生产批准或运行配置发布。

## 验证

使用正式 Temurin JDK 21.0.12.1 的 `javac --release 17` 编译生产源码，JUnit Platform Console 1.11.4 执行：

- `ChangeNoticeApprovalTest`：14 项（包含参数化展开）；全 5×4 状态/动作矩阵在一个测试中独立断言。
- 加上 `ChangeNoticeMatcherTest`、`ChangeNoticeDraftTest`、`TicketLifecycleTest`，共 85 项，0 失败，0 跳过。
- 同一组合也通过 ECJ 3.40.0 Java 17 目标编译与测试。

Maven 完整构建仍因 JVM 到 Maven Central 的 DNS 失败而未通过；未改变项目依赖版本。这里是领域隔离验证，不是 API、数据库或浏览器验收。

## 还需接入

审批仓储、原子审计/幂等、授权控制器、前端按钮和实际配置发布接线尚未实现。API 必须从新鲜会话和数据库加载 Actor，不得接受客户端的 mayReview / active / policy ID 作为授权。持久化必须原子保存快照与事件并核对当前草稿修订，不能把这个内存状态机等同于数据库 CAS。

后续数据库改动须使用 V37 之后的新迁移并经指定测试环境验证，V1–V37 不改；本次没有数据库迁移或对外请求。合同 TC 仍 NOT_RUN。
