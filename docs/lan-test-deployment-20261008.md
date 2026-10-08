# 新内网测试环境部署（2026-10-08）

当前新测试环境为 `192.168.1.215`，环境标识 `lan-test-20261008`。用户本轮授权连通性验证和全套部署。主站入口为 [MOP 登录](http://192.168.1.215:10010/login)，监控组件入口为 [Zabbix](http://192.168.1.215:10020/)。12 个服务运行，就绪为 `remote_test / ready=true / durable=true / production_ready=false`。

本日随后用户要求上传下载切换到可配置MinIO，后端及两个存储Worker已更新至77b41cf，使用显式minio。36次上传/授权下载及历史原图回读、5种配置切换/拒绝场景通过；数据库V30、Web与其余9个容器保持，详见[存储配置说明](configurable-object-storage.md)。下文e58fc9e为本日首次全套部署基线。

部署的 Web 与后端源码基线为 `e58fc9ecb6751b22d85e55141d888157e36bad8a`，实际 API 契约为 `0.45.0`，项目数据库为 V30。最新机房背景和登录交互已在此新入口部署。旧服务器不迁移数据、不停止服务，其历史验收记录单独保留。

## 连通性与容量

ICMP 两次均收到响应；SSH22 通过提供的账号真实认证。首次 .NET 端口探测被本机套接字访问策略拒绝，不能作为服务器不通的结论；随后原生 OpenSSH 和 Python SSH 均成功。主机键经原生 OpenSSH 观察后固定在私有 known_hosts，后续 SSH 使用 RejectPolicy，并再次核对 SHA256 指纹。

实测系统为 Ubuntu 26.04.1 LTS / x86_64，4 核、7329 MiB 内存，根盘可用约 86 GiB。初始主机未安装 Docker、Java、数据库等组件。sudo 认证成功后，仅安装新环境所需的软件包，没有执行系统整体升级、autoremove 或改变旧服务器。

## 服务与实际版本

| 服务 | 实际版本/状态 | 访问与用途 |
|---|---|---|
| Docker / Compose | 29.1.3 / 2.40.3 | 新服务器运行容器，镜像按摘要固定 |
| Web | Nginx 1.31.6 | 10010；静态页面、API 反代 |
| API | Java 17.0.20.1；契约 0.45.0 | 仅回环18010；项目内账号及租户授权 |
| 照片 Worker | 当前构建，真实处理通过 | PostgreSQL 作业、受限 MinIO 原图存储 |
| 来源导入 Worker | 当前构建，真实处理通过 | 原文件存储、独立 CSV 解析子进程 |
| 联系人 Worker | 当前构建，真实处理通过 | 联系人耐久作业、归档与原202回放 |
| PostgreSQL | 17.9；mop_test / V30 | 仅回环12060；独立 migrator/runtime，运行账号无DDL |
| MinIO | RELEASE.2025-09-07T16-13-09Z | 仅回环19000/19001；两个独立桶及前缀受限账号 |
| Redis | 7.4.9，运行 | 仅回环12070；独立数据卷和口令，MOP缓存业务接入尚未完成 |
| RabbitMQ | 4.3.2，运行 | 仅回环5673/15673；独立mop_test vhost/账号，MOP投递联调尚未完成 |
| Zabbix Server / Web / Agent | 7.0.31，真实agent.ping=1 | Web10020；单独mop_zabbix_test库及受限数据库账号 |
| 单次导出准备 Worker | 当前构建，实际生成XLSX | 按明确作业单独运行，不设常驻自动发布服务 |

表内 Web、API、三个常驻 Worker、四个基础组件、三个 Zabbix 组件共12个常驻容器；导出准备 Worker 为额外的显式单次进程。所有12个容器配置 `unless-stopped`；本轮没有进行整机重启验收。

## 工件、初始化与验收

85 个构建交付文件上传后逐项 SHA256 一致；Nginx 配置和导入临时目录单独生成。前端1822项测试、类型检查与正式构建通过；Java根工程构建成功，本轮打包使用skipTests，不将打包结果计作Java单元/集成测试通过。

空库由独立 `LanSchemaBootstrap` 显式执行 V1–V4。V5–V30由现有独立迁移CLI逐项执行，每个1次、每个重复0次，最终只读检查0迁移。结构与ACL校验通过，SQL未改写；新环境V1–V30保持冻结，后续结构修正使用V31或更高。应用与Worker不携带迁移凭据、不在启动时执行DDL。六个明确标识的测试身份使用项目内账号，Argon2id哈希保存在真实PostgreSQL中，MOP密码策略字段仍全null。

实际验证结果：

- [身份 HTTP](evidence/lan-identity-http-20261008.json)：53次请求通过，包括六角色登录、CSRF、匿名/只读拒绝、受限角色、撤权、密码重置和会话失效；新增合成账号/角色已停用。
- [真实浏览器](evidence/lan-test-ui-20261008/result.json)：Edge桌面和390px手机视口13项通过，8张截图，0 pageerror；新增合成账号/角色已停用。手机视口是浏览器仿真，不是实际手机安装验收。
- [Worker链路](evidence/lan-workers-20261008.json)：累计49次实际HTTP，照片存储/授权原图与摘要、原文件存储/真实CSV解析、联系人创建/归档/原202回放通过；无悬而未决的写请求。
- [导出准备](evidence/lan-export-20261008.json)：真实HTTP受理的单个作业由服务器Java Worker生成1行数据/16列XLSX，ZIP/XML及PG保留字节摘要核对通过。没有对象存储发布、下载授权或通知。
- [API重启](evidence/lan-api-restart-20261008.json)：只重启本项目API，19张相关业务表行数及摘要相同，迁移历史仍30条，其余11个容器启动时间相同；随后服务器就绪。
- [Zabbix](evidence/lan-zabbix-20261008.json)：默认管理员密码已替换，旧默认口令验证失败，新随机口令登录及实际Agent回包通过。采集范围是明确标注的测试Agent容器，未冒充客户设备监控。
- [最终只读盘点](evidence/lan-deployment-final-20261008.json)：临时下载代理和验证转发关闭后，12个容器仍运行，服务器自身ready/存储依赖通过；本机直访两个网页入口均200。

## 安装过程中已解决的问题

Ubuntu原软件源下载较慢，改为从其他镜像获取同版本软件包，并逐个核对服务器已签名APT索引中的SHA256后离线安装。Docker Hub经临时SSH回环下载通道取得组件镜像，下载结束后移除临时Docker代理配置；运行不依赖本机代理或本机数据库。

原MinIO镜像仓库无法拉取。MinIO、Redis和RabbitMQ从旧服务器只读 `docker save` 导出，压缩文件传输前后摘要一致。旧导出器的OCI索引只引用一个镜像，已重建包含三个原始manifest的索引；新Docker按manifest显示镜像ID，旧Docker按config显示ID。每个原始config及所有rootfs层逐项相同，未改镜像程序或旧服务生命周期。

Compose中tmpfs带逗号的参数需要加引号；导入Worker的只读挂载中需要预建`.local`挂载点；Nginx所有临时缓存目录必须指向可写tmpfs。上述问题已修复，保留只读文件系统和原进程隔离要求。

联系人首轮在新空环境触发既有 `LAST_ENABLED_DECISION_MAKER` 约束，原失败作业/证据保留在[首轮记录](evidence/lan-workers-initial-20261008.json)。通过正式API补建一个“新环境虚构测试决策人”后，新独立作业处理成功；该测试决策人保留启用、联系方式为空、未发送通知，没有取消或绕过业务约束。

## 私有配置与运行说明

连接信息只在 `deploy/private/` 保存，整个目录Git忽略。新环境固定主机键、SSH账号以及业务测试账号分别引用 `lan-test-connection.json`、`lan-test-known_hosts`、`lan-test-runtime.json`。MOP平台管理员用户名为 `moptest-padmin`，Zabbix管理用户名为 `Admin`；随机密码仅查阅本机私有配置，本文不记录。服务器运行凭据在 `/opt/mop-lan-test/private`，目录700、环境文件600；Redis配置按固定容器组授予最小读取权限。

服务器项目目录为 `/opt/mop-lan-test`，版本目录为 `/opt/mop-lan-test/releases/e58fc9ecb675`。受限角色、桶、vhost、命名卷及所有新增测试数据均归属于本项目测试环境。使用[部署模板及说明](../deploy/lan-test/README.md)，只读检查入口为 `tools/inspect-lan-test-server.py`。旧服务器的部署/初始化脚本保留历史用途，不在新环境盲目运行含旧组件名或OIDC假设的初始化步骤。

## 尚未解除的业务与组件边界

全套现有应用及上述组件已部署，不能据此宣布128项合同全部完成。客户资产/租户监控映射、阈值及事件规则、监控向MOP的真实接入仍为CONFIG_PENDING；Zabbix真实采集证据不代替这些规则。Redis/RabbitMQ仅完成组件运行和基本探测，不冒充MOP业务联调。

阿里云OSS的真实凭据及正式存储批准、企业微信/通知通道、堡垒机/CMDB、正式身份源与相关Q编号继续登记。当前存储使用独立MinIO测试实例，默认项目本地账号，SSO接口预留但关闭。备份执行、发布、下载、通知及正式SLA未知值不自动补齐，`production_ready=false`和正式合同`NOT_RUN`保留。
