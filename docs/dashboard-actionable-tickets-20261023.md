# UI01 未解决工单、SLA及完整下钻

合同M1-6要求“未关闭且未解决前5条”，M1-1要求待处理数量、已超SLA副标及同范围下钻。新增 `GET /api/v1/dashboard/actionable-tickets?page=1&page_size=5`，首页和 `/dashboard/actionable-tickets` 使用这一兼容的新接口；旧V1非终态及V2 SLA接口保持原有语义。

## 实际契约

`metric_version=MOP_ACTIONABLE_TICKETS_V1`、`projection_version=MOP_ACTIONABLE_TICKETS_SLA_V1`。当前授权租户内部账号可读全租户，客户账号只读本人登记；同时要求`dashboard:read`及`tickets:read`。只接受单值`page`和`page_size`，默认1/5，页大小1–100，页码乘页大小必须在安全整数范围内。拒绝租户、状态、时间或上限覆盖。

数据库在授权的只读`REPEATABLE_READ`事务中验证两种流程头，再统一排除`CLOSED/CANCELLED/RESOLVED`。计数、全量SLA和当前页使用同一人群、版本及观察时间；按登记时间、ID降序。最新五张都已解决时从后续待处理补足五张。未知模型/状态、双模型冲突或不完整数据整次失败，不给替代数量。

`registrations=known_pending+excluded+unconfigured`；只要有未配置流程登记，完整`pending_tickets`为null。`excluded`包含已解决，不称其为终态。V33 `RESOLVED`排除；V42 `PENDING_CUSTOMER`保留。V42虽有`RESOLVED`枚举，现有合法转换不产生它；真实测试不直接SQL伪造该状态。

SLA沿用现有冻结输入和服务端引擎，评估全部已确认待处理；JDBC每批100行只是内存控制，不是结果上限。`evaluated_pending=known_pending`、`unevaluated_pending=0`、`evaluation_limit=null`。SQL单次10秒、完整读取30秒保护触发时返回503，不伪装截断成功。服务档位/预算缺失、V42消费者禁用及V42监控t2尚未接通时仍为未知；有未知输入的完整`overdue_sla`保持null，同时返回已确认超时数。

前端只按服务端`breached`着红和标注“已超时”，剩余量灰色。0剩余本身不推出违约。首页总数、副标、前五来自同一响应；完整列表20条/页，可到最后页，超出末页有回首页恢复。每次翻页获取新快照，不承诺跨HTTP请求冻结。详情后退按原页重读，路由只存页码和不含业务数据的临时视图标识；账号、成员版本、能力或租户变化后回第一页并清除旧行。

## 指定环境与已执行验证

运行前实际核对LAN固定主机信任、项目归属、PG17.9/V50以及受限运行账号。本功能不需要迁移、后台消费者或新组件。隔离测试用受限仓储创建120条明确标记的虚构登记，包含112条待处理、7条排除、1条未配置；不使用实际JiangFaMing租户，不重跑DEMO初始化。

- Java定向66项执行，0失败/跳过：新接口、参数、GET/HEAD、成员撤权、旧V1/V2与时钟回归。
- 指定PG4项执行，0失败/跳过：最新五条已解决补位；112条两页不重复不遗漏，第100条后的超时仍入总数；报告人/租户/权限隔离；并发登记不混入原快照。耗时约304秒含真实登记准备，不作为接口响应性能或SLA承诺。
- PG前后实际租户相关表指纹及迁移历史一致；测试主体及两个测试查看者已停用。见[真实PG证据](evidence/local-astra-ut-20261023/ui01-postgres.json)。
- 根契约206项通过；离线DEMO引用新增一条页面责任，44页仍全部`NOT_RUN`，没有账号/数据初始化。4项DEMO契约通过；Python计划工具17项通过，1项Windows符号链接创建因系统权限1314无法执行，未删断言或记为通过。
- 前端完整回归313文件5221项通过；随后详情返回原页增量定向169项通过。桌面Edge合成浏览器分别核对1024/1440布局、键盘下钻、完整分页、超时/未知/0、撤权、焦点及axe。第一轮缺生产样式的截图判为不具代表性，补齐实际样式/容器后复验；真实HTTP浏览器证据另记。

运行命令：

```text
py -3 tools/probe-ui01-environment.py
py -3 tools/verify-report-capture-isolated.py --suite dashboard-actionable --output .local/<unique-run>
npm test
npm --prefix apps/web test
npm --prefix apps/web run test:browser -- qa/browser/dashboard-actionable.pw.ts
```

候选API已通过匿名拒绝、三个隔离DEMO角色、非法查询拒绝及首页/完整列表核对，实际Edge156.0.4314.42在1440桌面通过三角色页面读取，0页面错误/横向溢出。候选只包含UI01源代码，排除尚未验证的A05草稿；首个候选因独立快照漏复制既有Maven配置目录而启动失败，补齐原始资源后重建通过，没有放宽启动守卫。候选API、Web及SSH转发已关闭，无部署、迁移、业务外发或DEMO初始化。见[HTTP](evidence/local-astra-ut-20261023/ui01-http.json)、[真实浏览器](evidence/local-astra-ut-20261023/ui01-live-browser.json)及[完整分层验证](evidence/local-astra-ut-20261023/ui01-validation.json)。

正式合同TC、完整V42监控t2消费/非空浏览器流转、正式SLA政策及生产性能仍独立留待核验；UI01总组不据此标为全部验收完成。

## dot收尾集成说明

2026-10-24收尾采用main d53697df的完整人群SLA和page/page_size契约，替代此前dot的offset投影。事件指标和其余新增接口保持。真实PG/浏览器证据归属于原独立本地会话，dot仅再次执行代码测试，不冒充真实联调。
