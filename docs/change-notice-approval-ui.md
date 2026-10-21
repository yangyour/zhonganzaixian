> 当前谱系：用户 V39 为邮件；审批候选现为 V40，组合 API 0.60。本文及旧证据保留历史 V38/V39 审批记录，不代表当前 V40 已应用。见 [当前合入说明](upstream-email-approval-20261019.md)。

> 历史基线说明：本文保留旧审批分支的 V38 / API 0.58 验证记录。当前上游 V38 是用户企业微信；审批在新谱系为 V39，组合 API 为 0.59。旧证据保持原字节，不能证明本线 V39 已执行。当前映射与验证见 [合入说明](upstream-wecom-integration-20261019.md)。

# D10 明确修订审批页面

## 实现范围

`ChangeNoticeDraftsPage.vue` 中新增独立审批工作区。草稿列表、草稿修订、内部审批、运行生效分别显示，已有草稿 DTO 的未审核元数据不冒充审批聚合状态。这里只实现内部审批，不执行设备命令，不启用窗口匹配/告警抑制，不给出自动 P3 决定。

- 选中草稿后，用户明确点击读取该草稿修订的审批。对象 ID、草稿修订与内容 SHA256 固定，不随列表刷新、选择其他草稿或出现新修订而自动替换。
- GET `/api/v1/change-notice-drafts/{id}/revisions/{revision}/approval` 校验精确字段、当前租户、不可变草稿内容及摘要、审批版本、状态/时间/审核人、明确 false/null 安全边界。
- 审批动作仅来自服务端 `allowed_actions`。技术写权限和 padmin 身份不能推导 Q09 审批资格。页面沿用既有草稿页面入口的技术角色/能力门禁，未修改共享导航或角色定义。
- 用户再次明确准备审批版本后才出现说明输入和动作按钮。SUBMIT/WITHDRAW/APPROVE/REJECT 均需确认明确对象和两个版本；REJECT 必须有说明。刷新不会替换已准备 CAS 版本和原说明；发现更新草稿修订时停用旧审批动作。
- POST `/actions` 精确发送 `{expected_version,action,note}`；审批版本独立于草稿修订。沿用现有 ApiClient 的 CSRF、same-origin、幂等传输，无共享客户端修改。
- 网络/5xx/格式或收据核对失败保留未知结果，固定原路径、版本、body、key；只有显式原请求重试可再次 POST。GET 看到新状态也不当作原请求成功；重试被拒绝不能消除之前的不确定结果。成功只展示验证后的原收据，当前状态和后续权限必须重新 GET。
- 页内草稿写动作与审批未知写互锁。路由离开、正常租户切换、beforeunload 共同保护未确认请求和未保存说明；强制会话/权限上下文失效清除受保护数据并抛弃晚到响应。
- 不可变动作历史按 15 条技术分页读取，核对修订、内容摘要、动作转移、连续版本、角色身份、策略引用与已知收据。窗口/说明/目标以转义文本展示，目标每组最多 10 个。

## 验证与复现

在仓库根目录与 `apps/web` 分别 `npm ci`，不改变 lockfile。项目运行要求 Node 22.x；最终验证使用 22.23.3。

- `cd apps/web && npm test`：完整 Vue/TypeScript 单元测试。
- `cd apps/web && npm run build`：vue-tsc、Vite、静态发布产物与候选安全策略校验。
- 新增四组测试：strict reader、workspace/真实 ApiClient 的合成 transport、Vue DOM panel、宿主页导航/互锁。包括跨租户/畸形快照、server-derived action、CAS/新草稿修订冲突、丢失响应的精确重试/不重复 mutation、撤权后的未知重试、晚到响应、确认中换对象、移动 CSS 规则和文本转义。
- 可复现合成布局夹具：`cd apps/web && npm run dev -- --port 5197`；另一个终端在根目录执行 `node tools/check-change-notice-approval-layout.mjs`。默认只使用回环 Vite 和 `/usr/bin/chromium`，合成数据，无真实 API。脚本计划在 390/1440 宽度检查横向溢出、44px 按钮、目标列表并保存 `/tmp/d10-approval-layout` 截图。环境变量可指定同类本地测试地址、浏览器路径和输出目录。

本轮 Chromium 启动被执行环境的 socket sandbox 拒绝；获准的普通升级执行也在环境 mount setup 阶段失败，浏览器未启动。不继续改变安全设置或绕过限制；已停止本轮自有 Vite 进程。布局截图/浏览器验收为 NOT_RUN，CSS/DOM 断言不冒充视觉验证。

此分支未执行真实 PostgreSQL/API/会话/Q09/浏览器联调、迁移、外部服务、客户生产批准或设备执行。真实 HTTP 与集成验收由集成分支独立记录；全单元通过不能将 D10 整体标 DONE。

实际证据见 `docs/evidence/change-notice-approval-ui-20261019.json`。
