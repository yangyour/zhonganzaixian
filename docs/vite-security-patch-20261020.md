# Vite 7.3.7 安全补丁（2026-10-20）

## 触发与修复

口径绑定分支的 [CI 39514486747](https://github.com/yangyour/zhonganzaixian/actions/runs/39514486747) 在依赖安全审计处失败；后端、Windows隔离和Java/SSH检查成功。未绕过审计或合并失败分支。

官方公告把 Vite 7.0.0–7.3.6 列为受影响版本，7.3.7 列为7.x修复版本。关联的 launch-editor 问题涉及Windows路径检查/转义及部分编辑器参数解析；这是依赖风险，不是本项目已被利用的证据。

来源：[高危路径检查公告](https://github.com/advisories/GHSA-587h-7wmj-h5pm)、[转义公告](https://github.com/advisories/GHSA-698v-4r3x-3vcv)、[编辑器参数公告](https://github.com/advisories/GHSA-r825-w8mm-5pxw)、[Vite 7.3.7 发布](https://github.com/vitejs/vite/releases/tag/v7.3.7)。

前端开发依赖由精确版本7.3.6升级到7.3.7，锁文件随之更新，npm报告仅变更1个包。使用官方npm registry并锁定其integrity，没有运行 audit fix --force，没有修改安全检查阈值。

## 验证

官方registry审计：根目录与前端均报告0 vulnerabilities；2249项前端测试通过，类型检查、Vite构建、发布包和安全策略检查全部通过。未修改后端代码，本次不冒充重新执行后端或真实环境验收。此补丁不更改数据库、业务配置、权限或128项正式验收状态。
