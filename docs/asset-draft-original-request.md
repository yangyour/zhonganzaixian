# 资产创建原请求恢复

## 发现的缺口

AssetsPage旧createAsset直接调用ApiClient而不提供固定Idempotency-Key，重试按钮会生成新key；它还借用列表查询AbortController，列表刷新可能中断正在等待的写结果。源码可确认这些路径；没有宣称已在新环境实际制造未确认资产。

后端已有同租户名称唯一约束，因此相同名称重发不一定创建第二条，而可能变成ASSET_NAME_CONFLICT；这个409也不能证明第一次未知请求未提交。若用户修改名称重发则是不同意图。必须保留原请求而不是用新查询/新key推断旧结果。

## 新生产写状态机

assetDraftWriter在首次派发前冻结POST /assets、完整序列化四字段及原key。请求使用独立AbortController，不借用列表读取控制器；重复提交期间不产生第二个key，原结果不明确时禁止新意图。重试完整原path/method/body/key；未知原请求的后续401/403/404/409继续unknown，不能降级为“原写入失败”。首次明确拒绝可在用户明确begin后编辑重提。

成功收据必须同租户、完整原name/type/IP/site、version=1、manual/DRAFT、unknown/UNKNOWN/PENDING_ONBOARDING、sampled_at=null，身份及时间格式有界，未采样原因非空；多余/缺失/被修改字段不能算成功。created_at与updated_at独立检查，允许初次插入两个默认时钟相差毫秒，不强制相等。独立GET不是原写请求收据，本模块不自动GET。

四字段按既有Java String.strip规则规范，不把NBSP偷偷按JS trim删掉；名称/站点按现有UTF-16长度界限，控制符/非法代理拒绝。IP仅验证IPv4/IPv6字面量，URL构造仅作本地IPv6语法解析，未访问地址、未查DNS，也不改写IP文本。角色/能力/PV/租户上下文与通知回调重入均防止旧表单转向新范围；快照独立拷贝。强制授权变更或卸载会清除内存状态，尚无跨刷新/崩溃的持久请求恢复证明。

## 当前范围

本次只完成生产写模块及隔离测试，旧页面尚待接线；不能把模块提交视为用户按钮已经修复。当前API0.44、DB约束和写接口不变，没有执行POST、生成组件凭据或启动数据库。实际PG/OIDC/HTTP/浏览器和崩溃恢复均NOT_RUN。后续页面须锁定未确认请求、明确重试、保护关闭/路由/租户切换，再通过真实环境独立验证。

22新增隔离测试、1640Web/96文件、类型构建、125契约通过。见[写模块验证](evidence/niko-asset-draft-writer-20261005.json)。
