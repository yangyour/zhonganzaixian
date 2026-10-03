# M10-7 显式参数容量估算核心

`CapacityEstimate` 是D13中的纯数学估算器，按开发文档02的8输入/9输出模型实现。它未接入页面、HTTP、配置仓储、真实采样或采购流程，结果始终标记 `estimateOnly=true`。

## 输入

8个输入为hosts、itemsPerHost、intervalSeconds、historyDays、bytesPerValue、trendRatio、trendDays、headroomRatio。数量/天数非负，采样间隔和每值字节数正数；比例用0至1（不是0至100百分数）。

另需显式模型版本、每条趋势记录字节数、单池NVPS上限。这些属于版本化模型系数，不能把原型80/128字节、5000 NVPS等示例变成正式默认值。任一必需值未提供则UNRESOLVED、outputs=null并列出缺项；即使主机数为0，也不绕过缺失模型参数。

## 九个输出及单位

令 I=hosts×itemsPerHost，s=intervalSeconds，b=bytesPerValue，r=trendRatio，t=bytesPerTrendRow，h=headroomRatio，p=poolNvpsLimit：

1. NVPS = I/s（值/秒）
2. 日数据量 = I×86400×b/s（bytes）
3. 历史量 = 日数据量×historyDays（bytes）
4. 趋势量 = I×r×24×trendDays×t（bytes）
5. 单节点估算 = (历史量+趋势量)×(1+h)（bytes）
6. 双节点估算 = 单节点估算×2（bytes）
7. 峰值估算 = NVPS×2（值/秒）
8. Item总数 = I（整数）
9. 池数估算 = ceil(I/(s×p))（整数）

中间分子保持精确，十进制输出按明确的DECIMAL128技术精度舍入一次；不用二进制浮点数。池数基于未舍入输入向上取整，防止商略大于整数时因显示舍入少算一个池。数值使用BigInteger/BigDecimal，数量不依赖32/64位整数溢出行为。bytes不冒充GiB，界面换算应另标单位。

## 证据与未完成范围

10项隔离测试含9项手算结果、缺值不补默认、分母/比例校验、明确0数量、循环小数精度、整数临界取整及超固定整数范围。测试中的80/128等仅是标记明确的合成输入。

Q13实际负载/硬件、Q21资源和Q25/Q30正式系数仍未落实；模型值不代表实测NVPS、监控健康、采购建议或合同容量达标，双节点数学估算也不证明HA或零丢失。API V0.8、私有配置、V1–V6不变，真实组件测试NOT_RUN。运行结果见[独立证据](evidence/dot-capacity-core-20261002.json)。


## 2026-10-03 Proxy缓冲显式乘数核心

ProxyBufferEstimate补齐M4-15/M10-7的纯公式：站点NVPS×最长断网秒数×每值空间bytes×明确余量倍率。前两项非负、每值空间和倍率正数，任一缺失输出null并列缺项；即使NVPS为0也不掩盖其他缺值。不提供80字节/1.4等默认，不把输入每值空间冒充已实测。

倍率是直接乘数，不是自动1+比例。例如明确0.4就是乘0.4，不自动解释成1.4；这是数学语义，不表示低于基础需求的值足够或得到采购批准。高精度十进制乘法中间保持精确、最后一次DECIMAL128舍入，输出单位bytes。测量/接管验证始终false，单Proxy缓冲数学不证明双活或无丢失。

8新纯测试涵盖手算50,400,000bytes、空输入/不补默认、0值不掩盖缺项、负数/分母、倍率语义、小数精度和超固定整数范围。完整根970（957执行含295既有真实PG）/13历史跳过/6MinIO排除、51契约通过；这批PG是回归，不是新Proxy组件联调。首轮新测试var复合声明编译失败，改为明确BigDecimal后完整根通过。公开估算API、页面、保存场景/基准关联、实际Proxy和12,500NVPS×24h压测继续，M10-7/128项非DONE。见[纯核心证据](evidence/niko-proxy-buffer-core-20261003.json)。

提交前同步e6eb115及其本地父提交ee2f77b，保留原指定库时区用例修复和双方验收记录；随后根970及51契约再次通过。本功能不重算或改写已发布维保证据。
