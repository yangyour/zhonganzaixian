# 显式经营指标算术核心

## 2026-10-03 纯核心提交范围（eaa7d48，CI37130724964 success）

M10-5、M10-10、M10-12的可独立验证切片：ExplicitOperationalMetrics只计算调用方明确提供的样本中位数和活跃租户数/FTE。尚无公开API、项目数据读取、页面或正式经营报表；现有API契约仍0.28，没有偷偷增加路径或改变旧响应。

- 耗时单位毫秒，输入为非负整数，最多10000条、每值最多40位（技术安全上限，不是业务配额）；复制排序，不修改原列表。偶数样本取中间两项精确平均，保留0.5毫秒；BigInteger避免固定整数溢出
- 未提供集合、空集合、含未知样本分别INPUT_UNAVAILABLE、EMPTY_SAMPLE、UNKNOWN_SAMPLE；缺失样本不滤除，未知不当0。显式0和重复值保留
- FTE等于显式月度值班FTE与二线FTE精确相加；活跃租户/FTE仅最终除法使用DECIMAL128。0租户/正FTE为明确0；分母0为UNDEFINED，结果null；缺输入保留null且列出字段，不默认人员配置或换算系数
- 非负和40位整数/40位小数边界检查不因其他字段未知而跳过；结果构造器拒绝状态、原因、计数或值互相矛盾
- source固定EXPLICIT_INPUT_NOT_PROJECT_METRICS；Q27耗时定义、Q30活跃租户范围、Q23人员折算未核验。effectiveProcessingDuration、effectiveTenantsPerFte、targetMet均null，billingApplicable=false

正式处理耗时与监控MTTR不能混用；算术结果不证明项目真实样本、人员配置、目标达标、正式成本、收入或利润。后续HTTP层必须显式映射DTO，并把精确数值编码成十进制字符串；此处record附加方法不等于自动JSON契约。

## 验证

14项新增纯核心测试通过；完整根真实PG回归1050报告=1037执行（含既有322真实PG）+13历史fixture跳过，6MinIO排除，0失败/错误。新增14项没有组件依赖，不把既有PG回归当成本功能真实数据来源联调。55契约测试和生成一致性通过；本次未修改Web、未重跑浏览器。V1–V16字节不变，未执行DDL。

证据：[本批验证](evidence/niko-explicit-operational-metrics-core-20261003.json)。API/数据源/页面、客户口径、正式经营指标与合同完整验收仍未完成，三项均不标DONE。


## 2026-10-03 API0.29显式输入对照

POST /api/v1/operations/explicit-comparisons以当前有效成员、operations:compare_explicit能力（仅padmin）与CSRF保护。授权在解析请求前执行，每次重新查询成员，不接受客户端tenant_id/query覆盖。技术请求上限512KiB、10000样本、JSON深度3、重复键/尾随内容拒绝；两个完整组elapsed_millis/staffing可显式null，非null staffing必须提供active_tenants/on_call_fte/second_line_fte。数量/小数为规范十进制字符串，不接受JSON数字、指数、符号、前导零/空白。

成功响应返回当前租户、原显式输入、样本中位数/未知计数和FTE计算；精确结果为BASE10_STRINGS。各UNRESOLVED/UNDEFINED/CALCULATED原因独立校验，正式处理时长/人力指标/target_met仍null，不返回达标或计费结论。当前没有正式均值/P95/MTTR/工单指标、实际工程师/活跃租户查询，不能替代M10-12四处统一统计服务。无业务持久化、不要求幂等键；拒绝仍按已有安全审计记账，成功只读不加业务审计。

新增11控制器测试、完整根1061=1048执行（含既有322真实PG）+13历史跳过，6MinIO排除；57契约、643Web/类型构建通过。真实新PG/OIDC/API批次64HTTP逐响应Ajv校验，涵盖六会话、第二管理员、4类非授权身份403、匿名/CSRF拒绝、精度/空/未知/0分母、10000×40位边界、10001拒绝、严格JSON、真实租户切换与恢复、目录24项和成功只读审计尾不变；0业务记录写入。原样本都是明确合成输入，PG仅用于身份/审计，不冒充真实经营数据源。

首次测试源码使用了异常status()而实际是字段，编译失败后修复；首次HTTP脚本缺少租户切换幂等键，切换400，补齐该既有写入口要求后完整64通过，不放宽服务端。此前失败批次不计入64。API固定JAR正常停启加载本次构建，没有执行迁移或修改组件凭据。无新DOM/存储/原指定服务器验收；下一步接入实际前端对照及状态保护，仍不标合同DONE。

[API实施验证](evidence/niko-explicit-operational-api-20261003.json)、[64真实HTTP](evidence/niko-explicit-operational-api-http-20261003.json)。


## 2026-10-03 实际前端页面与响应校验

新增 /operational-comparison、桌面与移动导航（operations:compare_explicit），独立页面明确标注仅输入对照。样本有未提供null、明确空集合、逐行提供三种状态；逐行规范整数或?未知，CRLF只作换行，不丢空行、不排序改写原文、不填示例。最多10000项，空白人力字段null；人力输入组由用户明确启用，不自动从人数/出勤推断。

实际workspace先校验输入，再调用既有API；使用BigInt独立核对精确中位数、FTE合计及最终DECIMAL128，验证全部响应字段、原请求输入/租户/门禁，不显示本地算术作为API成功。修改立即清旧/取消当前请求、重复点击抑制，失败不保留旧结果，权限/租户上下文变化与卸载拒绝迟到回包且清空；不自动请求或保存。未知/空/0分母用“—”及原因，明确0显示0；不画经营达标图或提供正式导出。

15项新增Web（14状态机/输入/精度，1源码接线）共658通过，类型/构建与57契约/生成一致性通过。实际前端模块+ApiClient另23真实HTTP、20只读POST尝试、0业务写入：含10组边界/未知、10000×40位、超安全整数/极小FTE/大FTE和最终舍入、实际200丢弃与四个实际200迟到、重复点击、伪造本地管理员被真实MSP403、成功只读审计尾不变。受控传输延迟/丢回包不是物理断网、进程崩溃或浏览器DOM验证。

API20ca9c8的CI37131675736 success已回读。页面源码44px/单列/长数换行已构建，B03真实浏览器阻塞仍在；页面控件/DOM/截图、真实经营数据、导出及完整128项不标DONE。见[页面与模块验证](evidence/niko-operational-comparison-client-20261003.json)、[23HTTP](evidence/niko-operational-comparison-client-http-20261003.json)。
