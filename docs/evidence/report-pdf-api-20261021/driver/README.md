# 临时环境复现记录

本目录保存实际 attempt-3 的驱动，不是生产部署脚本。路径指向本次临时 Java、PG17.11、JUnit 和两个明确配置的字体。先独立准备这些工具、classpath.txt 与编译好的合成 SeedPdfReport，再核对 snapshot.json 中 JAR/迁移摘要。必须使用新的 attempt 目录，避免覆盖既有证据；不得指向现有业务数据库。run.py 创建并清理自己的临时数据库、角色和凭据，禁用邮件/企业微信，只监听回环地址。

字体文件不提交。Maven verify 使用 maven.jar.forceCreation=true，避免增量重打包沿用旧依赖。HTTP 执行过程中不能重建正在使用的 JAR。

正向读取前后比较105张表；错误处理原有安全审计不计入正向只读边界。实际服务关闭和数据库清理由 cleanup.json 证明。原始日志与 JUnit XML 不提交，避免携带环境信息。
