# 指定测试环境验证记录

首次记录：2026-09-30；更新：2026-10-01。当前运行模式为 `remote_test`：Windows本地应用通过仅绑定127.0.0.1的SSH隧道，连接用户指定服务器的PostgreSQL 17.9与Keycloak 26.2.4。数据库为独立 `mop_test`，身份域为本项目 `mop-test`；数据与账号均明确标识为虚构测试数据。本轮没有在本机另起数据库、缓存、消息或监控替代服务。

本机执行环境为Windows 11、Temurin JDK 17.0.20、Maven 3.9.6、Node 22.22.1、npm 10.9.4、Python 3.12。服务器其他已发现组件及缺失项见 [组件清单](environment/component-inventory.md)。测试环境接入不表示客户生产配置、许可或合同验收已获批准。

## 已执行结果

2026-10-01继续本地开发，新增配置修订与内部审批功能已验证：Java纯单元18项、指定PG集成11项、前端15项、契约3项均通过。新增三主体真实OIDC下40个HTTP响应及1440/390px页面流程通过，详情见[配置修订验证](configuration-revisions.md)和[evidence/configuration-revision-http.json](evidence/configuration-revision-http.json)。客户正式确认、配置生效和外部下发仍未实现，不能因内部批准记为合同全量完成。

### 客户报障登记：已执行验证

用户已明确一期由本平台作为工单权威；本轮只实现真实PostgreSQL报障登记与查询，operation为PENDING/CONFIG_PENDING，正式状态、优先级与SLA字段为null。照片上传及其余D07闭环未完成。实现范围和接口边界见[客户报障持久登记](customer-report-registration.md)。

|检查|当前结果|证据边界|
|---|---|---|
|Java纯单元与指定PostgreSQL集成|24项纯单元、19项真实PG集成通过，共43项|全量检查22纯+19PG；会话修正后补跑2项纯测试并重新打包；历史fixture 13项跳过，不合并计数|
|会话岗位投影补充|2项纯测试、8次真实HTTP检查通过|current membership岗位与capabilities同源；原Cookie降级后报障202/回读200、配置与资产写403，finally恢复成员且权限版本递增|
|前端单元、类型与构建|21项通过；类型检查和构建通过|输入/Unicode、安全result_ref、稳定幂等键与当前客户端回归；主包462.32kB，无包体警告|
|契约检查|3项通过；实际契约V0.4|登记与正式业务状态分离，未知值不默认生效|
|真实OIDC/HTTP报障流程|4个角色真实登录；47次HTTP及V0.4 Ajv响应校验通过|登记/回读、同键重放/冲突、已知设备语义去重、不覆盖原描述、同租户他人/跨租户404、输入/CSRF/附件门禁、SQL游标授权绑定|
|1440px/390px真实浏览器流程|16类控件/场景通过；0 pageerror，无水平溢出|登记、事实回读、筛选恢复/分页、内部只读、切租户、已受理GET失败只回读、POST已提交但响应丢失后原body/同键重试、手机表单与详情操作可达|

公开脱敏证据见[本轮汇总](evidence/customer-report-verification-summary.json)、[HTTP契约](evidence/customer-report-http-verification.json)、[真实成员角色](evidence/membership-role-http-verification.json)、[页面验收](evidence/customer-report-ui-verification.json)。截图保留于本机Git忽略的`.local/qa-customer-reports/`。不能据此将完整D07或128项需求标为完成；未接入RabbitMQ消费者、通知、图片服务，不把CONFIG_PENDING outbox记录当作已经投递或处理。

### 首个测试切片

下表保留首个测试切片的原始计数，与后续配置修订/报障批次分别记录。

|检查|结果|证据与实际范围|
|---|---|---|
|Java编译、打包及纯单元|通过；13项纯单元，0失败/错误|现有Surefire：Profile 5、OIDC映射4、Session安全2、Postgres安全2；不把隔离替身当作组件联调|
|指定PostgreSQL集成|通过；5项，0失败/错误|独立仓储实例回读/重放、双连接并发幂等、异常整事务回滚、配置CAS与null不激活、真实运行权限及审计hash重算|
|历史fixture工作流|13项默认跳过|仅保留历史/隔离测试用途，不计入本轮共享测试环境通过数|
|前端客户端单元、类型与构建|10项通过；类型检查与构建通过|CSRF/幂等头、401处理、迟到响应隔离与未知值语义；不替代真实身份和数据库联调|
|API契约与原登记一致性|3项通过|原登记副本、pending null与写入口门禁；首个切片契约为V0.2、配置修订批次V0.3，报障批次实际契约V0.4|
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
- Redis、RabbitMQ与MinIO仅已盘点，本批没有对应缓存、消息或S3业务联调；Zabbix、外部ITSM、堡垒机及相关资料缺失保持登记状态，真实监控未知值不冒充正常。一期本平台工单权威已由用户确定，缺失外部ITSM不阻塞本平台持久报障登记；正式规则仍待落实。
- 报障新增耐久outbox意图，但消息投递、Worker消费者/租约、审计外部受保护链头及至少3年保留治理尚未实现。正常Worker运行明确拒绝，CONFIG_PENDING记录不被标为已执行或送达。
- 第二机器/云端复现、客户H5、全浏览器、真实设备采样、容量/性能/灾备及128项合同逐项验收尚未完成。

首批local_fixture记录已归档至 [历史验证](history/2026-09-30-local-fixture-verification.md)、[历史QA计划](history/2026-09-30-local-fixture-qa-inventory.md)及 [历史摘要](history/2026-09-30-local-fixture-verification-summary.json)。其16项后端/33个HTTP等旧计数仅描述当时fixture阶段，不能合并为本轮真实组件结果。
