# 资产依赖草稿API（V0.9加入，当前契约V0.10）

当前已接入真实PG事务仓储；只追加人工提出的依赖草稿，不发布监控抑制、不执行监控写入、不推断Q20真实影响范围。页面代码已接入资产详情的独立依赖抽屉，浏览器验收保持未执行。

## 接口与权限

- GET `/api/v1/assets/{id}/dependencies`：当前会话具有`assets:read`且路径资产在本租户可见；返回同一租户完整图，而非仅该资产过滤后的子图。未知/跨租户路径404，拒绝任何查询参数。
- POST 同一路径：`assets:write`（当前padmin/msp/tadmin），必须CSRF与Idempotency-Key。同步事务成功200，只代表草稿持久化，不能读作监控生效。只接受expected_version、additions；版本是整个租户图的版本，不是资产版本。
- 每条新增边必须连接路径资产，字段仅parent_id、child_id、proposed_type、proposed_suppression_scope、proposed_enabled；三个proposed字段必填且可为null，不补客户默认规则。parent上游指向child下游；不接受客户端tenant/actor/effective字段。
- 事务内重新核验当前成员及全部引用、租户锁、完整图防环/CAS；不同请求并发只能一个使用同版本成功。重复边409而不覆盖；同键同体回放，不重复审计。跨租户/未知边404，旧版本409，环/自指400；缺schema/连接503。所有写入保留历史与审计。

## 明确技术边界

每批最多100条、完整图最多10000条；超限拒绝，不截断存储、不把它标为客户配额。请求JSON<=131072 bytes、2048 tokens、4层；重复键、尾随值、未知字段拒绝。类型最多64、抑制范围128个Unicode code point，空白/控制字符/不完整Unicode拒绝。expected_version范围0..9007199254740990，避免浏览器整数精度丢失；未有图时版本0、updated_at=null、空边集合，只读不创建记录。

响应显式version_kind=tenant_dependency_graph、complete=true、source=postgresql、lifecycle_status=DRAFT、decision_status=pending、configuration_status=CONFIG_PENDING、effective_rules=null、monitoring_applied=false和Q20。建议enabled=true也不是监控启用。客户端不能将建议图当作实际影响范围或已生效规则。

## 就绪与迁移

`/health/ready`新增asset_dependencies：只读检查V7列可访问和运行权限边界；缺schema/访问或历史写权限扩张时503。成功仅schema_access_ready=true，有效规则仍null、监控未应用。探测不迁移、不造图、不修改权限；其他不依赖此模块的API仍可调用。完整结构/FK/ACL核验由独立迁移CLI执行，不能用健康检查替代它。

V7在niko独立执行1、重复0、check0，字节已冻结；原服务器没有执行此迁移。API与Worker启动不迁移，V1–V7均保持原字节。

## 当前验证

新增7项控制器隔离检查及2项主就绪守卫；根Maven440项=427执行通过（含84真实PG）+13历史fixture跳过，6MinIO明确排除。契约20项、Web184项及类型/构建通过。首次契约回归仅因旧断言固定0.8版本失败，按新增端点的实际0.9更新后全部通过。

真实HTTP首次46项通过；补就绪探测后最终47项通过，包含真实OIDC/PG、受理/回读、同键回放与冲突、跨租户/CSRF/角色、严格JSON、审计、两个HTTP并发相反边200+409及重试拒环。两轮重叠，不累计为93个独立验收。见[首次HTTP](evidence/niko-asset-dependency-http-20261002.json)、[最终HTTP](evidence/niko-asset-dependency-api-final-http-20261002.json)。没有OSS操作或浏览器UI证据，监控发布及TC-M3-9仍待。

## 页面与请求保留（代码/模块验证）

入口为资产详情“查看或追加依赖草稿”；抽屉收起不卸载状态，未确认请求保留在当前资产页面。普通路由离开/浏览器关闭会提示；租户或权限变化必须清空旧上下文，不能以保留重试为由暴露跨租户数据。选择设备只接受当前查询返回的同租户对象，前100条被明确标记为不完整选项列表；保存始终使用独立回读的完整图版本。

响应严校验包括租户/路径资产、版本/时间、完整标记、nullable未生效事实、边唯一/无环；确认回执还必须恰好等于原图加本次请求。200响应不能通过校验时仍保留为未知写结果，不虚报保存。16项新隔离测试、实际模块只读4次HTTP及受控丢响应重试9次HTTP通过；前端200项及构建通过。真实DOM/移动视觉因B03未验收。

模块联调先用已安装的esbuild将`apps/web/src/utils/assetDependencyWorkspace.ts`以Node ESM打包到`.local/niko-test/dependency-client.mjs`，再在组件所在shell执行`tests/niko-dependency-client-readback.mjs`或`tests/niko-dependency-client-retry.mjs`。会话只读私有文件，证据拒绝覆盖同名历史文件；重试测试创建明确虚构资产/草稿并保留审计，不自动清理数据。
