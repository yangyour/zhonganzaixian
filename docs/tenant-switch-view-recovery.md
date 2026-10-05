# 租户切换后视图清理与原结果边界

## 原路径缺口

原App只在switchTenant正常返回true后清除旧asset_id/ticket_id/operation_id/cursor/site_id。若切换POST响应丢失，session store通过GET回读到另一当前租户后重新抛出原异常，App的清理分支不会执行；新租户页面可能继续携带旧范围选择。

本补丁在恢复视图前调用显式清理hook，成功响应与未知POST后的独立回读都以实际观察到的租户为准。清理期间switchingTenant和contextActionPending保持，旧页面不提前恢复，第二个切换或退出不会并行发起。原切换失败仍原样传播；GET当前会话不是原POST收据，也不证明旧命令绝不会晚到。

## 当前行为

- 先通过现有未保存/未知写入guard，才锁定页面及失效旧请求；guard拒绝不卸载旧编辑器
- POST成功响应必须匹配明确目标租户。HTTP200但目标不符视为INVALID_TENANT_SWITCH_RESPONSE，随后独立回读当前范围；不能把另一个租户当原目标切换成功
- POST失败后若当前会话回读到新范围，清除旧选择，再显示原错误；若仍旧范围则保留原合法选择，仍不判断原POST是否已提交。回读失败清空会话
- App等待router.replace和nextTick，使路由watch先完成，避免原切换错误刚显示就被路由watch清空。Vue Router返回的导航失败也检查；仅重复导航可视为无需再移动，取消/阻止不是清理成功
- 清理失败包装TENANT_VIEW_SYNC_FAILED，保留原切换与导航原因供诊断；用户消息固定，不回显异常正文，原API trace_id保留。不会因导航失败重发POST或额外读取会话
- 强制会话失效不受未保存guard阻挡；清理期间失效不能返回一个仍有效的成功上下文

## 未覆盖边界

本批仅修复客户端当前视图选择与失败展示，不新增原切换请求的持久日志、服务端租户上下文CAS、跨标签页顺序控制或跨重载恢复。没有声称单次回读已排除在途旧命令，也未解决所有迟到服务器写入与请求级租户绑定问题。后续应继续独立核对这些服务端边界，不能把本补丁称为完整跨租户/并发验收。

当前只完成隔离session store测试及App源码接线检查；没有新PG/OIDC/实际HTTP、真实Vue Router浏览器导航、CSS/手机验证。API0.44、数据库结构、组件凭据和原服务器不变；M1-7及全部正式验收仍NOT_RUN。

14新增隔离用例、25聚焦、1674Web/97文件、类型构建、125契约通过。初次类型检查有2处合成测试被推断为never，补明确Session工厂后通过；保留失败日志。见[本批证据](evidence/niko-tenant-switch-view-recovery-20261005.json)。

并发远端部署/HTTP浏览器兼容提交已纳入合并复核，合并树1685Web、1534 Java执行/781跳过、125契约、158Python通过；原服务器V26记录只引用其独立已提交证据，不等于niko组件恢复。见[合并证据](evidence/niko-concurrent-deployment-merge-20261005.json)。
