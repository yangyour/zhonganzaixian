# LAN测试部署模板

2026-10-22 最新运行：API/Web `055048b`、API0.77.0、PG17.9/V50，14 服务真实就绪；五个 Worker 保持本轮首发 `454a018` 且后端工件与最新主干相同。194 项真实 HTTP 检查通过，迁移与回退不能混用旧 API，见[本次记录](../../docs/test-environment-update-20261022.md)。下文旧版本为历史。

2026-10-19最新运行版本：`34a183e` / API0.58 / V38，7项应用由统一脚本更新，14服务真实就绪，7基础组件生命周期不变。企业微信登录及人工通知已启用，自动业务通知关闭；53项HTTP检查通过，本次未发送消息。活动覆盖路径见服务器 `active-update.json`，详见[实际执行记录](../../docs/test-environment-update-20261019.md)。下文旧日期版本为历史。

日常更新统一使用[测试环境更新与回退流程](../../docs/test-environment-update.md)及 `tools/update-lan-test-server.py`。它拉取当前跟踪分支、执行测试与构建、逐文件校验不可变版本、候选API真实就绪检查、仅更新既有应用并保存回退事务；首次部署与旧临时脚本不再作为日常更新入口。实际执行结果以当次脱敏证据为准。

2026-10-14最新运行版本：导出消息API0.48/V31、常驻准备队列和顶部横排模块；仍为13服务。`WEB_CONFIG_FILE`指向新不可变Nginx配置，CSP/缓存/静态gzip已实际应用。部署仅更新API/Web，不修改基础RELEASE_DIR和其他Worker版本；[完整记录及回退](../../docs/export-inbox.md)。

API0.48新增[本人导出消息](../../docs/export-inbox.md)：先使用独立CLI显式`--apply-export-inbox`并复核V31，随后仅在API私有env开启`MOP_EXPORT_INBOX_ENABLED=true`；准备Worker不需要此开关或签名密钥。V1—V31冻结，不在应用启动时迁移，旧发布不补发。

2026-10-14自动准备应用已部署，当前含可选覆盖服务共13个常驻容器；只读检查须加`--include-export-queue`。保留原固定UTC启用时间，沿用API0.47/V30；发布详情与回退见[本次记录](../../docs/asset-export-automatic-preparation.md)。

2026-10-14当前测试站已部署API0.47及顶部横排模块导航。台账准备可显式启用[常驻自动队列](../../docs/asset-export-automatic-preparation.md)，原单作业Worker继续保留；文件发布/900秒签名下载另行由已授权创建人在页面确认，见[导出发布配置与限制](../../docs/asset-export-publication.md)。启用时将独立导出桶/账号、`MOP_EXPORT_PUBLICATION_ENABLED`、`MOP_EXPORT_DOWNLOAD_SIGNING_KEY`仅写入API私有env；不把签名密钥给准备Worker，不自动发布历史作业，不重启存储/数据库等无关服务。

2026-10-12基础网络监控已接入MOP，见[运行参数与验收](../../docs/basic-network-monitoring.md)。API私有env显式启用MOP_NETWORK_MONITORING_ENABLED，提供回环Zabbix端点、受限只读token及固定资产绑定JSON。部署分别指定API_RELEASE_DIR和WEB_RELEASE_DIR；后者缺省沿用RELEASE_DIR。先在不可变新版本目录校验工件SHA256，再仅更新本项目api/web，保留旧私有env及版本引用以便回退。不得修改已在使用的静态文件目录或重启无关组件。

2026-10-10新增四台明确设备的只读网络预检：`python tools/probe-lan-devices.py --output <报告路径>`。只读取本目录`devices.json`和原私有SSH配置，从服务器与Zabbix网络命名空间执行有界ICMP/TCP检查；不安装组件、不创建监控主机、不登录设备。退出0表示各地址均观察到网络响应，2表示至少一个未确认，1表示探测基础设施失败；这三种状态均不代表监控就绪。详见[输入输出和复验说明](../../docs/device-network-preflight.md)。

本目录对应2026-10-08授权的新内网测试环境，部署结果和证据见[部署记录](../../docs/lan-test-deployment-20261008.md)。它包含12个常驻服务的Compose模板和独立空库初始化CLI。所有敏感值读取Git忽略的`deploy/private/`，不得将填好的环境文件或口令写入本目录。

## 顺序与命名空间

先只读核对SSH主机键、容量、组件和占用端口。目录`/opt/mop-lan-test`必须有与本项目一致的owner.json；已有无主目录、同名容器、数据库、账号或桶应先查明归属，不能直接覆盖。

在新环境安装Docker与Compose，验证签名软件源/软件包摘要。镜像取得后固定为已核对的image ID，放入服务器项目目录的私有`.env`：PG_IMAGE、JAVA_IMAGE、NGINX_IMAGE、MINIO_IMAGE、REDIS_IMAGE、RABBIT_IMAGE及三个ZABBIX镜像变量；RELEASE_DIR必须是已核对SHA256的版本目录。临时下载代理只用于组件获取，在启动业务前移除。

构建命令为`mvn -B -DskipTests package`及`npm run build`（apps/web）；skipTests仅表示构建，不能代替测试。部署api.jar、三个Worker JAR、单次导出JAR、`server/import-parser/target/parser.jar`及完整Web dist。预建`RELEASE_DIR/.local`，用于导入解析器的临时tmpfs挂载；应用交付目录只读。

将compose.yaml置于服务器项目目录，私有env文件分别为postgres、minio、rabbitmq、api、photo、import、contact、export、migration及zabbix.env，另有redis.conf。每个应用角色仅获得实际需要的受限账号；API与Worker不含迁移凭据。公开入口10010，API18010、数据库12060、存储19000、缓存12070及消息5673仅绑定回环。Zabbix Web初始只绑定回环10020，更换默认管理员口令并真实登录验证后再开放测试访问。

执行`docker compose config --quiet`检查配置；不要输出完整config，因为它会展开env文件中的秘密。先启动postgres/minio/redis/rabbitmq，在空项目库创建mop_test_migrator和mop_test_runtime受限账号。数据库mop_test、迁移账号名及回环12060是初始化CLI的固定保护条件。

## 显式迁移与启动

编译LanSchemaBootstrap.java，使用已构建API中的相同Flyway/PG依赖。仅对空数据库运行`LanSchemaBootstrap --initialize-empty-lan-test-database`，同时显式设置`MOP_NEW_TEST_ENVIRONMENT=mop-lan-test`。初始化执行V1–V4，非空库拒绝再次初始化。

随后逐项调用`cn.mop.persistence.TestSchemaMigration --apply-<feature>`，按源码支持的V5–V30顺序执行，每步核对结构/ACL、重复0次和最终`--check`。运行程序从已验证JAR读取SQL；V1–V30保持冻结。API/Worker启动不得迁移。

明确初始化六个虚构身份的项目内口令，使用既有`ProjectIdentityBootstrap`，口令从stdin传入。密码策略保持null；SSO关闭。MinIO分别配置mop-test-photos的photos前缀，以及mop-test-asset-imports的asset-import-sources前缀，账号只允许Get/Put及必要桶查询；真实核对删除、前缀外和跨桶拒绝。

完成以上步骤后启动Web、API及三个Worker。Zabbix使用单独mop_zabbix_test库和受限账号，仅为明确的合成测试Agent建监控项，不将它映射成客户设备。API及业务链路都通过以后，再确认12个容器运行、服务脱离临时本机转发仍就绪。

单次导出Worker使用私有export.env，加显式`MOP_EXPORT_PREPARATION_WORKER_ENABLED=true`、稳定worker ID及技术租期，运行`java -jar /app/export-worker.jar --prepare-one-without-publication <已核对所属作业UUID>`。它只准备并保留XLSX，不发布、上传OSS、授权下载或通知。

## 日常只读检查

本地安装已声明的Paramiko依赖，以实际Python执行：

```text
python tools/inspect-lan-test-server.py
```

该入口只读取SSH固定主机键、项目owner、12个容器、真实版本、迁移历史及服务器ready。可用`--output <脱敏报告路径>`保存结果，不输出环境文件或凭据。服务重启、迁移或命名空间变更必须针对已核对的本项目对象单独执行；不得对旧服务器或无关服务操作。


2026-10-14前端发布准备：Web构建新增静态产物完整性/大小门禁及release-manifest.json。候选[静态缓存片段](web-static-locations.conf)须替换原静态location块，保留API/认证反代，不可直接与原块重复include。需要Nginx≥1.29.3以合并继承安全头；本项目1.31.6语法已验证，尚未应用。上传后用`node tools/verify-web-release.mjs --dist <候选web目录> --check --expected-build-id <已记录值>`只读核对，不能在服务器上重新生成清单冒充构建原摘要。完整流程与回退见[第二轮上线准备](../../docs/frontend-release-readiness-20261014.md)。

## 自动准备应用覆盖配置

独立`export-queue.compose.yaml`只增加本项目准备Worker，使用私有`export-queue.env`和已核验`EXPORT_QUEUE_RELEASE_DIR`。开启时固定UTC启用时间、受限PG角色与技术租期；重启保留同一启用时间。先使用两个Compose文件执行`config --quiet`，再`up -d --no-deps export-preparation-worker`，不要重新启动基础组件。验证用`python tools/inspect-lan-test-server.py --include-export-queue`，同时提交新虚构请求核对实际XLSX。不开覆盖文件时原12服务模板与单任务入口保持可用。


上线优化第三轮补充：静态片段还包含CSP/安全头与仅静态HTML/JS/CSS的gzip设置；合并时替换现有同名头、gzip和location配置，避免重复。保留照片blob预览及self相机权限，Element Plus仅样式保留inline兼容。当前测试站仍未应用这些候选，本轮只做nginx -t和本地真实API浏览器验证。发布后必须核对实际CSP、Content-Encoding、Vary及Secure Cookie/TLS，不仅检查文件存在。标准矩阵和证据见[上线优化第三轮](../../docs/launch-optimization-20261014.md)。

本次Web配置支持独立`WEB_CONFIG_FILE`，缺省仍为原`RELEASE_DIR/nginx.conf`。安全头/缓存/静态gzip候选应合入新不可变完整配置，保留API反代及同源头；先`nginx -t`后仅重建api/web。不要为切换Nginx而改动基础RELEASE_DIR或其他Worker引用。
