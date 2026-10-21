# 未发布备份计划：明确日期预览

基于 main b2bacb5。增加日期换算的只读 API 和现有计划详情中的交互，不增加计划启用、设备执行、调度器、备份存储或新数据库迁移。

## 任务与验收标准

按 `frontend-acceptance-standard.md` 的任务可用性、状态闭环、上下文隔离和 WCAG 2.2 AA 目标实施：

- 用户自行选择具体日期，不预填“今天”或静默推算下一次执行；日期与当前计划版本一并请求。
- 原生带标签日期输入、44px 控件、可见键盘焦点、可换行窄屏布局、加载状态及禁重复提交。
- 可取消；取消中止请求、忽略迟到结果并返回日期输入焦点。修改日期立即清除旧预览。
- 租户、用户、权限版本、角色、上下文代次、计划版本或日期事实变化时中止并清空输入/结果。组件卸载不接受响应。
- 失败不保留旧结果；409 提示先重新读取计划，不用最新版本偷偷替代原版本。历史修订不挂预览表单。
- 展示本地日期时间与 UTC 候选，明确“不是下次执行时间”；上海历史夏令时缺口/重叠不选择偏移，显示无唯一候选。

单元/DOM和构建门禁通过不等于真实浏览器、读屏器、对比度或移动设备验收。已有 19 项 Playwright 脚本仍仅发现，执行受环境限制；不把本次 jsdom 测试称为浏览器通过。

## API 与边界

API 0.71.0 新增 GET `/backup-plan-drafts/{id}/schedule-preview?date=YYYY-MM-DD&expected_version=N`。

仅当前租户授权平台管理员。仓储重新核对当前授权及对象归属；不接受额外查询、重复参数、租户覆盖、无效日期或旧版本。返回 `Cache-Control: no-store` 和 `Referrer-Policy: no-referrer`。

响应严格绑定 plan_id、tenant_id、version、archived、requested_enabled、日期、HH:mm、本地日期时间、Asia/Shanghai；前端检查精确字段集合和互斥结果。JDK ZoneRules 是日期换算来源，不依赖浏览器时区。日期/UTC年份均限0001—9999，UTC下溢拒绝。

enabled/effective/creates_run/execution_authorized 始终 false；next_execution_at/missed_run_policy 始终 null；标记 UNPUBLISHED_CALENDAR_PREVIEW。归档计划仍只能查看日历候选，不恢复或启用计划。成功 GET 不写审计、收据、计划或执行任务；拒绝请求仍由统一异常处理器留下安全审计，不关闭审计。

## 依赖配置槽位

`deploy/examples/backup-execution-dependencies.json` 仅是后续接入清单，不被运行时消费或作为批准发布：执行与生产批准均 false，计划发布、设备授权、SSH适配器、已有凭据绑定、主机信任、加密存储/KMS、保留批准、失败工单映射、演练批准均 null。现有配置注册表的备份参数均保留未发布 null 槽位，不写密码、私钥、厂商命令或实际端点。

## 验证

最终结果与源码摘要记录于 `evidence/backup-calendar-preview-20261021/validation.json`。完整 M6-1 和128项正式合同验收仍为 NOT_RUN，不把时间预览称作备份执行完成。

### 真实服务联调记录

- attempt-1：21项真实PG测试通过，日期候选、越权/参数/版本/跨租户拦截均返回预期状态。驱动错误地要求包含拒绝请求的整段GET完全零写入，因正常安全审计而失败。保留失败和清理记录；未修改产品审计逻辑。
- attempt-2：新临时库V1–V45、21PG、26HTTP及3份实际响应的生产TS读取器回放通过。正常/归档/上海1991年夏令时缺口预览分别核对；成功GET前后105表指纹不变，拒绝GET只有mop_audit变化。
- 重复迁移和只读检查均执行0迁移，105表指纹等值；无新迁移。两轮API/PG进程、临时库和临时凭据均已清理。
- 3份纯合成捕获已成为Web和契约回归夹具；不含登录口令、Cookie或CSRF，不是实际浏览器/外部供应商验证。
- 后端根验证3788项：2792执行通过、996环境门控跳过；完整Web2705、契约164、Python113，类型/构建/静态门禁通过。详情见validation.json。
