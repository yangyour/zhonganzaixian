# 事件全局搜索真实 PG / HTTP 验证（2026-10-19）

本记录仅覆盖 dot 自有云端、全新可丢弃的回环 PostgreSQL 17.9 / ICU 76.1 与实际 API。未访问原服务器、用户电脑、外部供应商或设备。

## 已验证结果

- 真实 PostgreSQL：32/32 通过，0 失败、0 错误、0 跳过。其中既有 PostgresGlobalSearchIntegrationTest 9 项，新增 PostgresEventGlobalSearchIntegrationTest 23 项。
- 实际 HTTP：72/72 通过。以真实项目本地账号、CSRF 与会话执行搜索/详情；显式引导六个既有合成身份，五个身份参与 HTTP 场景，两个租户共 45 个合成事件。
- 覆盖固定四项上限、第五行溢出检测、同时间 UUID 降序、Unicode 大小写与 % / _ / 反斜杠字面量、租户隔离、最小字段、客户明确无权、当前自定义权限和即时撤权。PG 还验证两连接并发写入/撤权下的同一只读 REPEATABLE READ 快照。
- 检索结果中的旧事件不在最近 25 项内，仍可按 ID 直接打开授权详情；跨租户详情为 404。
- 31 次成功的 HTTP 搜索/详情请求前后，对全部 97 张表计算完整行指纹，均不变。故意拒绝的请求另行验证；既有安全层会为拒绝请求写审计，不将其混入成功只读指纹窗口。

## 迁移与精确制品

测试后端快照为 c26d4f584565400babba131e201e94882f842473；API JAR SHA256 为 db33d6d1d368a1d652847794e6f7fcc13b55ea48e8b8b9bf15c5bafb9fdcb803。

新空库显式初始化 V1–V4，随后逐个执行 V5–V41 独立迁移命令。V1–V41 源码、编译资源与嵌套 JAR 字节全部等于已发布 b21bb8c699f62d33cd7ec6ad5b00a3bd278995dc；未新增迁移。重复 V41 与只读校验未改变全部 97 张表。971 个嵌入应用/模块编译项匹配 JAR，测试前后 2,126 个快照源文件、类与 JAR 哈希不变；直接执行已批准编译类，不在测试时重新编译。

后续 32c28e 测试/fixture 补充及前端修正不倒算进该快照或以上真实测试计数。证据打包时另核对集成提交 806e513 与后端 32c28e 的 435 个生产源码/资源文件，全部与已测快照一致，后端 JAR 哈希亦相同。最终仓库全部文件不因此被声称属于该快照。

## 首轮记录与清理

首轮真实 PG 32 项全过；HTTP 测试尚未启动，因为验证脚本只传五个身份，而显式账号引导契约要求六个。仅修正脚本，未改后端、SQL 或已批准制品；首轮证据完整保留。随后在另一全新临时库重复完整流程并得到最终通过结果。

最终 API / PostgreSQL 均已停止；临时数据库、类路径目录与随机凭据已移除，两个回环端口已确认关闭。Email / WeCom / OIDC 禁用，继承供应商配置被剥离，未发送外部消息。

## 范围边界与证据

客户事件组仍为 NOT_AUTHORIZED；自动化脚本搜索仍为 NOT_IMPLEMENTED。这里不声称浏览器视觉验收、生产批准、外部交付或全部 128 项完成。

完整结果：[summary.json](evidence/event-global-search-real-20261019/summary.json)。
场景列表：[真实 PG](evidence/event-global-search-real-20261019/real-pg-test-cases.json)、[真实 HTTP](evidence/event-global-search-real-20261019/real-http.json)。
快照：[snapshot.json](evidence/event-global-search-real-20261019/snapshot.json)；首轮：[attempt-1/summary.json](evidence/event-global-search-real-20261019/attempt-1/summary.json)；生命周期：[lifecycle.json](evidence/event-global-search-real-20261019/lifecycle.json)。
