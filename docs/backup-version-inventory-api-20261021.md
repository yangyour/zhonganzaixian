# 备份版本只读 HTTP 边界与显式授权装配

基线 main fdca401454ebc9852cb6454cc5a0fd310754271c。承接[已登记版本分页仓储](backup-version-inventory-20261021.md)，新增GET /api/v1/assets/{assetId}/backup-versions，OpenAPI提升到0.77.0。未修改任何V1–V50 SQL或角色权限上限，未开启下载、解密、设备执行或归档。

## 真实实现与默认状态

BackupVersionInventoryController从当前会话固定actor/tenant/permission_version，并前后重读成员事实。assets:read只检查能否查看资产，绝不等同获准枚举备份；独立设备枚举及逐版本READ仍由仓储事务内的两类授权回调执行，包括lookahead和空列表前后核查。

BackupVersionInventoryQuery没有默认Bean，也没有管理员或草稿权限绕过。未显式装配返回503 BACKUP_VERSION_INVENTORY_NOT_CONFIGURED，而非空列表成功。postgres工厂必须同时提供真实DataSource、逐版本授权及设备枚举授权；这些授权仍须实现当前资产范围、权限变化与撤权锁协议，不能用演示lambda作为生产策略。

新增PostgresBackupVersionRepository.readOnly工厂，让只读服务不必配置不相关的对象写入/完成证明。该实例的register在任何参数处理、连接、授权或外部证明之前直接拒绝；没有“默认成功”的写证明。原登记构造方式不变。

例子新增backup_version_inventory_binding_reference=null，独立授权引用仍null，执行开关false。例子不是运行配置，填值不会自动生成Bean或授权。当前完整业务仍缺真实策略适配/部署绑定；不是全模块已可生产使用。

## 查询及输出

- 仅GET；页大小1–50，默认15为技术分页值。before_registered_at与before_version_id必须成对，时间严格毫秒UTC，拒绝24点、闰秒、无效日历日期、超精度及隐式时区转换。
- 身份/租户/下载等未知参数、重复参数和超长查询拒绝。游标绑定当前tenant与path asset。
- 整页验证资产归属、唯一版本ID、registered_at/version_id降序（UUID以规范文本的无符号字节顺序比较）、原边界、尝试序号、完成版本、密文摘要/大小及时间关系。
- has_more要求满请求页且下一游标等于末项；无更多时游标必须null。total_count始终null，页面不拼成固定时点全量。
- 返回历史登记/父运行完成关联摘要；object_observed_at是原对象观察时间，不是创建时间。当前对象健康、下载、设备执行、保留和恢复验证全部false。
- 不返回KMS引用、对象位置、注册操作者、内部证明/trace、租约owner或下载URL。私有异常统一净化，授权失败不返回部分页面；成功无业务写入，失败沿用全站拒绝审计。

## 验证分层

新增3项只读仓储边界、27项控制器/Bean装配边界、8项真实MVC安全过滤链、1项显式门禁的回环HTTP测试。最后一项实际启动127.0.0.1临时Spring服务、发出HTTP请求并停止，依次验证401、合成登录200、未配置503、显式合成授权200、非法参数400、独立拒绝403。身份、权限适配和库存都是合成替身，不连接数据库、对象存储或供应商；不把它当真实PG/业务授权验收。

5项新增契约检查及1项依赖模板检查；初次测试有Java复合var声明、AJV测试URN缺命名空间、旧契约版本断言未更新三类失败，修正并保留记录。实际浏览器97/11仍仅发现NOT_RUN，128正式TC仍NOT_RUN。

[验证记录](evidence/backup-version-inventory-api-20261021/validation.json)、[实际回环HTTP记录](evidence/backup-version-inventory-api-20261021/http-smoke.json)。普通verify默认跳过该回环用例；仅在MOP_BACKUP_VERSION_INVENTORY_HTTP_TEST_ENVIRONMENT=dot-backup-version-inventory-disposable-http时运行。测试类和合成登录入口不进入生产JAR。

## 后续

下一批接到既有资产详情的只读面板，明确未配置/拒绝/空/失败/取消/分页状态，不能新增看似已授权的下载按钮。真实独立权限适配、撤权事务协议、数据规模、存储健康、保留/恢复演练、执行Worker与数据库联调仍分别待完成。
