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
