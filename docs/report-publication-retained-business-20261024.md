# R02 保留库业务事务与实际PDF验证

复用远端批次已合入的季度报告仓储、发布状态机与FOP渲染器，不重新实现报告流程。2026-10-24在指定LAN现有PG17.9/V53保留验证库完成1个实际集成场景，4个分层检查全部通过，0失败/错误/跳过。输出2份实际PDF、3份HTML；两份PDF共4页，Poppler逐页渲染检查通过，规范化文本与各自HTML一致，页边界检查无越界。

此为真实仓储和渲染验证，不代表客户来源、模板、费用或正式交付已获确认。接口、业务实现及冻结SQL均没有因本次验证改变，普通运行入口对活动`mop_test`的限制保持。

## A：实际来源捕获仍保留批准门禁

新建明确SYNTHETIC的模板及执行单，通过实际仓储捕获季度来源；之后更新执行单，原冻结季度文档保持不变。实际`prepare()`生成`CUSTOMER_DELIVERY`文档，保留`SELF_REPORTED_SOURCES_UNVERIFIED`和`TEMPLATE_APPROVAL_UNVERIFIED`。

首次禁用字体，真实HTML入库，状态为`FAILED/FONT_UNAVAILABLE`，没有替代PDF；同命令重放不重复渲染/修订/审计，变更请求体或使用旧版本被拒绝。启用本机SHA锁定的既有`simhei.ttf`后重新渲染，同一原文恢复为双产物，PDF绑定准确HTML摘要及字体组合身份。来源未核验时APPROVE仍422，PUBLISH被状态守卫拒绝；RETURN可持久化，RETURNED后没有修订生成命令，不能直接重新RENDER。

## B：单独的ISOLATED_TEST状态机夹具

使用不同文档ID，以明确测试SQL准备一个`ISOLATED_TEST`初始DRAFT，只有此独立文档的初始缺失理由为空；原季度输出保持，A的来源、文档、理由和历史不变。此bootstrap不经过真实客户来源的批准门禁，因此B的成功不能算A已可交付。

初始状态之后全部调用真实仓储及实际FOP：禁止自审，指定审核人可批准；策略变更或接收人停用阻止发布，失败不留下半条事务；恢复测试接收人后可发布且重放不重复产生意图。只有选定接收人可列表/读取/下载，未选接收人与跨租户对象404；重复阅读保持首次时间和单行回执。RESEND只追加一次禁用外发的意图，PDF原字节保持；发布人撤权后旧命令回执不能重放。客户模板确认与正式费用配置为null，客户验收及外发始终false。

## 环境、工件与退出

```text
py -3 tools/verify-report-publication-business.py --source-summary .local/candidate-schema-chain-attempt-1/summary.json --output .local/report-publication-business-attempt-1 --session local-astra-ut --timeout 600 --pdf-mode REQUIRED
```

默认REQUIRED必须实际生成两份PDF并完成B流程，缺字体或PDF失败不会自动转HTML成功。显式HTML_ONLY仅用于单独排障，明确记PDF/B未运行。字体只读取本机既有文件，不复制或提交；最终PDF是通用冻结输出的技术排版，不视为客户正式模板批准。

目标精确匹配原R02保留库owner/marker、V53首次checksum/安装时间和完整history摘要，Java模式`PREAPPLIED_V53`没有Flyway或DDL路径。seed之前原子持久化精确2租户/6主体的范围清单，JVM退出且目标库0连接后才逐项核对并停用；六主体已停用，退出时新停用五条成员关系，另一条发布人成员已在负向用例中停用。全部数据库及不可变记录保留，环境锁已释放。

实际PG前后均17.9，活动`mop_test`保持V50、0迁移；history、能力字典、指定实际租户及保留库旧范围摘要不变。没有新组件、部署、设备操作、实际通知或删除。[业务与保护证据](evidence/local-astra-ut-20261023/r02-retained-business/summary.json)、[四页PDF检查](evidence/local-astra-ut-20261023/r02-retained-business/pdf-qa.json)分别保存；运行内`visual_review=NOT_RUN`是渲染阶段原始记录，之后的实际逐页检查由独立QA证据补充，不改写原运行文件。

R02仍IN_PROGRESS：真实HTTP/浏览器、月报/体检/年度适配、修订生成与未读跟进、权威来源核验、正式模板及客户批准仍未完成，正式TC保持NOT_RUN。本次编译和集成验证不冒充这些剩余工作。
