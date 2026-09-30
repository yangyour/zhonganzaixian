# 第一批本地验证记录

日期：2026-09-30。环境：Windows 11、Temurin JDK 17.0.20、Maven 3.9.6、Node 22.22.1、npm 10.9.4。全部验证针对本机 `local_fixture`，未连接客户数据库、真实设备或外部服务。

|检查|执行结果|证据与覆盖|
|---|---|---|
|Maven编译、测试与打包|通过；16项，0失败/错误/跳过|Workflow 13项+Profile 3项；`server/api/target/surefire-reports/`；`server/build-package.log`|
|前端客户端与输入语义测试|通过；10项|租户迟到响应、旧401、当前401、CSRF/幂等请求头、trace_id、operation状态及未知值语义|
|前端类型检查及构建|通过|`npm --prefix apps/web run build`先执行vue-tsc；按需注册组件后JS约495kB，无chunk提示|
|API契约生成与配置基线|通过；3项|源基线一致；待确认值null；写接口CSRF/幂等；接入/发布不声明200/202成功|
|实际jar的HTTP联调/OpenAPI响应校验|通过；33个响应|`tests/api-smoke.mjs`+Ajv2020，最终毫秒精度jar再次通过；Cookie/CSRF/Origin、幂等、游标、双租户、配置草稿/门禁、viewer|
|API生产模式硬门禁|通过|实际jar使用production启动退出1，日志含FAIL_CLOSED；未装配真实身份与持久仓储|
|Worker入口及模式硬门禁|通过|local_fixture初始化退出0，未消费耐久任务；local_fixture+production退出1|
|运行与停止|通过|本机启动API/Web成功；停止按工程路径+启动时间核验；停止后重新启动成功|
|桌面与手机页面|通过本批主链路|已安装Edge，1440×900/390×844；未观测pageerror，390px document宽度=390px|

机器可读摘要：`docs/evidence/verification-summary.json`。编译结果、日志、测试截图属于本机生成文件，不作为已交付生产系统的证明。

## 浏览器实际操作

1. 平台管理员通过同源fixture登录，API返回可信身份、租户集合及能力。
2. 创建交换机草稿后回读详情：source=fixture、状态未知、生命周期草稿、采样为空。接入返回CONFIG_PENDING及Q02/Q03/Q06，未生成operation。
3. 搜索不存在名称，显示空列表；刷新保留URL筛选及空状态。查询站点并翻页后切B租户，旧site_id/cursor清除，新列表只有B租户IP及站点。
4. 编辑monitor.sample_interval_sec草稿，登记值依旧待确认，draft_value更新；发布门禁显示Q20与trace_id，并在审计中记录DENIED。
5. viewer登录后，无创建、配置、审计或租户切换入口；相应HTTP动作另由服务端测试验证拒绝。
6. 手机客户管理员可打开创建表单、提交IPv6草稿并读取详情。表单宽度366px、位于390px视口内；详情可滚动，底部关闭按钮可达。

截图保存在 `.local/qa/`：`assets-desktop.png`、`assets-mobile.png`、`create-mobile.png`、`asset-detail-mobile.png`、`configuration-gate-desktop.png`。浏览器验证使用开发资源，无生产身份或监控事实。

## 验证局限

这是同一机器上的本地验证。第二环境复现、数据库RLS/独立库、持久事务/outbox、Worker撤权及租约、真实SSO/ITSM/Zabbix、客户H5、全浏览器、容量/性能/灾备和合同128项验收均未执行。D01–D05为局部实现，其他模块按实施状态继续开发。
