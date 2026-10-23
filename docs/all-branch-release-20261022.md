# 全分支覆盖核验与测试环境统一发布（2026-10-22 晚间）

用户要求“拉取最新代码部署到测试环境，注意要全部分支的修改代码合并到main”。已于 **2026-10-22 20:30:17 EDT（America/New_York）** 将 main 的 `86713696932e476cbc6eb00c1e007895ff20a11a` 统一发布到指定测试环境的 7 个应用；随后真实 HTTP、浏览器和组件复核通过。

## 仓库及全部分支核验

- 项目：MOP / 众安在线；本地绝对路径 `E:\work\wxw9\zhonganzaixian\mop-platform`。
- 规范 origin：`https://github.com/yangyour/zhonganzaixian.git`；完整组织路径 `yangyour/zhonganzaixian`；当前分支及上游 `main` / `origin/main`。
- 执行 `git fetch --all --prune`，将原 main `7a56739` 正常快进到 `4f64031`。本次新增的 `8671369` 仅记录分支覆盖核验，应用代码与 `4f64031` 相同。
- 逐项检查 **29 个远端非 main 分支、8 个本地非 main 分支**，全部分支 tip 都是 main 的祖先，分支独有未合入提交数均为 0。额外 `git ls-remote --heads origin` 核对实际远端的 30 个分支，不仅使用旧的远端跟踪引用。
- 5 个关联工作目录（含一个 detached HEAD 验证目录）均干净，没有遗留未提交改动。历史 handoff 本地分支相对其旧远端多出的文档提交也已在 main 内。
- 已存在的合并历史完整包含这些功能分支，因此本次无须制造重复合并提交，没有强推、重写共享历史或删除分支。构建前、部署前及部署后再次核对 origin/main；最终分支覆盖核验时 main 与 origin/main 均为 `8671369`。

[初始覆盖证据](evidence/all-branch-release-20261022/branch-coverage.json)及[发布后覆盖证据](evidence/all-branch-release-20261022/branch-coverage-final.json)记录每个引用、完整 SHA 和差异计数。本发布记录及证据的后续归档提交只更新文档，不改变已验证的应用工件。

## 统一发布结果

| 项目 | 实际结果 |
| --- | --- |
| 业务入口 | http://mop.all-online.com.cn/login |
| 内网入口 | http://192.168.1.215:10010/login |
| 环境 / 项目 | lan-test-20261008 / mop-lan-test |
| 统一源码版本 | `86713696932e476cbc6eb00c1e007895ff20a11a` |
| 应用 | API、Web、照片 Worker、来源导入 Worker、联系人 Worker、导出准备 Worker、AF 采集器，共 7 项 |
| 发布目录 | `/opt/mop-lan-test/releases/20261023T002816Z-86713696932e-ae576b` |
| 工件核对 | 166 个文件 SHA256 一致，含 6 个 JAR、147 个 Web 文件、Nginx 配置及 12 个公开监控工具/示例 |
| Web build_id | `2ac49e25b90538a3c04162c402f50b4a4d160e5de9b76b53251a67e7b02b87a2` |
| API 契约 | 0.81.0；生成结果与当前源码一致 |
| 服务与依赖 | 14 项运行，ready=true、durable=true；7 应用标签一致且无异常重启 |
| 数据库 | PostgreSQL 17.9 / V50，Flyway 历史保持，0 迁移 |
| 原基础组件 | PostgreSQL、MinIO、Redis、RabbitMQ、Zabbix 三容器，共 7 项，ID/镜像/启动时间/重启次数保持 |
| 原运行配置 | 原 Compose、私有 env、端口、凭据及活动配置延续，未修改共享组件配置 |

前次仅 API/Web 为 `9d3fed4`、其余应用为 `603337b` 的状态已由本次统一发布替代。此前尚未部署的监控登记目录、资产校验准备接口、Prometheus 抓取观察读取代码也已进入本次 API 工件。Web 源码没有新增改动，重新构建得到的 build_id 与前次相同，未改变用户确认的视觉样式。

发布沿用 `tools/update-lan-test-server.py` 的归属、固定主机信任、数据库版本与工件检查、候选 API/Nginx、私有配置并发守卫、仅应用 `up --no-deps --pull never`、失败回退及验后守卫。使用本机忽略目录中的接续入口，将完整 Web 套件并发固定为 4，并把当前公开监控脚本/禁用示例加入同一不可变归档；没有跳过或删除原部署门禁。构建工件逐项复核后才进入切换。

## 本轮验证

| 检查 | 实际结果 |
| --- | --- |
| 部署保护 | 20 项通过 |
| 根目录契约 | 187 项通过 |
| 演示请求结构契约 | 4 项通过；仅离线结构检查 |
| 前端 | 296 个文件、4913 项通过，4 个工作进程 |
| Java 全模块 package | 成功；4310 项报告，实际执行 3267、条件跳过 1043，失败/错误均 0 |
| 类型、生产构建、清单及安全策略 | 全部通过；保留原包体积/混合引用提示 |
| 监控 Python 工具 | 指定服务器独立临时目录 85 项报告，实际执行 84、跳过 1，失败/错误均 0 |
| 正式入口 HTTP / 摘要 | 域名和内网入口合计 370 项通过，含全部 146 个清单内 Web 文件逐项摘要核对 |
| 正式域名浏览器 | JiangFaMing 登录 HTTP 200；资产/事件页在 1440px、390px 的 4 项读取检查通过，0 脚本异常、无整页横向溢出 |
| 服务器已发布工具 | catalog、禁用目标 validate、inventory 编译和 file discovery render，4 项离线命令通过；未写输出目录、未应用配置或启动采集器 |

HTTP 复核包含真实登录/会话、4 台实际资产、工单和事件空态、导出/站内消息/网络监控/账号读取、安全头、缓存、匿名拒绝、缺失脚本 404，以及新监控接口的未配置响应和身份覆盖参数拒绝。浏览器只读取页面，对非 GET/HEAD/OPTIONS 的业务请求设阻断计数，最终业务写请求为 0；截图另在本机忽略目录人工查看，不进入 Git。

Java 条件跳过项及未配置官方 Prometheus 二进制而跳过的 Python 实际引擎场景，均不计作指定组件联调通过。Python 的 84 项仅为输入/协议/本地 HTTP 测试，未访问业务数据库或真实设备，也没有在服务器新增数据库、缓存、消息或监控引擎。

前两个本机构建尝试未完成：第一次默认并发在 Web 阶段停止，第二次在工具运行环境重启时中断；最终全套四进程构建独立成功，未复用不完整结果。补充 Python 测试在 Windows 首次因两项符号链接权限、两项缺 POSIX sh 失败；服务器首次缺测试硬编码的 `python` 命令，第二个全新临时目录只为测试创建局部 bin/python 指向既有 Python3 后通过，未改全局 PATH 或安装软件。原日志及失败证据保留在忽略目录，不将其改写成成功。

## 数据、功能开关与保留门禁

JiangFaMing 当前租户继续只保留 4 台用户指定设备，工单/事件列表仍为空；本次没有恢复旧测试身份、创建演示账号、写入 SYNTHETIC 数据、发送邮件/企业微信或调用设备探测。main 中新增的演示计划工具仍只提供离线准备，不代表演示账号和业务数据已经开通。

当前服务器未启用登记目标目录及 Prometheus 读取来源，相关读取接口真实返回 503 `CONFIG_PENDING`；覆盖参数返回 400 `VALIDATION_ERROR`。新代码可用与来源已经接通分别记录，不使用成功空列表或“健康”状态替代未配置。公开禁用示例、清单编译和文件发现工具随版本交付，不授权采集、配置应用或设备执行。

备份版本独立授权查询装配、客户未确认规则、生产批准及 128 项正式合同验收保持原状态，production_ready=false。本次发布与只读冒烟不构成全部业务功能验收。

## 回退和证据

本次回退事务为 `/opt/mop-lan-test/private/update-20261023T002816Z-86713696932e-ae576b/transaction.json`。按[统一恢复流程](test-environment-update.md)可恢复此前健康的 V50 应用组合，不降级数据库，不恢复已清理的合成数据；本轮未触发回退或另做回退演练。

证据：[部署](evidence/all-branch-release-20261022/deployment.json)、[验证总表](evidence/all-branch-release-20261022/validation.json)、[HTTP](evidence/all-branch-release-20261022/http.json)、[浏览器](evidence/all-branch-release-20261022/live-browser.json)、[组件](evidence/all-branch-release-20261022/components.json)、[7 应用和离线工具](evidence/all-branch-release-20261022/server-assets.json)、[Java 统计](evidence/all-branch-release-20261022/java-summary.json)、[监控工具测试](evidence/all-branch-release-20261022/monitoring-tests.json)。

原始构建日志及真实页面截图只存本机 `.local/test-deployment/20261023T002447Z-redeploy-86713696932e/`，补充脚本及中间证据只存 `.local/all-branch-release-20261022/`。连接秘密与完整环境继续仅存 Git 忽略的本机配置和服务器私有事务。
