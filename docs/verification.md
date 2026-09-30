# 指定测试环境验证记录

日期：2026-09-30。当前运行模式为 `remote_test`：Windows本地应用通过仅绑定127.0.0.1的SSH隧道，连接用户指定服务器的PostgreSQL 17.9与Keycloak 26.2.4。数据库为独立 `mop_test`，身份域为本项目 `mop-test`；数据与账号均明确标识为虚构测试数据。本轮没有在本机另起数据库、缓存、消息或监控替代服务。

本机执行环境为Windows 11、Temurin JDK 17.0.20、Maven 3.9.6、Node 22.22.1、npm 10.9.4、Python 3.12。服务器其他已发现组件及缺失项见 [组件清单](environment/component-inventory.md)。测试环境接入不表示客户生产配置、许可或合同验收已获批准。

## 已执行结果

|检查|结果|证据与实际范围|
|---|---|---|
|Java编译、打包及纯单元|通过；13项纯单元，0失败/错误|现有Surefire：Profile 5、OIDC映射4、Session安全2、Postgres安全2；不把隔离替身当作组件联调|
|指定PostgreSQL集成|通过；5项，0失败/错误|独立仓储实例回读/重放、双连接并发幂等、异常整事务回滚、配置CAS与null不激活、真实运行权限及审计hash重算|
|历史fixture工作流|13项默认跳过|仅保留历史/隔离测试用途，不计入本轮共享测试环境通过数|
|前端客户端单元、类型与构建|10项通过；类型检查与构建通过|CSRF/幂等头、401处理、迟到响应隔离与未知值语义；不替代真实身份和数据库联调|
|API契约与原登记一致性|3项通过|原登记副本、pending null与写入口门禁；当前实际契约为remote_test V0.2|
|真实OIDC登录|通过|密码仅在Keycloak SSO页输入；标准签名/签发者验证成功，观察PKCE S256与state/nonce；身份以verified issuer+sub关联项目成员|
|真实RP注销与重新登录|通过|标准Keycloak注销后SSO清除，可改以viewer重新登录；临时授权客户端信息仅保留服务端会话|
|伪造注销与回调边界|通过|没有pending上下文的GET `/auth/logout`、invalid-state callback均未注销既有会话，随后 `/session` 仍200|
|真实API HTTP/OpenAPI校验|28个响应通过|真实OIDC Cookie下执行CSRF、fixture入口拒绝、资产与游标、持久幂等及并发、租户隔离、配置草稿/发布门禁和耐久审计|
|API重启后的持久回读|5个HTTP检查通过，进程身份断言通过|已确认API的PID/startTime变化及默认remote_test启动成功；重新真实OIDC登录后，原资产及created_at、原幂等key结果、配置草稿及版本仍保留，执行退出码0|
|真实受限运行账号|通过|audit INSERT=true、UPDATE/DELETE=false；配置registry_json不可UPDATE；Flyway历史不可SELECT；迁移与运行角色分离|
|真实成员撤权及降级|通过|旧Cookie读取与已提交写重放在撤权后401；仍active但降为viewer后写重放与配置读取403；测试后恢复原成员，权限版本单调增加|
|组件断链与恢复|通过|只停止本机SSH隧道，ready与写入口503；恢复后仍remote_test且durable=true，没有fixture回退，未停止远程服务|
|真实管理端与移动页|通过本批范围|PC 1440×900无pageerror；真实tadmin在390×844登录，无水平overflow；viewer无创建/配置/租户切换菜单，写与配置HTTP均403|

后端执行摘要读取 `server/api/target/surefire-reports/` 与 `server/persistence/target/surefire-reports/`。机器可读总表见 [verification-summary.json](evidence/verification-summary.json)，真实权限/撤权结果见 [remote-security-verification.json](evidence/remote-security-verification.json)。它们只记录环境、计数与行为结果，不包含密码、Cookie、CSRF token、OIDC token或完整协议跳转URL。

移动截图已生成于本机 `.local/qa-remote/assets-mobile.png`。`.local/`、编译产物、私有运行配置和会话文件均未作为Git交付材料；截图不等同于服务端授权或组件联调证据。

## 复现入口

先按 [运行说明](../README.md)准备指定环境私有配置，并停止正在占用jar的本工程API，再运行 `pwsh -File deploy/check.ps1 -Install`。该脚本启用真实PostgreSQL集成，连接失败即失败，不以fixture替代。

启动应用后执行 `npm run test:api`：捕获工具在已安装浏览器中完成真实Keycloak登录，将临时Cookie/CSRF只写入被Git忽略的私有会话文件，随后执行HTTP契约检查。重启验证的入口及执行顺序见README。云端或第二机器复现仍须通过受控私有配置连接同一指定测试环境；缺少连接资料不能据纯单元通过宣称已联调。

## 局限与历史记录

- 已验证的是上述测试切片。标准OIDC成功流程不等于已经实测全部伪造签名、密钥轮换、会话超时与身份灾备分支。
- PostgreSQL测试库和API的tenant+id约束不替代客户生产RLS/独立库及六层隔离验收；生产Q02/Q03/Q17与客户正式岗位仍待确认，production/integration不作为可用运行模式。
- Redis、RabbitMQ与MinIO仅已盘点，本批没有对应缓存、消息或S3业务联调；Zabbix、ITSM、堡垒机及相关资料缺失保持登记状态，真实监控未知值不冒充正常。
- Worker耐久任务消费者、outbox/租约、审计外部受保护链头及至少3年保留治理尚未实现。正常Worker运行明确拒绝，不创建假任务或发布假成功。
- 第二机器/云端复现、客户H5、全浏览器、真实设备采样、容量/性能/灾备及128项合同逐项验收尚未完成。

首批local_fixture记录已归档至 [历史验证](history/2026-09-30-local-fixture-verification.md)、[历史QA计划](history/2026-09-30-local-fixture-qa-inventory.md)及 [历史摘要](history/2026-09-30-local-fixture-verification-summary.json)。其16项后端/33个HTTP等旧计数仅描述当时fixture阶段，不能合并为本轮真实组件结果。
