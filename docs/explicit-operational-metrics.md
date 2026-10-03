# 显式经营指标算术核心

## 2026-10-03 实施范围

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
