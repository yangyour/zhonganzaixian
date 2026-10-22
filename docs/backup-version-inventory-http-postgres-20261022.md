# 备份版本列表：真实 HTTP / PostgreSQL / 前端载荷回放

## 实际执行范围

新的可丢弃PostgreSQL17.11集群仅监听127.0.0.1:28177，无Unix套接字。既有V1–V4初始化，V5–V50逐项显式应用；重复V50和前后只读检查均0迁移，源SQL摘要未变。没有操作原服务器。

BackupVersionInventoryHttpPostgresTest实际启动仅回环、随机端口的Tomcat，使用local_fixture安全过滤链、仅测试类中存在的合成会话入口、真实PostgresPlatformRepository成员/能力读取及真实备份版本分页仓储。枚举授权和逐版本授权分别显式注入；测试还在事务内锁定当前成员/账号并核对实际权限版本。独立备份授权是明确的合成门禁，不是生产配置发布或默认padmin授权。

准备2条已登记版本及1个没有版本登记的授权资产，准备阶段使用既有V49仓储以及合成KMS/内存对象/采集完成证明。它证明数据库登记关联，不证明实际设备采集、云备份或可恢复性。

## 12个实际 HTTP 请求

状态序列：401、200、503、200、200、200、200、400、400、403、403、404。

- 未登录拒绝；合成会话建立；缺省无查询绑定明确503，而非空列表。
- 绑定后每页1条的首/次页正确使用登记时间/版本UUID成对游标，2条版本各出现一次。
- 默认15条页返回同2条版本，合法空资产返回0条，均不编造全局总数或当前对象健康。
- 非法租户覆盖与不成对游标400；独立枚举撤权403；lookahead隐藏行无逐版本权限时整页403、不返回部分data；不存在资产404，不假装空成功。
- Cache-Control:no-store和Referrer-Policy:no-referrer核对；无KMS/对象地址/下载授权泄露。
- 正向元数据读取前后111张表（110业务表+Flyway历史）完整快照一致，额外对象/完成证明回调0次。失败请求保留正常拒绝审计，不把安全审计误判为业务写入。

整个测试生命周期仅7张业务表按合成准备/拒绝审计发生变化：资产、设备协调、运行、归档源、归档事件、版本登记和审计；另外103张业务表指纹不变。正向GET无写入与准备/拒绝审计两种口径分别记录。

## 前端与契约回放

真实返回的默认页、成对分页和空页保存为明确命名的synthetic fixture，不含会话cookie、CSRF令牌、密钥或原配置字节。新增3项严格读取器回放、1项真实Vue组件DOM展示及1项OpenAPI/AJV契约回放。回放证明已捕获DTO与前端/契约兼容，不等于浏览器连接实时后端。

生产UI/接口契约没有改动。实际浏览器、axe、截图仍NOT_RUN，105项规格/12文件只是可发现；128正式合同TC仍NOT_RUN。local_fixture会话不是生产本地密码/OIDC登录验收，独立授权配置仍未装配。

## 清理与门禁

Tomcat关闭，PG停止，临时数据库和凭据删除；没有真实云服务、消息、设备命令、账号变更或自动执行。新测试类和合成登录入口不打入生产JAR。默认Maven运行跳过该实库HTTP测试，只有MOP_BACKUP_INVENTORY_HTTP_PG_TEST_ENVIRONMENT=dot-backup-inventory-http-pg-disposable且临时回环mop_test连接齐全时才执行。

实际执行命令：mvn -pl server/api -am -Dtest=BackupVersionInventoryHttpPostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Dmop.inventory.http.pg.evidence=<本地证据文件> test。

精确测试数、构建及清理见docs/evidence/backup-version-inventory-http-postgres-20261022/；实际返回样本见fixtures/backup-version-inventory-http-postgres-synthetic-20261022.json。
