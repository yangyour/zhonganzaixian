# 资产导入原始源测试存储

2026-10-02 已复用指定服务器现有 MinIO，为资产导入新增独立项目命名空间 `mop-test-asset-imports`。没有安装、升级、停止或重配服务，没有开放公网组件端口，也没有更改照片桶及其账号。

运行账号仅有本桶的 GetBucketLocation/ListBucket，以及 `asset-import-sources/*` 下的 GetObject/PutObject。真实验证了写入及读回、删除拒绝、其他桶不可见、前缀外写入拒绝和匿名访问拒绝；准备脚本重复执行时核对私有所有权 UUID、桶标签和精确策略，不接管已有未知命名空间或扩大已有账号权限。

凭据和恢复状态只保存在 Git 忽略的 `deploy/private/asset-import-namespace.json`。运行配置引用为 `MOP_IMPORT_S3_ENDPOINT`、`MOP_IMPORT_S3_BUCKET`、`MOP_IMPORT_S3_ACCESS_KEY`、`MOP_IMPORT_S3_SECRET_KEY`；原照片的 `MOP_S3_*` 不变。仅通过本机 SSH 转发访问指定组件。

从工程根目录执行：

```powershell
pwsh -File deploy/prepare-asset-import-environment.ps1
```

脚本首次只准备本项目桶、受限账号与策略，后续重复核验已有所有权和权限。探测输入明确为虚构组件测试数据，不存真实资产或客户秘密。[公开证据](evidence/asset-import-component-verification.json)不含凭据。

此记录只证明存储命名空间与 IAM 能力。应用的大文件单次条件写、原始摘要保护、V6 数据库结构、原始源 Worker、CSV/XLSX 解析和映射均须独立联调，当前为 `NOT_YET_VERIFIED`。发现组件不能算完成导入；最终入库、配额、客户字典/IP/日期规则和完整 M3-6 验收仍未完成。扫描未配置、生产批准未核验、保留期限为 null，不擅自执行清理或生命周期规则。
