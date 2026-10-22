# 邮件与企业微信：完整绑定输入和单次确认

基线 main 19a42aafa310fa8c425c9c010cfa38b6decc0aca。按[前端验收标准](frontend-acceptance-standard.md)完善两个管理页面的原输入及操作目标保护；没有发送真实邮件/企微消息、修改真实绑定或启用自动通知。

## 本批范围

- 绑定账号/地址或UserID、搜索原文、已应用查询、页码、当前通道状态及精确绑定事实进入完整快照。刷新、分页搜索和离开确认不再只比较dirty或租户，确认期间新输入及clean检查之后的首个新输入均保留。
- 账号版本进入范围清理；同账号的版本替换会清除旧绑定编辑和读取依据，并从第一页重新读取。未知写入继续保留原请求，不用放弃确认清除恢复凭据。
- 测试发送和精确绑定停用使用单次确认锁和独立确认epoch。重复触发不会打开多个可提交确认；旧账号确认的finally不能解锁新范围中的新确认。
- 测试发送校验完整通道状态，而非只看收件人；同一收件人对应的发件邮箱/企业发生变化，也需重新确认。
- 精确绑定停用校验完整原记录，不能因ID和版本没变就忽略地址/UserID被改写。概览停用只接受当前列表原行，陈旧对象不得提交。
- 刷新期间原目标若变化，会拒绝旧确认。确认框取消不清绑定输入；可配置项与真实供应商授权边界不变。

## 验证

新增22项两个真实Vue/ElementPlus页面的DOM/处理器回归，定向包含精确绑定读取器共123项通过。模态框是显式替身；原生DOM不是浏览器模态焦点、视觉或辅助技术验收。

首轮重复确认测试错误地等待第二个未结束弹窗，产生测试超时；修正为并发触发后立即断言弹窗数量。随后用修改前两份源码重放，得到4个明确断言失败（两种通道各一个新输入丢失、一个重复弹窗），再恢复修复后源码验证通过。保留[首轮日志](evidence/channel-confirmation-protection-20261021/initial-regression.log)及[原源码复现](evidence/channel-confirmation-protection-20261021/confirmation-regression-before-fix.log)，不以最终PASS覆盖失败。

[验证记录](evidence/channel-confirmation-protection-20261021/validation.json)、[本地HTTP记录](evidence/channel-confirmation-protection-20261021/http-smoke.json)。无API、DDL或真实外部操作；74项浏览器规格和128项正式TC仍NOT_RUN。站内通知中的独立发送小组件不属于本批两个管理页面的覆盖范围，需单独核对。
