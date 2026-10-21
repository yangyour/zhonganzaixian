# 显式采样观察窗诊断核心

基于 main 48f0af7。Q06允许候选规则模型和模拟触发验证，但不允许把冲突阈值、恢复方向或心跳参数默认为已确认。本切片实现独立纯计算核心ObservationWindowAnalysis，不接入事件流、不保存/发布规则、不更新监控状态。

## 输入与语义

调用方明确提供比较符/阈值/单位、候选保持时长、允许最大样本间隔、观察时刻及有序样本。无系统时钟和默认间隔。十进制使用BigDecimal，沿用既有阈值诊断的技术边界，不做浮点舍入、单位转换或比较方向自动反转。

最多1000条是计算资源上限，超过拒绝而非截断。时间要求UTC年份0001—9999、整毫秒、严格递增、不晚于观察时刻；重复、乱序、未来和空样本引用拒绝。KNOWN必须有数值与单位；UNKNOWN/COLLECTOR_ERROR不得携带数值，可保留已知单位元数据，不因空值而写0。

候选算法EXPLICIT_MATCHING_OBSERVED_SPAN_V1：

- 只计算末尾连续满足显式谓词的“已观察采样点跨度”，不证明样本之间物理状态持续正常。
- 未知/采集错误/不满足条件均打断跨度；两点间隔大于显式maxGap才打断，等于边界不打断。
- 最新点距观察时刻超过maxGap为STALE_LATEST，保持未知；不把最后采样到观察时刻的无样本尾部加进持续时长。
- 跨度达到明确hold可得MATCHING_SPAN_REACHED，但candidateSpanReached只是候选计算值，绝不是恢复确认。
- 缺条件/hold/maxGap返回CONFIG_PENDING；负持续/间隔或已知样本单位不匹配返回CONFLICT，不猜默认值或换单位。
- 空样本NO_SAMPLES、最新未知UNKNOWN_LATEST、最新不满足NO_CURRENT_MATCH分开；未知计数/跨度/结论返回null，而不是0/false。
- 明确hold=0、maxGap=0不是缺失；单个匹配点可达到显式0跨度，但一毫秒后就超出0间隔新鲜度。

结果固定publishable/monitoringApplied/eventStateUpdated/recoveryConfirmed/writesPerformed/unitsConverted=false、effectiveRule=null、customerConfirmation=UNVERIFIED、physicalRangeCheck=NOT_RUN。候选阈值之间的方向与滞回一致性仍由既有ThresholdConditionAnalysis独立检查，本模型不将单个谓词推断成正式恢复规则。

## 验证与后续

24项纯测试覆盖精确比较/开闭边界、亚毫秒阈值不舍入、跨度等号、采样断点、未知与错误、无样本、陈旧、尾部不外推、显式0、单位、时钟、不可变及1000条上限。初次测试导入两个同名Status/Result通配类型导致编译失败，已改明确类型导入；随后无效单位的异常类型未统一也由测试发现，现统一为400验证错误。失败均未计为通过。

本切片尚无HTTP端点、页面或实际采样源；下一步接受保护的只读诊断与前端恢复流程。API0.71.0/V1–V45不变；不是监控恢复观察窗的生产激活或M4完整验收。具体回归汇总见evidence/observation-window-core-20261021.json。

## 受保护只读 API（API 0.72.0）

新增POST /api/v1/monitoring/observation-window-diagnostics，仅remote_test当前授权padmin和configurations:read可用；重新读取成员，验证用户/租户/有效状态/权限版本，先授权后解析，仍要求CSRF。没有新增角色授权或幂等写入。

输入精确5字段：condition、hold_seconds、max_gap_seconds、observed_at、samples；样本精确recorded_at/state/value/unit。条件可显式null或部分未定；已知样本值/单位必需，未知/错误值null。十进制必须字符串，拒绝数字、指数、负零；UTC严格解析拒绝24点/闰秒归一化。成功原样回显输入及精确结果，绑定当前tenant/actor/permission_version。

512KiB、5层JSON、16000 tokens、1000样本是拒绝而非截断的技术上限。重复键、尾随文档、全部query、租户/规则/授权覆盖拒绝。no-store/no-referrer；不读取监控供应商、规则库或客户批准资料。成功诊断不写审计/收据/规则/事件；拒绝保留统一安全审计。

15项新增控制器边界测试通过。新临时PG/API38项HTTP全部预期：匿名/非管理员/CSRF拦截、14组候选场景、另一已授权租户及9组无效输入；15份成功捕获覆盖8种状态，均通过OpenAPI严格校验。成功调用前后105表指纹不变；拒绝只有mop_audit变化，0规则保存/事件更新。临时进程、数据库和凭据已清理。

初次契约回归提示新POST未列入“明确只读例外”；依据实际零写入实现与实库验证，仅增加该精确路径，保留全部其他业务写入的幂等/CSRF门禁。没有放宽通用规则。

当前前端入口仍待接续，不把HTTP接口当作已完成页面。证据见evidence/observation-window-api-20261021/validation.json；V1–V45不变、正式TC仍NOT_RUN。

## 前端诊断工作区与入口

新增/observation-window-diagnostics路由及资源监控导航，路由要求padmin/configurations:read，页面再检查remote_test；阈值条件诊断页提供关联入口。没有给其他角色增加能力，也不自动读取现行阈值或真实采样。

- 初始空白；空结构须用户明确填入且不发送，观察时刻保持null直到用户补全。精确JSON字段/字节/深度/token/重复键、小数字符串和时间顺序先本地拒绝，但不会提供本地“成功”替代。
- BigInt独立复核服务器的状态、理由、末尾跨度/计数、精确小数、原输入回显及actor/tenant/permission。只校验真实响应，不把计算器结果当API成功。等值精确小数表达允许，低于浮点分辨率的差异仍拒绝。
- 编辑、取消、上下文变化或卸载中止旧请求，迟到结果不能覆盖新范围。取消保留输入并返回编辑器焦点；重复提交被抑制；读取失败清旧结果，重试保留同一显式输入。
- 清空、换结构、主动离开或切租户提示丢失内存输入；取消保留，旧确认不能清掉新范围。beforeunload提醒；强制失效先清私有输入。
- 结果按8状态说明，缺值显示未知/未定而不是0；始终明确不是实际恢复、发布或事件写入。关联标签、说明、状态通知、可键盘滚动的原输入回显和44px操作目标按前端标准实现。

67项新增Web用例（50读取器/状态模块、17原生DOM页面）覆盖15份实际HTTP捕获、回显/伪授权/精确值篡改、取消和竞态、角色/租户/模式变化、离开与确认保护、空值和导航门禁。首次TypeScript检查暴露测试Mock泛型适配问题，已在测试传入点明确签名，未削弱生产类型。

新增7项Playwright脚本：320/390/768/1440键盘/回流/axe、取消焦点、撤权、离开确认。总26脚本可发现，仅证明可加载；实际浏览器仍受环境限制NOT_RUN，未生成或声称验收截图。独立合成入口仅开发环境，不进入生产构建，也不是后端授权测试。

完整回归/构建标识见evidence/observation-window-ui-20261021.json。代码合并不等于部署原测试站；生产恢复策略消费和完整合同验收仍未完成。
