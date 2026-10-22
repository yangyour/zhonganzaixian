# 2026-10-22 最新主干重部署与真实浏览器复核

本次用户要求继续拉取并把最近改动全部更新到测试环境。最终已于 **2026-10-22 07:47:24 EDT（America/New_York）** 发布 `603337b58db73165b3bd0a58094d68d5d4b387bb`；7应用统一到同一源码标签，14服务运行，真实依赖ready=true/durable=true。PG17.9保持V50，本次0迁移；production_ready=false，正式合同验收未完成。

## 仓库与版本

本地绝对路径 `E:\work\wxw9\zhonganzaixian\mop-platform`，规范origin `https://github.com/yangyour/zhonganzaixian.git`，完整组织路径 `yangyour/zhonganzaixian`，分支main/上游origin/main。先快进至74396c3，包含刚合并的web-redesign、全站功能布局清理与备份版本登记消费者；再正常合入93226bd监控预设工具，以及本轮实际浏览器检查后提交的辅助文字对比度修复。没有更换客户仓库、强推或覆盖他人未提交修改。

后端完整构建基线74396c3。后续只改CSS/合成浏览器入口、监控标准库工具/配置与记录，后端源码及六个JAR摘要保持一致；修复后的完整前端测试、类型/构建/清单重新通过，复用本轮已经执行的Java测试，不把未重跑测试标为新执行。

| 项目 | 实际结果 |
| --- | --- |
| 内网入口 | http://192.168.1.215:10010/login |
| 业务入口 | http://mop.all-online.com.cn/login |
| 环境 | lan-test-20261008 / mop-lan-test |
| 应用 | API、Web、照片/来源导入/联系人Worker、导出准备Worker、AF采集器共7项 |
| 发布目录 | `/opt/mop-lan-test/releases/20261022T114531Z-603337b58db7-fb588e` |
| 逐文件核对 | 154 个文件/工件SHA256一致，包含六个JAR、完整Web、Nginx和三个监控工具/配置文件 |
| Web build_id | `7f4037f36d92941b28991d6e38f8f0b0c264bae81d0efdda1f65e60dc68e45f1` |
| PostgreSQL | 17.9/V50；源码与打包资源一致，前后Flyway历史相同，0迁移 |
| 基础组件 | PG、MinIO、Redis、RabbitMQ、Zabbix三容器共7项ID/镜像/启动时间/重启次数不变 |
| 运行配置 | 原Compose/.env及应用私有env保持，端口/组件镜像/凭据不变 |

## 构建、浏览器与真实服务检查

- 部署保护20项、契约170项、Web4815项/290文件通过；前端类型、生产构建、静态清单及发布策略通过。
- Maven全模块package成功，4196项报告，实际执行3154项、条件跳过1042项，失败/错误0。跳过实库测试未算指定组件联调通过；没有本机新建数据库、缓存、监控或fixture替代指定环境。
- 合成组件真实Edge浏览器：320/390/768/1440四视口最终4项通过，axe违规0；键盘焦点、独立横向滚动、只读刷新、页面宽度及写请求保护均保留。320和1440截图已人工查看，属于SYNTHETIC组件证据。
- 服务器临时回环候选API连接真实PG/存储/监控且ready；候选邮件/企微分发关闭，随后移除。正式切换后195项真实HTTP通过，包括六类账号登录/会话/资产读取、主要读取接口、匿名拒绝、跨租户资产404、安全头、缺失JS404和两个入口就绪。
- 正式入口逐个读取143个静态文件，SHA256与新清单一致。14服务运行，7应用源码标签/只读挂载及零异常重启核对通过，原基础组件生命周期保持。
- 蒋发明JiangFaMing账号真实登录HTTP200；在正式测试站以该账号进行资产页和事件页的1440/390px四项真实浏览器读取检查，路径/标题、无页面运行异常、无整页横向溢出均通过，未提交业务写入。真实页面截图留在本机忽略目录，单独的[浏览器证据](evidence/redeploy-20261022/live-browser.json)不与合成扫描混用。
- 监控预设目录、标准库CLI及全禁用示例已随不可变版本交付；在服务器仅运行catalog/validate，13个预设、13个禁用目标校验通过，network_accessed=false。未绑定模板、启用采集、扫描设备或写入供应商配置；该切片37项Python测试另有本轮证据。

## 发布前检查发现与修复

首次四视口因辅助文字对比度失败（4.15:1及3.07:1），合成验收入口没有引入最新生产workspace/control层。按实际App样式次序补齐两层样式后，统一控件层小字仍为4.09:1。仅将两个辅助文字令牌由#6a7b90调为#5f7086，事件计数采用同一工作区令牌及兼容回退，未改变布局、数据、接口、租户、CSRF、幂等或写流程。原浏览器断言不变，最终4项及修复后完整4815项Web/类型/构建/清单通过；不宣称全站WCAG符合性。

新增监控预设Python测试首次在GBK默认编码下导入失败，显式UTF-8模式重跑37项通过。回环HTTP/TCP均为合成验证，未探测真实设备；禁用示例和32项待确认执行依赖保持原状态，未自动授予设备备份执行。

## 网络恢复与部署保护

发布准备阶段保存目标192.168.1.215:22出现banner EOF/超时；当时本机192.168.5.0/24、VPN只有10.8.0.0/24明确路由，默认路径选中FlClash TUN，而业务域名仍是旧版ready。先保留旧站点和完成不依赖SSH的构建，没有关闭全部代理、改全局VPN/路由或把旧站点HTTP200当作新发布。

用户明确回复“已恢复”后，重新通过固定主机密钥SSH认证、项目owner、14服务、PG17.9/V50及真实依赖就绪核对，才继续上传和切换。失败与恢复分别记录，不将管理通道短时不可达登记为数据库/基础组件离线。

部署使用既有统一更新器及本机已验证工件；增加的三个监控文件只随版本交付。新目录不覆盖旧工件，Nginx语法与候选API验证、原配置与迁移历史并发守卫、仅7应用up --no-deps --pull never、失败回退及验后守卫均保留。本轮没有触发实际回退或另做回退演练。

## 回退与剩余边界

本次事务：`/opt/mop-lan-test/private/update-20261022T114531Z-603337b58db7-fb588e/transaction.json`。可按[统一流程](test-environment-update.md)恢复本次之前的健康V50应用配方，不降级数据库。V50以前旧身份目录不兼容的应用恢复限制仍见[首轮迁移记录](test-environment-update-20261022.md)，不能混用更早的事务或重写冻结SQL。

备份版本列表的独立授权服务仍未装配，真实503 BACKUP_VERSION_INVENTORY_NOT_CONFIGURED作为明确门禁保留；不会用草稿权限或成功空列表代替授权。人工邮件/企微配置原样延续，本次未发测试消息；自动执行与客户生产批准不因代码交付而启用。128项正式合同TC保持原状态。

证据：[总验证](evidence/redeploy-20261022/validation.json)、[部署](evidence/redeploy-20261022/deployment.json)、[构建](evidence/redeploy-20261022/build.json)、[HTTP](evidence/redeploy-20261022/http.json)、[组件](evidence/redeploy-20261022/components.json)、[正式浏览器](evidence/redeploy-20261022/live-browser.json)、[监控文件](evidence/redeploy-20261022/monitoring-assets.json)、[320px合成截图](evidence/redeploy-20261022/compact-events-320.png)、[1440px合成截图](evidence/redeploy-20261022/compact-events-1440.png)。秘密、完整日志、真实页面截图和回退环境仅存本机/服务器私有忽略目录。
