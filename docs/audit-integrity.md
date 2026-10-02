# 审计链只读验证

对应M2-12、通用M7-5，当前阶段为算法、授权只读仓储与真实PG验证；公开区间校验/检索接口已接线并有61项真实HTTP证据，页面代码/状态和真实客户端已验证，浏览器DOM待，不能标完整合同验收DONE。

## 保持已存编码不变

V1既有规则是UTF-8 SHA-256(previous_hash + 顶层key排序后的JSON)，JSON排除entry_hash，保留Unicode、null、转义和数值。没有重新签名历史、修改写入器、迁移或重建链。每批最多1000条，输入按sequence升序；非起始区间带上一条本地记录作前驱校验。核对namespace/tenant/id/sequence/hash列与payload一致、缺失/乱序/重复ID、前后hash链接、完整字段和实际摘要。

返回只含范围、已验证数、固定失败枚举/序号、起止摘要和externallyAnchored=false，不附原始内容。VALID只表示所给区间与本地前驱在该次快照中一致：没有外部可信时间戳、签名或WORM锚，无法仅靠本库hash链证明具有全部重写权限的人未重算整链，也不证明未来记录未变化。

## 已执行验证

- 8项隔离计算测试通过：完整/部分区间、原写入器编码兼容、Unicode/null/转义、改内容/链接、缺失/乱序/多余、跨范围/列不一致/重复ID、无效前驱、严格类型和防御拷贝
- 2项真实PG只读测试通过：两租户既存5162条记录分7个有界区间，在各自REPEATABLE READ快照验证；另对真实payload的内存副本改一字段，hash校验拒绝，回读证明数据库原记录未改
- 不是向真实表注入篡改，不改变审计记录、角色、权限、schema或秘密；总记录数是此次快照数，后续正常审计追加会增加
- 根495=482执行通过（含104真实PG）+13历史fixture跳过，6MinIO排除，0失败错误

真实OSS、原服务器、浏览器UI和客户留存策略仍按现有阻塞保留。下一步接当前padmin授权的只读区间工具与有界检索，不能让客户端选择其他租户或平台namespace。

## 2026-10-02 审计只读仓储与稳定分页

AuditReader/PostgresAuditReader在REPEATABLE READ内重新核验当前启用padmin成员与permission_version；仅会话当前租户，不推定平台级或其他租户权限。按sequence倒序最多100条，第一页固定snapshot_sequence，后续before_sequence只读取该上界以前；并发正常追加不挤入旧页。actor/action/result/resource_type/resource_id/trace_id均参数化精确匹配，发生时间为[开始,结束)；字段和精度有技术边界，非任意SQL查询。

每条列表记录核对真实行/JSON envelope与本地hash；损坏/不兼容或断库503，不返回伪造正常。区间验证最多1000条、明确EMPTY/VALID/INVALID，读取本地前驱并保留失败枚举；EMPTY的valid=null，未来区间404。只查询原V1表，不提供修改/删除/修复，也不把本库链声明为外部可信锚。

7项新增真实PG回归通过：12条精确筛选记录的10+2分页、并发追加后旧快照稳定、新首页可见第13条、全筛选/半开时间、SQL注入文本无效、角色/权限版本/租户范围、区间及无写证明、默认有界/未来区间拒绝、参数边界；合成设置记录通过既有audit writer追加，读方法本身不写。根502=489执行通过（含111真实PG）+13fixture跳过，6MinIO排除，0失败错误。断库列表/校验均503守卫通过。

公开API/页面仍待接线；不宣称M2-12/M7-5完成，V1–V8和私有配置不改。上一2a58fbc的CI37035240717已回读success。见[仓储证据](evidence/niko-audit-reader-20261002.json)。

## 2026-10-02 审计只读API V0.12

新增GET /audit/records与GET /audit/integrity，仅当前启用padmin的audit:read，不接受tenant/namespace参数。records支持8类精确/半开时间筛选和固定sequence上界keyset；列表单条hash校验不能当整链校验。integrity区间含首尾，最多1000条，省略结束取min(快照尾,开始+999)；EMPTY返回valid=null，INVALID是200的失败校验结果，未来区间404。whole_snapshot_chain_checked只表示本次快照从1到尾完整且VALID；externally_anchored始终false，无外部签名/WORM证明。

7项新增controller隔离测试、契约24、Web225及类型/构建通过；根509=496执行通过（含111真实PG）+13fixture跳过，6MinIO排除，0失败错误。61项真实PG/OIDC HTTP/Ajv检查通过：12条筛选记录10+2稳定分页、正常追加后新首页13条、全精确筛选/半开时间、注入文本不改变谓词、区间与默认1000条、成功GET不追加审计、各非管理员/匿名拒绝、非法/重复/伪造scope和未来区间拒绝、无修改/删除入口405、denied可追溯。

没有对真实表进行篡改；INVALID只在隔离与真实行内存副本覆盖，不能称为真实存储攻击测试。V1–V8、原私有配置、角色能力未改，原/audit兼容入口保留近期100条，不当完整检索结果。本API阶段新页面仍待，后续见页面章节；M2-12/M7-5整体非DONE。上一4823b9a的CI37036201421已回读success。见[HTTP证据](evidence/niko-audit-api-http-20261002.json)、[本地回归](evidence/dot-audit-api-20261002.json)。

### 请求边界

records允许page_size（10/15/25/50/100，默认15）、snapshot_sequence、before_sequence、actor_id、action、result、resource_type、resource_id、trace_id、occurred_from、occurred_before。before_sequence必须同时提供snapshot_sequence；筛选或身份上下文改变应清空两个分页边界，新追加日志须刷新第一页查看。时间必须显式秒/时区且最多毫秒精度，范围[开始,结束)；未提供时间意味着不另设时间筛选，不代表超过已存留存。

integrity仅接受from_sequence/to_sequence，默认from=1；每次独立读快照，自动补全/循环验证不由本接口执行。返回checked_at、snapshot_sequence、范围、验证数、失败序号/固定枚举和本地前驱/末尾hash，不包含该区间原始payload。不提供修复、重签名、删除或留存变更。

## 2026-10-02 审计检索与区间校验页面

AuditPage切换到实际有界API，新增操作者/动作/结果/对象/trace精确筛选、UTC半开时间、应用筛选与固定序号上界分页；未应用输入不混入下一页。记录摘要只展示既存hash，并明确选择区间，查看不自动发起校验。区间面板独立显示当次快照、范围、前后hash、失败枚举及是否覆盖该快照整链；最多1000条/批，下一批需明确点击，不能将多次独立快照累计冒充整链证明。

严格客户端校验租户/排序/唯一性/分页边界/EMPTY-null/INVALID/外部锚为false；失败或非法输入清除旧结果，查询与校验各自取消迟到响应，租户/身份/权限改变清空两区。无修改/删除/修复入口，不改变保留策略。

14项新增状态/DTO/输入回归通过，全Web239、契约24、类型/构建通过。首轮模板dialog回调参数隐式any导致类型检查失败，改为具名boolean处理函数后通过。实际auditWorkspace+ApiClient模块连接真实PG/OIDC执行8次GET：20条两页稳定回读、trace筛选、单条校验、先1000再明确下一1000且不累计、最后序号上界不变；没有业务写请求或读取追加审计。另上一API的61项HTTP包括58项契约响应检查和3项不支持写方法405检查，不将不存在的写端点称为schema通过。

API c7294a1的CI37037860376已回读success。当前是页面代码/状态与真实客户端HTTP证据，浏览器DOM/390px仍NOT_RUN_B03，M2-12/M7-5整体非DONE。V1–V8、API V0.12、角色和私有配置不改。见[前端回归](evidence/dot-audit-ui-20261002.json)、[实际客户端HTTP](evidence/niko-audit-client-http-20261002.json)。

### 实际前端模块复现

安装已锁定Web依赖后执行node tools/build-audit-client-test.mjs；在已批准组件所在QA会话中显式运行node tests/niko-audit-client-http.mjs。脚本仅使用当前私有padmin会话，全部GET，不生成账号或组件；拒绝覆盖已有证据。生成bundle在忽略的.local/niko-test，不包含凭据。该步骤不运行浏览器，不能替代控件与视觉验收。
