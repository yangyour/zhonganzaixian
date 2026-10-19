# D10 变更报备窗口匹配核心（2026-10-19）

## 范围

基于 `feat/d07-ticket-lifecycle` 的 `d7822ff` 接续。main 的 D05–D18 索引基于 V32，不能覆盖接续分支已有的 V33–V37 实现。

新增 `cn.mop.domain.change.ChangeNoticeMatcher`，实现 M6-7 / M4-17 所需的纯匹配计算，未修改 V1–V37、API 契约、数据库、现有草稿语义或运行门禁。

- 检测事实绑定租户、设备、不可变备份版本及检测时刻；去重键绑定前三者，不因重复采集时刻变化而生成新的键。
- 审批事实绑定报备 ID、修订和原草稿 SHA-256；拒绝自审、错修订指纹及同一报备的多个候选有效修订。
- 必须显式输入已发布规则的租户、release ID、版本、定义摘要、前后容差、两端包含规则和事后审批规则。没有默认窗口或隐含紧急补报许可。
- 匹配要求设备在报备设备集合内、检测时刻位于显式窗口内，并满足事后审批规则。所有命中按报备 ID 排序，可重现且集合不可变。
- `CONFIG_PENDING`：没有窗口策略，不自动判断为未报备。
- `EVIDENCE_INCOMPLETE`：候选集合不完整或相关报备结束时间未知，不能证明未报备。
- `MATCHED`：至少有一项正向匹配；集合不完整仍保留警告。
- `NO_MATCH`：完整候选集合中没有命中，也没有相关未知结束窗口。
- 租户检查先于缺配置返回，跨租户统一 404。非法精度、负容差、时间越界与溢出均拒绝。

## 接线边界

这些输入是计算事实，不是数据库审批、成员权限或有效发布的证明。后续仓储必须在获授权的租户事务中读取有效审批修订、实时成员资格、运行配置发布和完整候选集，再调用匹配器。`complete=true` 不能来自请求参数，不能对单页结果使用。

`NO_MATCH` 不是自动建单授权。D17 真实备份/diff → D15 info 事件 → D10 匹配结果 → D15 自动 P3 建单还需原子持久化和幂等接线。不得调用本核心来直接执行设备命令、静默监控、修改 SLA 或伪造工单成功。

Q09/Q22 客户规则仍未代填。尚未实现审批持久化/API/UI、真实设备采集和自动建单集成；合同 TC 仍未完成。

## 验证

在 dot 云电脑使用 OpenJDK 21.0.12.1、Eclipse ECJ 3.40.0（Java 17 目标）及 JUnit Platform Console 1.11.4，直接编译生产源码并执行：

- `ChangeNoticeMatcherTest`：16 项新测试。
- `ChangeNoticeDraftTest` 与 `TicketLifecycleTest`：既有回归。
- 合计 71 项，0 失败，0 错误，0 跳过。

新测试覆盖显式端点/毫秒边界、容差、缺配置、查询不完整、未知结束时间、不同设备、事后审批策略、自审、错修订、跨租户、重复修订、确定排序、不可变集合、去重键与非法输入。

完整 Maven 路径尚未通过：默认缓存目录不可写；切换 `/tmp` 独立缓存后，JVM 解析 Maven Central 域名失败。此后通过可用下载通道获取正式 ECJ/JUnit 制品执行上述隔离测试，没有更改项目依赖版本，没有用替身模拟数据库。没有运行 HTTP、浏览器或 PostgreSQL 集成测试；私有测试连接资料未在云电脑提供。

复现（ECJ/JUnit 路径由调用方提供）：

```sh
java -jar "$ECJ_JAR" -17 -encoding UTF-8 \
  -cp "$JUNIT_JAR" -sourcepath server/domain/src/main/java \
  -d "$CLASSES_DIR" \
  server/api/src/test/java/cn/mop/api/ChangeNoticeMatcherTest.java \
  server/api/src/test/java/cn/mop/api/ChangeNoticeDraftTest.java \
  server/api/src/test/java/cn/mop/api/TicketLifecycleTest.java
java -jar "$JUNIT_JAR" execute --class-path "$CLASSES_DIR" --scan-class-path
```
