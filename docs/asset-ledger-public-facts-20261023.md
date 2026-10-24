# A05 资产登记资料批量读取

本轮从已审阅WIP `3b219461`逐文件恢复公开资料读取，不整体覆盖主干。规范仓库`yangyour/zhonganzaixian`，独立检出`E:\work\wxw9\zhonganzaixian\.parallel\local-astra-ut-20261023`，分支`codex/local-astra-ut-a05-20261023`，起点`d53697df`。用户要求再次核对最新推送后，已fetch全部60个远端引用，六份WIP均未更新，未发现重复实现；见[去重记录](evidence/local-astra-ut-20261023/remote-dedup-check.json)。

## 功能与API

`GET /api/v1/assets/ledger-facts?ids=<uuid>,<uuid>`只接受单个`ids`参数和1–100个不重复规范UUID。要求当前`assets:read`；任一不存在或跨租户资产整批404，不释放部分行。通过既有指定数据库的一次只读`REPEATABLE_READ`事务批量返回站点名称、手工登记负责人/活动状态、业务说明及对应资产/资料版本。

公开字段固定为`asset_id/asset_version/site_id/site_name/metadata_version/metadata_recorded_at/metadata_source/business_description/owner`，不返回内部备注、完整修订快照、价格、联系方式或凭据。未登记保持`NOT_RECORDED`与null。业务说明仍为`MANUAL_DECLARATION`，`business_relationship_verified=false`、`proxy_verified=false`，不宣称权威关联或监控已接通。

在原草稿基础上修复三处边界：

1. 读取结果返回前重新核对会话租户、完整当前成员、权限版本、角色、能力及账号守卫；读取期间撤权不释放旧范围资料。
2. 同批量SQL取出修订和资产的名称、类型、站点、管理地址，并逐项核对。任一核心漂移整批503，原因`MANUAL_METADATA_CORE_DRIFT`；不把旧资料贴到变化后的资产。
3. 资料记录时间不能晚于快照观察时间；服务端与前端保留精确时刻，前端按纳秒比较，任一异常整批拒绝。

资产列表与详情用同一公开读取器，分别绑定所读资产版本；陈旧资料隐藏并提示刷新。切租户、成员/账号版本、能力/角色/mode变化和取消清除旧值并取消在途请求。重试有焦点恢复。新资料失败时基本资产仍可读；未知CPU、内存、Proxy和问题数保持未知。仅修正该页类型占位及表格键盘提示的低对比度，不改全局样式或菜单。

## 验证和运行范围

- Java定向51项及启动Profile策略5项，合计56项通过，0失败/跳过。
- 指定LAN PG17.9/V50的4项真实检查通过：来源白名单/未登记、整批404与旧权限、真实隔离资产核心漂移拒绝/恢复、并发修订仍读取同快照。首次准备调用资产创建接口因缺已发布配额被现有守卫正确拒绝；改用显式新测试命名空间中的第二个预置资产后重新执行全部断言，未修改配额逻辑、不宣称资产创建API通过。首次失败证据保留。
- 前端全量316文件5265项通过；根契约209项、离线DEMO契约4项通过。既有搜索测试隔离新增独立资料请求，未删主列表筛选/请求次数断言。
- 实际Edge156的5项合成桌面测试通过，1024/1440布局、键盘、axe、详情回读、陈旧/未来资料、取消焦点和撤权分别验证。首轮3过2失败为两处低对比度，局部修复后5项通过，未屏蔽规则。
- 真实候选API和Edge对三个已有隔离DEMO角色各7台资产核对批量资料、逐资产详情和列表返回；版本、站点及公开字段匹配。无业务写入、外发、DEMO初始化、迁移或部署，候选进程和转发已关闭。

实际租户相关表（含手工资料头/修订）指纹与迁移历史保持，测试主体已停用，不删除不可变隔离证据。[分层验证](evidence/local-astra-ut-20261023/a05/validation.json)、[PG](evidence/local-astra-ut-20261023/a05/postgres.json)、[HTTP](evidence/local-astra-ut-20261023/a05/http.json)、[真实浏览器](evidence/local-astra-ut-20261023/a05/live-browser.json)。

```text
py -3 tools/verify-report-capture-isolated.py --suite asset-ledger-facts --output .local/<unique-run>
py -3 tools/verify-dashboard-sla-candidate.py --ledger-facts --port 18217 --output .local/<unique-run> --hold
node tools/verify-dashboard-actionable-browser.mjs --assets
npm --prefix apps/web run test:browser -- qa/browser/asset-ledger-facts.pw.ts
```

A05整组仍保留100行真实批次、完整历史异常目录、冻结导出文件与当前显示引用的一致性验收，以及权威业务/Proxy/监控来源。导出使用站点/负责人ID，页面显示名称，两者不按字面相等冒充一致；本次没有修改导出实现或核销正式合同TC。
