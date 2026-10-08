# 可配置的上传与授权下载存储

2026-10-08用户要求上传下载切换到MinIO，并可配置、可切换。新增显式`OBJECT_STORAGE_PROVIDER=minio`，与已有`oss`共用选择入口。API、照片Worker和来源导入Worker采用相同配置解析；下载继续通过平台会话及grant鉴权、大小和SHA256核对后返回原字节，不向浏览器下发存储凭据。

已部署到192.168.1.215的10010入口，后端源码基线77b41cf。API、照片Worker和来源导入Worker均为显式minio，旧MOP_S3/MOP_IMPORT_S3参数已从三个运行env移除，目标仍是原两个测试桶，既有原图继续可授权下载。其余9个常驻容器启动时间相同。数据库仍V30，本功能没有结构迁移或新的HTTP路径，现有0.45 API契约不变。

## 私有配置与切换

配置模板见[MinIO环境变量示例](../deploy/minio-storage.env.example)。将实际配置保存在Git忽略的私有文件或服务器项目private env中。空凭据或非法值会拒绝连接，不存在演示账号、备用存储或自动OSS/MinIO回退。

| 配置 | 含义 |
|---|---|
| OBJECT_STORAGE_ENABLED | true显式启用；false显式关闭 |
| OBJECT_STORAGE_PROVIDER | minio或oss，未知值拒绝 |
| OBJECT_STORAGE_ENDPOINT | 实际服务端连接端点；MinIO支持HTTPS，HTTP限回环或RFC1918地址 |
| OBJECT_STORAGE_REGION | MinIO可选区域，缺省由SDK查询；新实例us-east-1 |
| OBJECT_STORAGE_PHOTO_BUCKET / IMPORT_BUCKET | 照片/来源的项目桶，可分别配置 |
| OBJECT_STORAGE_PHOTO_ACCESS_KEY / SECRET_KEY | 照片前缀受限凭据 |
| OBJECT_STORAGE_IMPORT_ACCESS_KEY / SECRET_KEY | 来源前缀受限凭据 |
| OBJECT_STORAGE_BUCKET / ACCESS_KEY / SECRET_KEY | 明确选择共用配置时使用的通用值 |

MinIO字段优先级为显式能力专属值、通用值、历史MOP_S3或MOP_IMPORT_S3值；字段一旦存在但为空，不继续找备用值。端点中的userinfo、查询、片段、路径、非法端口、公开HTTP和非法桶名拒绝。账号/secret、端点和桶均不出现在配置对象toString或错误原因中。新增凭据键同样禁止进入业务配置提案、审计正文或公共文档。

每个Worker仅读取自己能力的账号，不能以照片凭据替代导入凭据。明确选择OSS时只创建原生OSS适配器，并沿用已有批准范围校验；OSS配置缺失、错误或连接失败不会改用已有MinIO凭据。完全不存在新OBJECT_STORAGE配置时，历史模式继续兼容原回环桶，作为已有环境的接续路径。

采用后端私有配置、重启生效的方式。修改同一配置版本中的API、照片Worker及导入Worker三个env，先检查目标桶/IAM、读取权限和历史数据，再重建这三个本项目容器；原有数据卷不删除。切换提供方、端点或桶不会搬迁历史对象，目标没有原字节时返回存储不可用/完整性失败，不伪造下载成功。存在未完成作业时应先保持原目标处理完，再切换；目前未实现跨提供方数据迁移或在线混合读取。

现有照片上传和授权原图下载、来源文件存储与读取均使用上述提供方。导出准备Worker仍只生成/保留XLSX；本次提供方切换不自动实现导出发布、OSS授权下载或通知。MinIO测试模式已获用户选择，正式客户生产存储和其他业务规则门禁单独保留。

## 验证边界

隔离JUnit覆盖可配置端点/区域/两个桶、能力独立凭据、字段优先级、空值拒绝、私有HTTP边界、非法/关闭/其他provider不回退、API与Worker接入及凭据提案阻断。真实组件验证只连接192.168.1.215上的MinIO，通过SSH映射至21900，使用两个明确标记的独立测试桶及受限账号；原默认两个桶和其IAM不改。两个组件用例实际创建、原字节回读、原请求重放、不同内容拒绝和原对象保留通过，合成对象留存，不冒充云端OSS或完整业务HTTP验收。

最终证据：

- [配置和组件测试](evidence/configurable-storage-tests-20261008.json)：48项相关JUnit、2项实际MinIO和79个纯JDK断言通过，各自单列。
- [部署与切换验证](evidence/configurable-minio-deployment-20261008.json)：隔离候选API在真实PG/MinIO上验证正常minio200、空MinIO秘密503、不完整OSS503、关闭503、恢复MinIO200。候选进程故意保留有效旧MinIO参数，所有失败均未回退。503的就绪报告在照片失败处提前返回，未报告的导入状态保持null，不推断其状态。
- [实际上传与授权下载](evidence/configurable-minio-http-20261008.json)：36次HTTP通过，照片上传/原202重放/原图大小与摘要、来源存储/真实CSV解析，以及配置变更前的原图仍可授权下载；跨租户404，0未确认写请求。没有重做联系人或导出历史场景。
- [最终状态](evidence/configured-minio-final-20261008.json)：关闭本轮本机验证转发后，12个容器运行、API及两个Worker的provider均为minio，ready/durable为true。OSS真实云端凭据和业务联调仍NOT_RUN。
