# 未发布厂商 OID 映射候选

M4-19 / Q20 允许先实现 YAML 格式与加载校验。此切片只读取调用方传入的 UTF-8 字节，返回不可变候选，不打开文件目录、不连接设备、没有监控热加载、API、SNMP、发布或回滚执行。`effective()` 与 `sourceVerified()` 恒为 false；解析成功不证明 OID 真实存在或品牌、型号、固件兼容。

## 候选格式 v1

根字段必须恰好包含 schema_version、revision、rollback_revision、brand、model、firmware、metrics。schema_version 必须是字符串 `'1'`；rollback_revision 必须显式提供，可为 null，不能等于当前 revision。型号/固件是调用者声明的适用范围文本，不作通配匹配或自动选择。

每个指标必须恰好包含 id、oid、type、source_unit、target_unit、scale、offset、minimum、maximum。type 为 GAUGE 或 COUNTER；所有数值参数使用加引号的十进制字符串，避免 YAML 隐式浮点解析造成精度丢失。OID 为无前导点的数字弧形式，首弧0–2，前两弧按 ASN.1 约束，各弧最多32位无符号值，最多128弧。这里只检查编码形状，不验证厂商归属。

数值换算严格按显式输入 `raw × scale + offset`，使用 BigDecimal，不推导单位、百分比分母、计数器速率、环绕或阈值。scale 须为正，minimum/maximum 为换算后的包含边界。COUNTER 只允许非负整数原值；输出仍不表示速率。单位是明确标签，当前不核验物理维度兼容；真实换算关系与范围须由设备资料和样本验证，不能以通过此校验解除Q20。算术不舍入，超过技术精度界限或范围即拒绝。

## 解析安全与资源界限

使用已有构建环境的 SnakeYAML 2.4，adapters 显式固定版本。使用 SafeConstructor、拒绝重复/递归键、零集合别名、深度16、码点上限；预读事件还拒绝所有锚点、别名和显式标签，禁止对象构造、合并别名和额外文档。根/指标未知字段、缺字段、错误类型均拒绝。错误只返回通用文案，不附原始 YAML 或嵌套异常。没有 includes、环境替换或网络解析。

技术资源界限：输入256KiB、20,000解析事件、最多512指标、文本128字符、指标ID64字符、十进制整数/小数各最多40位、结果总精度80位。这些是候选解析资源约束，不是合同设备配额、监控性能或生产精度承诺。摘要是输入原字节SHA-256，同字节确定、注释变化会改变；不是客户签字、可信来源鉴定或语义等价证明。

模型复制指标集合，失败不会修改调用者此前持有的候选。尚未实现候选库、活动版本选择器或原子热替换，因此不声称已完成热加载/回滚。

依赖API参照：[LoaderOptions 2.4](https://javadoc.io/static/org.yaml/snakeyaml/2.4/org/yaml/snakeyaml/LoaderOptions.html)、[SafeConstructor](https://javadoc.io/static/org.yaml/snakeyaml/2.4/org/yaml/snakeyaml/constructor/SafeConstructor.html)。依赖许可在锁定JAR内Maven POM标注Apache-2.0；正式生产许可/支持核验仍走Q03。

## 验证边界

测试只使用虚构品牌、型号、固件和OID，不加入任何真实厂商默认值。隔离测试与完整回归结果见[证据](evidence/niko-vendor-mapping-candidates-20261006.json)。API0.45与SQL不变；真实设备、监控接入、持续热加载和完整TC-M4-19仍NOT_RUN。
