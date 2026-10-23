# B01：备份版本列表独立授权提供者

2026-10-23 接续 `9cdccdc8` 总清单。此项补齐原有版本列表 API 和资产详情面板的运行提供者，不把 B01 整项标为 VERIFIED。

## 实际调用链

资产详情已有 `BackupVersionInventoryPanel` → `GET /api/v1/assets/{assetId}/backup-versions` → `BackupVersionInventoryConfiguration` → 当前私有授权文件 → 独立枚举/逐版本授权 → V49 PostgreSQL 元数据查询。现有页面取消、原页重试、分页、切租户清空结果及错误提示保持；没有另建模拟页面或复制版本数据。

`remote_test` 显式装配查询 Bean，已有外部显式 Bean 可保留。未设置下述配置时，调用返回 503 `BACKUP_VERSION_INVENTORY_NOT_CONFIGURED`，不会把未装配表示为零条备份。非法/不可读配置或数据库故障返回脱敏 503；身份、设备范围和权限版本仍受原 API/仓储检查。

## 受控运行配置

- `MOP_BACKUP_ACCESS_GRANTS_FILE`：绝对路径，指向受控私有 JSON 常规文件，不接受符号链接、目录或相对路径。每次授权重新读，最大 256 KiB；应由维护者原子替换，半写入文件会拒绝访问。
- `MOP_BACKUP_VERSION_INVENTORY_BINDING`：明确的 `backup-task-binding:<标识>`，必须与授权项一致。没有默认身份、默认设备或管理员旁路。
- JSON 根对象严格为 `schema_version: 1` 和 `grants` 数组。每项严格包含 `binding_reference`、`tenant_id`、`asset_id`、`actor_id`、`permission_version`、`expires_at`、`operations`。UUID 为规范小写；权限版本为正整数；有效期为 Instant。拒绝未知/重复字段、重复身份范围、重复操作和未知操作。
- 列表需要 `VERSION_INVENTORY`，实际返回的每一版本另外需要 `VERSION_READ`。`DISCOVER`、归档、版本登记和下载权限均不能替代枚举权限。授权项还必须匹配数据库当前 active 用户/成员与权限版本。
- 按 M6-D07，当前成员基础角色必须为内部 padmin/msp；客户 tadmin/viewer 即使文件误配允许项也拒绝版本枚举/正文相关任务。内部角色本身仍不授予独立备份权限。
- 空授权列表表示当前无人获准，返回 403；文件撤除表示提供者未配置，返回 503；到期或撤销不使用之前缓存的允许结果。文件不接受口令、URL、命令、角色通配或其他配置字段。

没有替客户写入任何实际授权值或部署环境变量。测试授权文件只在忽略/临时目录中，测试结束清理。只查询历史元数据，不下载密文、访问 KMS、授权恢复或声称可恢复。

## 验证与剩余

独立文件解析、授权、控制器及真实 MVC 安全链回归；指定 PostgreSQL 17.9 另执行实际已配置提供者读取、枚举权限独立拒绝、当前数据库权限版本拒绝、跨租户404、文件撤销和缺失503。没有新建账号或业务记录，没有复用旧Fixture身份。现有面板/客户端94项测试通过。实际配置文件被测试删除后，默认运行继续未配置；未部署或启用任何备份。

本次实际环境存在的查询可为空，不补造版本，非空生产数据、实际页面连接新部署提供者和正式TC不由此标记通过。B01其余 Worker入口、受保护下载/解密/差异，B02失败工单/恢复/演练/清理/变更联动继续推进。
