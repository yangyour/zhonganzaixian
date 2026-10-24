# 2026-10-24 远程测试环境发布

用户明确要求“部署远程”，并延续“先忽略HTTPS问题”。本次已将已验证主干 `b964701b5a148978cf761bf550dd7f24745fdb43` 发布到指定LAN测试环境，7个项目应用统一版本。应用切换完成时间为 **2026-10-24 02:40:47 EDT**，实际浏览器复核完成于02:45:07 EDT。HTTPS未变更，不将测试发布等同正式生产批准。

## 当前结果

| 项目 | 实际结果 |
| --- | --- |
| 业务入口 | http://mop.all-online.com.cn/login |
| 内网入口 | http://192.168.1.215:10010/login |
| 发布源码 | `b964701b5a148978cf761bf550dd7f24745fdb43`；API契约0.85.0 |
| 应用 | API、Web、照片Worker、资产导入Worker、联系人Worker、导出准备Worker、AF采集Worker共7项 |
| 服务状态 | 14个服务运行；`remote_test / ready=true / durable=true` |
| 数据库 | PostgreSQL17.9；独立CLI将活动`mop_test`从V50升级至V55 |
| 迁移执行 | V51–V55各apply=1、repeat=0、check=0；应用启动0迁移 |
| 工件 | 171项文件/工件摘要核对；Web build_id `f9351338e1695550fe0b7df67ec5e90f638a99be1ae214576eae195674f51cc6` |
| 发布目录 | `/opt/mop-lan-test/releases/20261024T063849Z-b964701b5a14-e29edd` |
| 正式生产 | `production_ready=false`，完整合同验收未完成 |

项目为众安在线MOP，规范origin为`https://github.com/yangyour/zhonganzaixian.git`，组织路径`yangyour/zhonganzaixian`。发布工作区为`E:\work\wxw9\zhonganzaixian\.worktrees\remaining-gaps-20261024`，分支`codex/remaining-gaps-20261024`及同名origin上游，源码与发布时main一致。原`mop-platform`目录的3处未提交前端改动未带入工件。此后仅归档文档的Git提交不改变本次实际运行源码。

## 迁移、数据和恢复

发布前实际为V50，旧API/Web为c3e1c35b，其他5应用为2291ddf8。最新工件包含V51–V55，因此先按独立迁移流程核对已冻结SQL及指定隔离库证据，没有降低发布脚本的完整版本检查。

取得本地migration/environment锁及远程更新锁后，上传同一已验证API JAR，以只读CLI确认旧结构/ACL/历史。短暂停止6个项目写入应用，Web和7个基础组件保持运行；为项目数据库生成私有custom格式备份，pg_restore目录读取、远端/本地SHA256核对通过。备份1969503字节，SHA256为`4dbc443d06ad38903857bcd9c12546665f4ba6125900323c3378f071546bde96`，原始文件只在忽略目录及服务器私有目录保留，未执行恢复演练。

随后使用现有固定Java镜像、受限migrator身份和PropertiesLauncher，逐项显式执行notification-control、attachment-security、report-publication、monitoring-enrollment、monitoring-object-mapping五个模式。每步立即验证重复不迁移、只读结构/ACL与连续历史。旧50条历史保持一致；原111张表中只有Flyway历史按预期增加，其他110张旧表的数据指纹不变；新增11张业务表均为空。没有业务数据填充或旧记录重写。V1–V55继续全部字节冻结，后续SQL从已协调的新版本追加。

临时迁移凭据文件已删除，原应用在V55上恢复后真实就绪，再运行现有`update-lan-test-server.py`的工件、候选及应用切换流程。候选关闭邮件/企微派发；正式配置延续现有值，未额外启用通知、采集、备份、扫描或生产开关。PostgreSQL、Redis、RabbitMQ、MinIO与三个Zabbix容器的ID、镜像、启动时间和重启次数在整个窗口保持不变。全部协调锁及候选容器已释放。

当前应用回退事务为：

```text
/opt/mop-lan-test/private/update-20261024T063849Z-b964701b5a14-e29edd/transaction.json
```

需要回退时使用既有工具的restore模式及上述精确事务。目标为已经在V55恢复并验证就绪的旧应用组合；保留V55，不自动降级数据库，不使用更早V50事务冒充当前回退依据。本次准备并验证了恢复守卫，没有实际执行应用回退或数据库恢复。

## 验证

- 30项部署保护测试、215项发布流程契约检查通过；Java package发现5310项、实际执行4179通过、1131按环境条件跳过，0失败/错误。Web339文件5522项通过，类型、构建、发布清单和静态策略通过。之前功能阶段的271项Node检查与本次发布入口的215项范围分别记录，不混成同一计数。
- 公网和内网共462项实际HTTP/摘要检查通过。包括所有Web清单文件、认证/匿名拒绝、跨租户资产404、实际账号4台设备、报告中心、当前SLA来源版本、待办/铃铛、事件指标、巡检/备份草稿、监控及生产诊断读取。实际账号报告发布列表为空，这是实际数据状态；没有补造报告、恢复或监控事实。
- 真实Edge156.0.4314.42连接已发布API，padmin/msp/tadmin报告中心及内部角色SLA页面共7个桌面页面/视口检查通过，涵盖1440/1024。所有业务请求为真实GET，没有模拟读取响应；登录与切换DEMO会话单列，不计业务对象写入。没有新增页面异常或页面级横向溢出，宽表沿用独立滚动区域。此结果是实际页面读取验证，不是空报告列表下的新修订正向写入、全部浏览器兼容或全站无障碍验收。
- 发布过程中没有测试邮件/企微发送、设备操作或业务初始化。登录/切换会话保留正常安全记录，不宣称数据库完全没有任何写入。

机器证据：[发布](evidence/remote-release-20261024/deployment.json)、[迁移](evidence/remote-release-20261024/migration.json)、[构建/汇总](evidence/remote-release-20261024/validation.json)、[HTTP](evidence/remote-release-20261024/http.json)、[浏览器](evidence/remote-release-20261024/browser.json)、[发布后组件](evidence/remote-release-20261024/components-after.json)。原始备份和私有配置不入Git。

报告修订及监控时间来源已随代码部署；客户审批策略、正式模板、实际设备恢复、供应商派发、统一可用率/对账等门禁仍保留。真实入口可读不替代完整业务写入链或128项正式合同验收。HTTPS继续按用户要求后置。
