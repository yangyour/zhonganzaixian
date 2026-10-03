# D04/D05 原请求与展示快照隔离

## 实际缺陷

四个较早的状态模块（assetDependencyWorkspace、assetMetadataWorkspace、configurationDraftWriter、configurationRevisionWriter）此前state()返回内部对象，通知回调仅复制顶层。调用者修改返回的attempt.key/payload/path/state或嵌套baseline，会连带改动下一次原请求重试；这破坏“原body、原目标、原版本、原幂等键”约束，即使服务端依旧执行授权/CAS。

对每模块分别从getter与回调篡改业务快照，共8项回归先失败（该轮85项中8失败/77通过）。本次统一使用detachedWorkspaceState，业务字段通过structuredClone形成独立快照；每次getter和回调都重新复制，原命令、回执、历史及基准不再与UI对象共享。Error作为不透明诊断值保留原引用和ApiError原型/状态码，未宣称错误对象也深度不可变；命令状态转换不从该展示错误值恢复请求。

没有更改请求协议、原键策略、动作权限或恢复门禁。未知结果仍禁止另建请求；普通刷新仍只读；403等原重试拒绝仍保留旧不确定性；强制租户/身份上下文切换仍取消并清空。它修复应用内别名污染，不替代服务端验证，也不声称能抵抗已控制应用执行环境的恶意脚本。

## 验证

8缺陷回归修复后通过，另4快照工具测试覆盖嵌套数据、多个快照、Error原型及null/undefined；全804项Web/typecheck/build/70契约及生成一致性通过。现有>500kB打包建议仍在，未冒称DOM/性能验收。

实际编译四模块并连接niko原PG/OIDC/API0.33，16次真实HTTP验证：依赖图/手工资料/旧登记基准只读，分别修改getter和回调快照后再读，内部基准不变；配置修订新建1份明确合成提案，第一次真实200被客户端主动丢弃，篡改外部attempt副本后仍按原path/body/key回放，创建审计恰好1条；独立回执也不受UI嵌套历史变更影响。随后按真实API撤回本次提案，撤回审计1条。合计3次POST尝试、2个逻辑写动作（创建/撤回），提案最终WITHDRAWN、effective_value=null、客户核验仍UNVERIFIED。其余三模块原未知写的副本污染由隔离单元覆盖，不把它们的只读HTTP误记为新增真实写恢复证明。

415条登记本轮前后完整DTO摘要一致；未新增身份/权限/迁移、未启动Worker或重启API。私有控制记录只定位本次自建合成提案以防重复执行，未输出凭据。真实200丢弃是受控客户端故障，不是物理网络断链。日期修复d8bff785 CI37160197413 success已回读。见[HTTP证据](evidence/niko-workspace-snapshot-client-http-20261003.json)、[回归证据](evidence/niko-workspace-snapshot-isolation-20261003.json)。D04/D05及全部128项仍按各自真实验收门禁推进。

## 2026-10-03 同类问题扩展至另外七个写模块

继续排查发现内部备注、客户评论、工时、工作日历、人工恢复、维保、手工事件七模块同样暴露内部attempt。每模块getter/回调各一项回归，共14项先失败（该轮160项中14失败/146通过），然后接入同一业务快照隔离函数，160项定向全通过；全818Web/typecheck/build/70契约及生成一致性通过。

测试先执行真实导出的状态模块及其正常准备流程，再制造隔离传输未知结果，修改外部attempt的key/payload/state，确认内部原请求不变且重试path/method/key/body完全一致。没有为这些纯别名测试创建本机组件或新业务记录。本次七模块没有新增真实HTTP、设备、存储或DOM验证；前一四模块16HTTP证据不重算为七模块新联调。API0.33、V1–V19、账号权限、正式SLA/告警/维保供应商/客户确认门禁均未改变。前一快照修复77f20a77 CI37160849771 success已回读，见[本轮证据](evidence/niko-remaining-writer-snapshots-20261003.json)。

## 2026-10-03 七个只读模块及证据游标隔离

只读模块也存在相同业务对象共享：审计、日历读取、手工事件读取、资产资料查询、配置对照、来源诊断、报障operation。外部改写proof.next_from_sequence可能改变下一段审计校验范围；展示对象的版本/查询游标/目标引用也不应反向污染已验证快照。配置对照还需与读服务返回的原数组脱离，避免另一处编辑源对象后改变完整对照。

15缺陷回归先失败（该轮179项中15失败/164通过）；修复getter与回调，三个联合状态reader在接受新结果时也先复制源数据。公共工具只为顶层error/proofError保留不透明异常原型/分类，其他业务字段全部独立复制；不为无error字段的idle/ready状态补造error。18新增纳入全836Web/typecheck/build/70契约通过。构建main chunk仍501.63kB并保留原>500kB建议，未提高告警阈值或冒称性能已验。

实际七模块只读16次GET，均来自原niko PG/OIDC/API0.33已有数据：审计15行及末20条局部链VALID（非全库、非外部锚定），日历/手工事件/资料查询各15行且选中详情验证；配置对照源数组、配置诊断及既有报障operation均验证副本改动不影响内部快照。415登记DTO前后摘要相同，无业务写或补造样本。实际审计下一段范围保护由隔离单元覆盖，HTTP仅证明本次真实返回与局部链证据保持；不扩大为全链或浏览器证明。前七写模块ac3c0494 CI37161242225 success已回读，见[HTTP](evidence/niko-reader-snapshot-client-http-20261003.json)与[回归](evidence/niko-reader-snapshots-20261003.json)。

## 2026-10-03 D05导入工作区只读事实视图

导入工作区使用Vue ref而非上述普通state结构，也曾把上传/解析/映射attempt及source/artifact/operation/policy、file/format/busy直接暴露为可写ref。4项新回归先失败（29项中4失败/25通过）；现向组件返回Vue深只读视图，内部工作流仍可按实际API回读更新这些ref。原上传File内容/格式/键、原解析参数与expected_version、原映射位置/忽略列/body/key无法被普通展示引用赋值改写；来源版本、原始表头/单元格、技术政策can_import也不能从展示端反写。

这些是随内部状态更新的只读响应式视图，不是独立历史快照；也不声称能阻止已控制同一执行环境并刻意取raw对象的恶意代码。selection和mappingDraft作为明确的用户编辑区继续可写，修改新表单不改变已冻结的旧请求。错误/字段提示属于诊断展示，未被宣称深度不可变。列工具签名接受深只读列，表格只复制列数组容器以适配现有AppTable，不改列内容或15列业务规则。

4回归修复后，导入/映射定向35项、全840Web/typecheck/build/70契约与生成一致性通过。实际生产模式Vue模块连接API只做3次GET（会话+两次技术政策），验证政策与本地选中文件/格式只读、销毁仍清空；0上传、0存储/解析调用，can_import=false。上传/解析/映射原重放保护仍是隔离单元证据，没有用模拟源替代当前OSS真实联调；技术政策可读取不代表存储可用。DOM/390px新改动未验，原指定服务器的旧浏览器证据不扩大。前读取快照12f0b9ad CI37161799473 success已回读，见[政策HTTP](evidence/niko-import-readonly-policy-http-20261003.json)与[本轮证据](evidence/niko-import-readonly-refs-20261003.json)。
