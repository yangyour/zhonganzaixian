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
