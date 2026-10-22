# 紧凑页面与右上角搜索发布（2026-10-22）

用户明确要求“提交并推送远程，然后部署”。已于 **2026-10-22 08:31:47 EDT（America/New_York）** 将 `main` 的 `123b5f5cc447c47e573d4965ac64181bb9d942b1` 发布到指定 LAN 测试环境，业务入口为 http://mop.all-online.com.cn/ 。

## 仓库与发布范围

本地绝对路径 `E:\work\wxw9\zhonganzaixian\mop-platform`，origin `https://github.com/yangyour/zhonganzaixian.git`，完整组织路径 `yangyour/zhonganzaixian`，分支及上游 `main` / `origin/main`。发布前工作区干净，本地与远端完整 SHA 相等。布局提交19294c3、搜索提交123b5f5以及正常合并保留的监控接入页均已推送。

通过既有统一工具 `py -3 tools/update-lan-test-server.py update --branch main --only-api-web` 更新API/Web。其他5项应用和7项基础组件保持原生命周期与配置；后端源码及冻结SQL没有新增变更，数据库仍为PostgreSQL17.9/V50，本次0迁移。候选API连接指定组件，邮件/企微分发在候选中禁用；没有执行消息发送、设备探测或业务写入。

## 实际验证

- 本次重新执行20项部署保护、170项契约、4869项前端测试，以及完整Maven package。Java报告4196项，实际执行3154、条件跳过1042，失败/错误0；跳过项不计指定组件联调通过。
- 类型检查、生产构建、静态发布清单与安全策略通过；Nginx候选配置与回环候选API检查通过后才切换。153个工件摘要一致，Web build_id为`c5652ec31b1726e7816ce858fb6e2a572e086d9e980c5547fb811805f967bac9`。
- 切换后197项HTTP检查通过，其中逐一核对145个静态文件摘要；包括实际登录、业务读取、匿名拒绝、跨租户404、安全头和域名/IP双入口就绪。
- 正式业务域名上执行6项实际浏览器页面检查：资产、事件、用户管理在1440/390px下可读取，新页标题不占位、搜索在顶栏右侧、按钮进入工作区；事件复核默认收起。搜索点击、Ctrl+K、Esc焦点返回及真实搜索GET通过。无页面异常或整页横向溢出。浏览器为155.0.4283.62，截图仅存本机忽略目录，未提交业务表单。
- 14服务运行，ready=true、durable=true；迁移历史、原私有配置及所有非选中容器元数据与发布前一致，临时候选已移除。

此记录是测试环境应用发布证据。production_ready=false；客户生产批准、既有备份列表独立授权服务缺口和128项正式合同TC仍按原记录保留，不因UI发布改标完成。监控标准库及全禁用示例继续保留在此前不可变发布目录，本次未启用目标。

## 版本、恢复和证据

- 发布目录：`/opt/mop-lan-test/releases/20261022T123003Z-123b5f5cc447-36ae99`。
- 回退事务：`/opt/mop-lan-test/private/update-20261022T123003Z-123b5f5cc447-36ae99/transaction.json`，可按[统一回退流程](test-environment-update.md)恢复本次发布前的健康V50应用，不降级数据库。
- 当前API/Web源码版本为123b5f5；其他5个应用沿用此前603337b，未将未重启的服务标作新版本。
- [验证汇总](evidence/compact-ui-release-20261022/validation.json)、[部署](evidence/compact-ui-release-20261022/deployment.json)、[HTTP](evidence/compact-ui-release-20261022/http.json)、[组件](evidence/compact-ui-release-20261022/components.json)、[正式浏览器](evidence/compact-ui-release-20261022/live-browser.json)。原日志及真实截图只保留于本机`.local/test-deployment/20261022T122723Z-259c4c/`。

没有改动API契约、运行参数、端口或秘密引用。原界面实施记录中的“未部署”是当时状态，本记录确认其现在已发布；文档归档提交与已发布工件源码提交分别保留。
