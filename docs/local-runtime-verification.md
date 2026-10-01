# 本地接续验证（2026-10-01）

用户最新要求本地继续开发。已拉取云端完整工作分支及最后交接提交`1db3976f10f94956ab6b2be500c67663d14f5b18`，云端已回报停止业务写入、无开发子代理和未提交文件。继续全部128项及逐功能提交规则，不将云端纯测试记录合并为本轮组件验收。

## MinIO JVM兼容修复：独立验证通过

首次完整Maven构建揭示MinIO 9.0.3依赖的OkHttp 5.3.2默认artifact只有Kotlin多平台元数据，Java不能找到`okhttp3`类；新版上传builder的两个长度参数为`Long`。本轮保留MinIO版本，明确引入`okhttp-jvm:5.3.2`、排除空的多平台artifact，根工程对齐Kotlin 2.2.21，并传入long长度参数。保持loopback固定命名空间、禁止重定向、条件PUT、原图hash/size验证及异常脱敏。

文件Controller的隔离重放测试重设已有Mockito回答时提前调用了null Supplier，造成测试自身NPE。改用`doReturn`配置已提交重放结果，继续验证存储断链时新请求503、已有受理回放不重新调用存储。

本轮针对该修复执行16项：MinIO适配器5项隔离安全测试、FileController 8项隔离测试、指定服务器MinIO 3项真实存储测试，0失败/跳过。真实测试使用本项目`mop-test-photos`和受限账号，只保留明确虚构测试对象，不改变其他项目、安装组件或启用删除。机器证据见[SDK验证](evidence/local-minio-sdk-verification.json)。

工作区完整Maven编译/隔离测试和打包也已通过，但工作区含尚未提交/验收的迁移与照片改动；不能用这条结果宣称SDK修复提交完成全部照片应用、PG文件事务、OIDC HTTP或UI验收。V5仍未执行，照片正式批准UNVERIFIED、扫描UNSCANNED、留存null。完整链路验证继续推进。

## 环境接续

只读预检确认本机私有runtime仍在、指定PG17.9和OIDC发现可用，Flyway仅V1–V4成功、照片表尚不存在。旧隧道PID已被其他程序复用；经PID/启动时间/脚本路径和端口核验，只归档本项目失效记录并创建新的工程隧道，没有停止其他程序。原始连接文件、凭据和会话仍只保存在Git忽略的私有目录。
