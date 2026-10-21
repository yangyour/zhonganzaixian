# 2026-10-19 测试环境更新记录

已通过正式更新脚本将当前开发分支发布到指定 LAN 测试环境。发布时间为2026-10-19 03:28 EDT（07:28 UTC），部署源码为 `34a183e3571adfd9eb7861ec382918f45a802e10`，分支为 `feat/d07-ticket-lifecycle`，API契约0.58.0。该分支包含最新企业微信开发；未将其他未合入分支或较旧的main替代当前开发分支。

日常执行命令、前置条件和异常恢复统一见[操作手册](test-environment-update.md)。本记录和验收证据提交属于部署后的文档归档，不改变已发布应用源码或工件。

## 实际发布

| 项目 | 实际结果 |
| --- | --- |
| 项目 / 环境 | mop-lan-test / lan-test-20261008 |
| 内网入口 | `http://192.168.1.215:10010/login` |
| 企业微信业务入口 | `http://mop.all-online.com.cn/login` |
| 更新应用 | API、Web、照片Worker、来源导入Worker、联系人Worker、导出准备Worker、AF采集器，共7项 |
| 发布目录 | `/opt/mop-lan-test/releases/20261019T072625Z-34a183e3571a-0390ed` |
| SHA256逐文件核对 | 131个工件，包含6个JAR、完整Web及Nginx配置 |
| Web build_id | `4903314ce8ce4a6ba737d13dfe4701b8e6af1852fbed3add7ddef9db6aa209b5` |
| 运行状态 | 14服务运行，ready=true、durable=true、remote_test |
| 数据库 | PostgreSQL17.9，V38历史前后相同，本流程执行0迁移 |
| 基础组件 | PostgreSQL、MinIO、Redis、RabbitMQ及Zabbix三个服务，共7容器ID/镜像/启动时间/重启次数不变 |
| 配置 | 原Compose、.env及各应用私有env不变；活动覆盖及回退环境仅保存在服务器private目录 |
| 生产状态 | production_ready=false，正式合同验收未完成 |

复核实际组件版本：Docker29.1.3、Java17.0.20.1、Nginx1.31.6、MinIO RELEASE.2025-09-07、Redis7.4.9、RabbitMQ4.3.2、Zabbix7.0.31。完整摘要与容器状态见[发布证据](evidence/test-environment-update-20261019.json)及[组件盘点](evidence/test-environment-update-components-20261019.json)。临时API已移除，不依赖本机开发进程或临时隧道维持测试站。

## 企业微信运行配置与同源校验

用户明确选择同时启用企业微信登录及人工通知，自动业务通知保持关闭。服务器从只读私有文件取得应用凭证，设置 `MOP_WECOM_ENABLED=true`、`MOP_WECOM_LOGIN_ENABLED=true`、`MOP_WECOM_DISPATCH_ENABLED=true`；此分发开关仅投递用户明确操作创建的耐久消息，`automatic_notifications_enabled=false`。本次没有创建消息、发送测试消息或新增/修改绑定。

既有Nginx固定将上游Host/端口设为IP:10010，这会令业务域名的同源及回调校验失败。新不可变Nginx保留完整安全头、静态缓存和路由，仅增加指定业务域名的固定白名单映射；IP仍使用10010，业务域名使用HTTP80。API使用框架转发头处理，不接受客户端任意选择身份域名。实际Nginx语法检查、域名账号登录、固定企微授权地址生成、浏览器关联Cookie均通过；从IP发起企微登录仍返回明确的 `WECOM_CALLBACK_ORIGIN_MISMATCH`，指导使用业务域名。

扫码后的真实回调仍按用户此前决定保留后续验证。本次没有扫描二维码，HTTP入口可用不代表HTTPS证书问题已解除；原已记录的证书过期问题未改动，也未关闭证书校验。完整企业微信开发和验收边界见[开发记录](wecom-development-20261019.md)。

## 测试与真实检查

| 检查 | 本次最终结果 |
| --- | --- |
| 部署保护 | 17项隔离用例通过，含部分切换失败、自动回退及回退失败不能假成功 |
| 契约 | 138项通过 |
| Web | 160文件、2224项通过；类型检查、构建、清单与发布策略通过 |
| Java | Maven全模块package通过；3102项报告，2189实际执行、913按条件跳过，失败/错误0 |
| 候选环境 | 同一服务器临时回环API真实依赖就绪；分发强制关闭；验证后移除 |
| 已发布HTTP | IP与公网业务域名共53项通过，消息发送0次 |
| 发布后盘点 | 14服务运行，就绪通过；V38、组件版本和非应用容器生命周期核对通过 |

913项跳过的组件/历史测试没有当成执行成功。隔离或历史fixture测试也没有被计为真实组件联调。真实检查独立记录：账号登录、企业微信功能开关与成员绑定、已有1条发送记录保留、安全头、静态清单/首页摘要、域名校验文件、缺失JS为404、CSRF拒绝、固定域名回调保护和关联Cookie。未额外发送消息，未将已有ACCEPTED伪造成新的DELIVERED。见[53项HTTP证据](evidence/test-environment-update-http-20261019.json)及[验证汇总](evidence/test-environment-update-verification-20261019.json)。

## 首次拦截与修复

首次运行使用 `44e2c58`，在切换前被全表待发消息检查拒绝。只读核对发现：当前真实企业/应用只有1条ACCEPTED记录；另一个合成测试应用有QUEUED记录。原检查范围过宽，与实际Worker仅领取本租户、本企业、本应用的逻辑不一致。

新增精确范围校验及回归测试后提交为 `34a183e`，重新执行全部门禁并成功发布。首次前后14服务状态完全一致；没有删除、取消、投递或篡改其他合成应用记录。另补齐Compose对凭据中字面量美元符号的转义，防止日后将合法凭据误作变量展开。本次既有应用环境未含美元符号，该保护不改变当前秘密。

## 回退依据

本次私有事务路径为：

```text
/opt/mop-lan-test/private/update-20261019T072625Z-34a183e3571a-0390ed/transaction.json
```

可按[手册](test-environment-update.md)使用 `restore --transaction <上述路径>`，恢复本次发布前各应用的实际镜像、环境和挂载，数据库不降级。原版本和私有快照均保留；自动回退分支已通过隔离故障测试，本次成功发布没有触发真实回退，也没有额外执行实际回退演练。后续手工管理应用应读取 `/opt/mop-lan-test/active-update.json` 并带上活动覆盖文件，或直接使用本更新脚本；既有容器的自动重启策略会保留当前挂载与环境。

所有证据均经过本机私有JSON秘密值比对，未记录密码、token、含密连接串或完整运行环境。连接原文件继续Git忽略。完整D11、可信TLS、实际扫码、客户生产批准和全部128项合同需求仍按原独立记录追踪。
