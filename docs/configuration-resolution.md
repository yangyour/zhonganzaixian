# 配置作用域解析与不可变快照核心

2026-10-01 实现 D04 第4–5步及 SET-D15/19 的纯Java核心，代码位于 `cn.mop.domain.configuration`。它接受明确的配置定义、获授权对象作用域、带版本的发布及客户核验事实，返回已解析结果或保留null的 CONFIG_PENDING 诊断。

覆盖层顺序、允许作用域、类型、范围及关联Q编号均由定义输入提供，不从原登记值或内部 APPROVED 提案推断。选择层上的未配置、未生效、过期、冲突或证据缺失阻止回退；有效期采用左闭右开区间。不同租户的记录不能进入解析结果，错绑的核验/发布资料不会进入诊断快照。

诊断和创建时快照保留配置版本、作用域、时间及缺失原因，深层复制JSON值，区分null与0/false。创建所需配置未解析时返回422 CONFIG_PENDING；后续输入变化不能改变已创建的快照对象。敏感配置只支持引用形状，错误类型输入从诊断中脱敏；引用及客户资料真实性仍须由实际适配器核验。

本核心没有接入现有仓储、API或正式发布流程，没有持久化快照或激活登记值。客户资格/证据真实性、每个键的层级和范围、发布记录来源及真实PG事务仍待完成；不能把纯Java中的合成事实当成客户批准。

实际验证：Java17以 `--release 17 -Xlint:all,-serial -Werror` 编译真实源码通过，15组共250个隔离断言通过。15个JUnit包装及所有运行组件联调未执行。可复用的断言程序随Git提交，机器证据见 [独立验证](evidence/cloud-configuration-resolution-verification.json)。

复现纯测试（先激活云工具）：

```bash
mkdir -p .local/configuration-resolution-check
javac --release 17 -Xlint:all,-serial -Werror -d .local/configuration-resolution-check server/domain/src/main/java/cn/mop/domain/DomainException.java server/domain/src/main/java/cn/mop/domain/configuration/*.java server/persistence/src/test/java/cn/mop/persistence/configuration/ConfigurationResolutionChecks.java
java -cp .local/configuration-resolution-check cn.mop.persistence.configuration.ConfigurationResolutionChecks
```

此命令不连接组件、不执行迁移，也不证明D04或128项合同整体验收完成。
