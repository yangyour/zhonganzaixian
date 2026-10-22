# 权限声明目录：读取恢复、范围清理与摘要快照

基线 main 3e02614a74908aa896b7e943b068c406e70b3c94。依据[前端验收标准](frontend-acceptance-standard.md)改进既有只读目录，不创建角色、变更权限或确认客户正式矩阵。

## 本批改进

- 提供取消GET等待与原固定目录端点重试。失败、取消不保留可打印旧目录；成功重试保留同范围本地搜索/维度筛选，恢复后聚焦页面标题。取消聚焦共享状态通知。
- 账号版本、角色、权限、租户及上下文变化同步清除原目录、筛选与恢复目标，旧响应不得回填；权限不可用时明确提示并阻止刷新、打印事件。
- 读取器固定自己的AbortController和原generation，加载通知同步触发新范围读取时，旧调用不得借用新controller多发GET。已复现原先发2次而预期1次的回归，再修复。
- 摘要计算前保存已完成结构/语义校验的独立快照。异步SHA-256等待期间外部响应对象变化，不得让返回值脱离已核验摘要。已复现跨租户/权限位变动后错误返回，再修复。
- 命名可聚焦滚动区域、表格caption/列标题继续保留；按钮、搜索、维度选择采用44px产品目标，增加可见焦点，不宣称视觉或WCAG全面验收完成。

## 证据

新增14项：8项读取器（包含两个先失败后修复回归）及6项真实Vue/ElementPlus原生DOM。覆盖取消、失败、重试、迟到响应、摘要等待、重复请求、账号/能力撤销、筛选保留与打印范围。实际HTTP只检查本地Vite页面/模块，未执行浏览器JavaScript或真实授权变更。

[验证记录](evidence/capability-catalog-recovery-20261021/validation.json)、[派发缺陷原始失败](evidence/capability-catalog-recovery-20261021/dispatch-regression-before-fix.log)、[摘要快照原始失败](evidence/capability-catalog-recovery-20261021/digest-regression-before-fix.log)、[HTTP记录](evidence/capability-catalog-recovery-20261021/http-smoke.json)。

本批执行期间临时目录中的旧工具链消失，工作区源码和依赖仍在；初步定向测试使用系统Node24，随后在可持久工作区从npm官方包恢复Node22.23.4/npm10.9.9，并以该项目要求的版本重新执行最终测试/构建。旧临时日志未假定可读；历史证据以已提交仓库文件为准。

74项浏览器规格及128项正式TC仍NOT_RUN。未改API/数据库/DDL，目录仍是代码声明而非资源访问裁决，Q09客户正式矩阵与签字均不补默认值。
