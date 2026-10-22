# 桌面操作流程发布记录（2026-10-22）

用户明确要求“推送并部署”。已于 **2026-10-22 10:21:28 EDT（America/New_York）** 将本次桌面交互优化所在的 `9d3fed4db4038132293ef4e95c5b9ca71ba33cf4` 发布至指定测试站 API/Web。入口：[MOP 运维托管](http://mop.all-online.com.cn/)。10:23:11 EDT 完成正式业务域名的交互复验。

## 版本与范围

- 本地目录：`E:\work\wxw9\zhonganzaixian\mop-platform`；origin：`https://github.com/yangyour/zhonganzaixian.git`；完整组织路径：`yangyour/zhonganzaixian`；分支及上游：`main` / `origin/main`。
- 发布前工作区干净，本地与远端均为 `9d3fed4`，再次推送返回已同步。使用既有 `py -3 tools/update-lan-test-server.py update --branch main --only-api-web` 完成构建、候选检查、切换及回退事务保存。
- 本次交付包含列表—详情—返回、当前结果内上一条/下一条、筛选应用/撤销、资产列表到导出准备及返回原筛选列表。详见[实施记录](web-interaction-flow-20261022.md)。菜单组件、`App.vue` 和全局样式保持已确认基线；没有开展手机整改。
- 只切换 API/Web。其余 5 个应用与 7 个基础组件保持原容器生命周期、镜像及配置。它们的应用源码标签仍为 `603337b`，不能标作本次版本。
- 发布过程固定源码后，远端另新增监控后端提交 `d518091`。该提交已正常快进保留在主干，但不在本次已验证/已发布的 `9d3fed4` 工件中；发布证据不冒充对后到提交的部署证明。

## 发布门禁与真实复验

| 检查 | 结果 |
| --- | --- |
| 部署保护 | 20 项通过 |
| 契约 | 177 项通过 |
| 前端 | 296 个文件、4913 项测试通过 |
| Maven package | 4243 项报告，实际执行 3201、条件跳过 1042，失败/错误 0 |
| 类型/构建/清单/安全策略 | 通过；原构建体积及混合引用提示保留 |
| 上传与工件摘要 | 154 个文件核对一致，包含 146 个 Web 静态工件及其他发布文件 |
| 候选 | 真实指定组件连接、Nginx 配置检查、回环候选 API 就绪通过；候选随后移除 |
| 正式 HTTP/摘要 | 域名/IP 双入口合计 354 项检查通过；静态文件、缓存/安全头、实际登录与业务 GET 通过 |
| 正式桌面浏览器 | Edge 155.0.4283.62，10 项交互检查通过；0 页面脚本异常、0 非登录业务写请求 |
| 依赖与数据库 | 14 个项目服务运行，ready=true、durable=true；PostgreSQL 17.9 / V50，0 迁移 |

正式桌面浏览器检查覆盖：筛选后详情位置说明、返回原查询、连续查看后的浏览器后退、原列表焦点、短桌面窗口滚动恢复、撤销筛选不请求、无匹配查询恢复、未应用筛选阻止导出、导出正确预填且未提交、返回保留条件及每页条数。视口为 1440×900 与 1440×560，不冒称手机验收。

真实账号当前可见资产仍为 4 台用户指定设备，报障和事件列表为空，没有重新生成合成业务。验证只进行了实际登录和读取，不创建导出任务、不发送邮件/企业微信、不执行设备探测。实际报障正向详情缺少记录，保留此前隔离浏览器/DOM证据边界，不为发布造工单。

1042 项条件跳过的后端测试不计作指定组件联调通过。`production_ready=false`，客户生产批准、组件业务缺口及 128 项正式合同 TC 仍按原记录，不因发布而变更。

## 工件、证据和回退

- API/Web 源码：`9d3fed4db4038132293ef4e95c5b9ca71ba33cf4`。
- Web build_id：`2ac49e25b90538a3c04162c402f50b4a4d160e5de9b76b53251a67e7b02b87a2`。
- 不可变发布目录：`/opt/mop-lan-test/releases/20261022T141943Z-9d3fed4db403-0bfa1f`。
- 回退事务：`/opt/mop-lan-test/private/update-20261022T141943Z-9d3fed4db403-0bfa1f/transaction.json`。按[统一恢复流程](test-environment-update.md)恢复 API/Web 发布前的 V50 应用，不回退数据库、不恢复已清理的合成业务。
- [部署](evidence/web-flow-release-20261022/deployment.json)、[HTTP/摘要](evidence/web-flow-release-20261022/http.json)、[浏览器](evidence/web-flow-release-20261022/live-browser.json)、[组件](evidence/web-flow-release-20261022/components.json)、[Java 测试统计](evidence/web-flow-release-20261022/java-summary.json)。
- 原始构建日志：本机忽略目录 `.local/test-deployment/20261022T141659Z-d50e79/`；真实截图与补充验证：`.local/web-flow-release-20261022/`。连接秘密不进入 Git、截图或公开记录。

已有私有配置、迁移历史和 12 个非选中容器元数据与发布前一致。没有新增基础组件或修改其端口、权限及全局配置。
