# Windows 接续指定测试库 V7–V15

2026-10-03 本地已将 `2acf9f0` 接回，指定服务器可经固定主机键 SSH 访问。原库 PostgreSQL17.9 的 `mop_test` 仍为 V6，不能直接用 niko 库已到 V15 的结果宣称本地就绪。

`deploy/migrate-test-schema.ps1` 现在复用 Java 独立迁移 CLI 的全部逐功能模式。无参数始终 `--check`，通过只读连接核对现有历史、结构、ACL，不执行迁移；不提供“全部执行”或自动补齐。原 `-ApplyPhotos` / `-ApplyAssetImports` 兼容，不能与其他 Apply 组合。新增显式 `-ApplyFeature` 依次支持：

|版本|功能参数|
|---|---|
|V7|asset-dependencies|
|V8|worklogs|
|V9|asset-metadata|
|V10|internal-notes|
|V11|ticket-comments|
|V12|working-calendars|
|V13|manual-incidents|
|V14|manual-recoveries|
|V15|manual-warranty|

先停止本工程拥有的 API/Worker，执行根 Maven 构建及 `deploy/prepare-migration-classpath.ps1`。默认运行迁移脚本查看真实版本；按已审阅功能逐次运行，例如 `./deploy/migrate-test-schema.ps1 -ApplyFeature asset-dependencies`。Java CLI 检查前置版本，不允许跳级；已执行功能重复请求只检查、执行0次。先保存项目测试库恢复副本，不能把本文件当成其他环境或生产执行许可。

Windows 包装层在连接前校验固定清单覆盖 V1–V15、无重复/未知资源，源 SQL 与编译 SQL 都逐字节匹配已有 `tools/niko-test/frozen-*.json` 的审核摘要。私有配置只在本机读取；迁移账号只传入独立 CLI 的进程环境，不进入 API/Worker。数据库身份仍固定 `mop_test` / `mop_test_migrator`。没有修改已执行 SQL、服务器端口、组件全局配置或其他项目权限。

本次真实执行 V7–V15 各1次，每个功能重复执行均0次，最终只读检查0次，结构/ACL全部通过。项目库 pg_dump 自定义格式副本保存在忽略目录，已记录文件SHA256；未执行恢复演练。46项无数据库的保护测试覆盖默认/旧/新模式、组合与未知参数、源码/编译字节篡改、缺失/多余资源、空与重复清单，已加入 CI。

首轮保护检查发现历史本地 V1 检出仍为 CRLF，连接前拒绝；随后恢复为已冻结的 Git blob 原字节、重新复制构建资源，没有改 SQL 逻辑或修复 Flyway 历史。实际API仍0.26.0，迁移入口变化不增加业务API。完整应用、存储流水线、UI和128项验收独立记录；schema升级不能代替它们。

证据：[本地 V15 结构接续](evidence/local-schema-v15-20261003.json)。
