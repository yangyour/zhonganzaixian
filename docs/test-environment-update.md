# 测试环境代码更新与回退

本流程用于当前指定 LAN 测试环境 `lan-test-20261008`（192.168.1.215），从本地拉取当前开发分支、构建并更新既有应用。入口为 `tools/update-lan-test-server.py`；原 `tools/deploy-test-server.py` 是旧服务器首次部署工具，不用于当前环境的日常更新。

## 前置条件

- 在本地项目 Git 根目录运行。工作树必须干净，当前分支必须跟踪 `origin/同名分支`，本地提交必须先推送。工具不会切换分支、自动合并其他功能分支、重置文件或替用户保存未提交改动。
- Git、JDK17、Maven、项目要求的 Node22/npm、Python3 和 Paramiko 可用；npm 依赖已按锁文件安装。Windows 使用 `py -3`，已配置相同环境的其他系统可使用 `python3`。工具不安装软件或启动本机基础组件。
- 保留 Git 忽略的 `deploy/private/lan-test-connection.json`、`lan-test-known_hosts`。凭据来自已授权连接资料及现有私有配置；工具只解析字段，不执行连接资料中任何文本。SSH 必须同时通过已保存主机键及 SHA256 指纹校验。
- 服务器 `/opt/mop-lan-test/owner.json` 的项目和环境一致，应用归属标签一致，既有组件可用。服务器已有 Compose、固定镜像及私有 env；不拉取镜像、不修改原端口、不安装或升级共享组件。
- 数据库必须已经通过独立审核流程应用与本次 JAR 相同的迁移版本。脚本仅在只读事务读取 Flyway 历史，核对源码与打包 SQL 字节，拒绝缺失、失败或多余版本；API/Worker 不自动迁移。不得改写冻结 SQL。

## 常规更新

截至 2026-10-19，本地开发分支为 `feat/d07-ticket-lifecycle`，并非 `main`。要发布其他分支，应先正常切换到已审核、已验证的分支再调用本工具。`--branch` 是防止误发分支的断言，不是自动切换指令。

```powershell
Set-Location E:\work\wxw9\zhonganzaixian\mop-platform
py -3 tools/update-lan-test-server.py update --branch feat/d07-ticket-lifecycle
```

执行顺序：

1. 检查工作树、上游和本地提交；`git fetch --prune origin` 后，仅允许 fast-forward 到当前分支最新远端提交，固定本次完整 SHA。
2. 执行部署保护测试、契约测试、完整 Web 测试、Maven `package`（包含测试）、Web 类型检查与构建、静态清单复核。测试子进程清除继承的 MOP/SPRING/对象存储环境变量，外部组件测试仍需其显式前置条件；跳过的测试不能计为联调通过。任何失败立即终止。
3. 核对构建期间源码未改变；确认 Web 全部文件摘要及 API JAR 内冻结 SQL 与源码一致。日志与工件只保存在忽略的 `.local/test-deployment/<本次编号>/`。
4. 获取服务器项目部署锁，核对 owner、容器归属、实际依赖就绪与数据库历史。记录所有应用外容器的 ID、镜像、启动时间和重启次数，后续要求完全一致。
5. 建立全新不可变 `/opt/mop-lan-test/releases/<UTC时间-SHA-随机后缀>/`，上传完整应用工件及 Web。先核对归档 SHA256，再解压、逐文件核对；原版本目录不覆写。应用包括 API、Web、照片/来源导入/联系人 Worker，以及已经存在的导出准备 Worker、AF 采集器；可选 Worker 未存在时不会自动新增。
6. 以现有容器实际环境、固定镜像及挂载生成新版本的 Compose 覆盖和回退快照。完整环境可能含秘密，故只存服务器项目 `private/update-<本次编号>/` 下、权限0600；不输出到控制台、证据或 Git。现有基础 Compose、`.env`、应用私有 env 不改写。Nginx 保留当前完整安全头、缓存和路由策略。
7. 使用现有 Nginx 镜像执行候选 `nginx -t`，并在服务器回环18039启动临时 API。候选连接同一指定 PG/存储/监控，企业微信发送分发强制关闭；必须观察真实 `remote_test`、`durable=true`、`ready=true`。临时容器仅用本项目标签创建，验证后移除。
8. 再次核对原配置和迁移历史未被其他操作改变。Compose `config --quiet` 后，仅对选定应用执行 `up -d --no-deps --pull never`。不运行 `down`、`prune` 或基础组件重启。
9. 核对正式入口依赖就绪、应用完整 SHA 标签、重启次数、Web build_id/首页摘要、迁移历史和所有受保护容器。通过后原子保存 `active-update.json`，输出脱敏 `result.json`。

若机器没有对应的 npm 依赖，先在项目根目录与 `apps/web` 分别执行 `npm.cmd ci`；构建失败的具体日志位于本次忽略目录。不要用 `skipTests` 或删除失败门禁来冒充本流程通过。

## 首次启用企业微信登录与人工通知

本次用户已明确选择：启用登录与人工通知，自动业务通知保持关闭。首次部署时额外读取本机忽略的 `deploy/private/wecom-app.json`，只将应用凭证三字段写入本次服务器私有文件，0400归应用 UID10001，通过只读挂载读取。凭证不进入工件、Compose明文环境或证据。

```powershell
py -3 tools/update-lan-test-server.py update `
  --branch feat/d07-ticket-lifecycle `
  --enable-wecom-manual `
  --wecom-tenant-id 55000000-0000-4000-8000-000000000001 `
  --wecom-callback http://mop.all-online.com.cn/api/v1/auth/wecom/callback `
  --allow-http-wecom-callback
```

该参数仅适用于从未启用到启用的首次配置；之后运行常规更新会保留现有配置，不重复上传凭证或叠加域名规则。租户 ID 是现有项目测试租户，不是生产批准。

`MOP_WECOM_DISPATCH_ENABLED=true` 负责投递用户明确点击后创建的耐久消息；它不生成自动业务通知。代码状态 `automatic_notifications_enabled=false` 继续保持。启用前要求发送台账没有 QUEUED/PROCESSING 历史待发项，否则停止配置并要求核对，不自行取消或投递旧消息。工具不会调用发送消息接口、创建绑定或重复发送测试消息。扫码回调必须由实际用户完成，登录入口可用不能写成真实扫码已通过。

当前域名 HTTP80 转发到 LAN10010，而原 Nginx 固定将所有请求声明为 IP:10010。首次启用会在新的 Nginx 文件中增加固定域名白名单映射，分别保留 IP 与域名的 Host/端口；不透传任意 Host 或客户转发头。API启用框架转发头处理以保持同源和回调校验。原安全头与静态策略保留，先经真实语法检查再切换。现有 HTTPS 证书过期问题单列，未关闭证书验证；本工具当前仅支持显式 HTTP80 测试回调，TLS 入口调整需单独审核。

## 状态、证据及再次执行

```powershell
py -3 tools/inspect-lan-test-server.py --include-export-queue --include-af-collector `
  --output .local/test-environment-current.json
```

已有14服务的环境使用以上两个开关。若环境原本没有可选服务，应省略相应开关。只读脚本记录实际组件版本、运行状态和依赖就绪；本次发布 SHA 与 Web 摘要另以更新脚本生成的 `result.json` 为准。脱敏结果可复制到 `docs/evidence/`，不要复制私有快照或env。

多次更新各自使用新版本目录，保留此前工件和私有事务，不清理旧版本。再次执行会重新拉取、测试、构建并检查，不将旧成功状态当本次成功。脚本支持当前应用配置逐次延续；基础组件缺失或不可用时如实失败，并按项目要求记录 `docs/environment/component-inventory.md`，不得静默改用fixture。

## 自动回退和显式回退

切换应用期间的任何失败（包括部分容器已重建、真实依赖未就绪、静态文件不符、迁移历史变化或其他容器状态变化）都会用本次私有回退覆盖重建原应用，并重新观察就绪。只有实际观察到就绪才输出 `rollback READY`。若回退也失败，退出非0并明确报告未确认，不假称已恢复。数据库没有被本流程迁移，应用回退不会执行数据库降级。

成功发布后，如需退回该次发布之前的应用：

```powershell
# 将下方路径替换为本次 result.json 中实际 transaction 值：
py -3 tools/update-lan-test-server.py restore `
  --transaction /opt/mop-lan-test/private/update-<本次编号>/transaction.json
```

显式回退只接受当前活动事务且仅执行一次，使用保存的原镜像、环境与挂载，保留现有数据库历史，校验基础组件未变化。之后的常规更新从实际回退配方继续。不要依据旧 SHA 猜测回退目录。

网络/电源中断可能发生在应用切换前后，无法保证 Python 异常处理已执行。这时保留 `/opt/mop-lan-test/.update-lock`，先读取其 owner.json、当前活动事务及容器挂载，确认原更新进程已结束；仅在人工核对后删除这个**确切锁目录中的 owner.json 及空目录**，不要递归清理发布目录。发布在切换前已保存 `transaction.json`，即使尚未写活动指针，也可由维护者核对该事务的 `base_files`、`rollback_file`、`services`，按同样的 `docker compose --project-directory /opt/mop-lan-test --env-file /opt/mop-lan-test/.env -p mop-lan-test -f <逐项原配置> -f <rollback_file> --profile af-monitoring config --quiet` 和 `up -d --no-deps --pull never <明确应用名>`恢复。不要输出完整 `compose config`。恢复后再执行只读检查并核对活动指针，不能盲目绕过锁。

## 验证记录与边界

2026-10-19首次实现的14项隔离部署保护测试通过，覆盖部分切换失败、验后失败、无效配置、回退失败、组件范围、归属、挂载、固定域名、工作树/上游和未推送提交等边界。真实部署结果将另附本日记录及机器证据，不以隔离替身冒充服务器联调。

本流程完成应用发布与运维留证；客户正式生产批准、TLS证书、实际扫码、完整D11和全部128项合同验收继续按原记录单列，不因发布成功改为DONE。业务API契约沿用当前源码，部署工具不新增业务端点。
