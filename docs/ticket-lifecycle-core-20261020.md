# D07 工单生命周期候选纯领域

依据剩余功能 D07 的代码核查补充和合同 M5-3、M5-14 至 M5-17，实现独立纯领域 `cn.mop.domain.ticket.TicketLifecycle`，复用既有客户关系与说明校验。

## 候选转移矩阵

| 动作 | 来源 | 目标 |
|---|---|---|
| 接单 | NEW | IN_PROGRESS |
| 转派 | NEW / IN_PROGRESS / WAITING_VENDOR / REOPENED | 原状态 |
| 待厂商 | IN_PROGRESS / REOPENED | WAITING_VENDOR |
| 恢复处理 | WAITING_VENDOR | IN_PROGRESS |
| 提交解决 | IN_PROGRESS / REOPENED | PENDING_CUSTOMER |
| 客户确认 | PENDING_CUSTOMER | CLOSED，CUSTOMER_CONFIRM |
| 客户反馈未解决 | PENDING_CUSTOMER | REOPENED |
| 超时关闭 | PENDING_CUSTOMER | CLOSED，TIMEOUT_AUTO |
| 关闭后重开 | CLOSED | IN_PROGRESS |

保留合同六态与 M5-15 明文的 REOPENED 扩展。RESOLVED 不擅自加入直达关闭或跳过客户确认的路径；其具体入口与完整设计图仍需 M5-3 书面确认。本矩阵是可测试候选，不是客户已批准流程，也尚未成为 API 运行配置。

## 保护与边界

- 输入快照不可变，每次动作校验 expected_version、时间不倒退和版本 / 重开计数溢出。
- 客户确认 / 未解决绑定原解决快照 hash；工程师不能代替客户确认，跨租户统一 404。
- 转派原因必填，原 SLA 两个截止时间逐字保留；待确认返回暂停恢复时钟的计划，重开返回恢复原时钟的计划，不计算新的 SLA。
- 解决说明最小字符数、确认超时、重开窗口、服务经理提醒阈值由显式 Policy 提供；缺少动作所需项返回 CONFIG_PENDING，不补业务默认。
- Unicode 字符按码点计数，边界空白不计入最低字数。既有文本控制字符、无效代理对保护保留。
- 通知标志只表示需要创建通知意图，不是已送达；超时入口仅为未来持有 fencing 租约的 Worker 适配层准备。
- 当前方法不替代完整 CapabilityCatalog 权限、负责人关系、CSRF、幂等、CAS 数据库提交、审计、有效配置发布或客户书面批准。未来 JDBC 仓储须在同一授权租户事务加载真实事实。

## 验证

真实 JDK 17 编译与 JUnit：新增 14 项测试、既有客户规则 16 项，共 30 项全部执行通过，零跳过。转移矩阵逐一检查 7 个状态 × 9 个动作，非法边均拒绝；另覆盖确认凭证、精确超时 / 重开边界、角色 / 租户、配置缺失、版本冲突、计数溢出及 Unicode。

本批无数据库迁移、无持久化 / HTTP / Worker / 页面接线、无真实组件联调或部署。D07 及 128 项合同正式验收不标 DONE。后续仍需扩展表、真实事务验证及接口接线。

随后使用 Maven 3.9.9、JDK 17 和严格依赖校验，完成 domain / persistence / adapters / api 模块编译及上述两组 JUnit 测试，BUILD SUCCESS。初次构建因 Java 未采用既有环境代理及系统信任库失败；按现有代理和系统 CA 配置后通过，未关闭 TLS 校验或更改系统安全设置。远程组件测试不在本次定向构建范围内。
