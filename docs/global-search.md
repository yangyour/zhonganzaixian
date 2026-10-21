# 全局分组搜索边界

## 2026-10-19 API0.63 监控事件搜索切片

M1-8 / M1-D04 将既有 V37 `mop_monitoring_event` 接入 GET /api/v1/search。无新增迁移或权限授予，V1–V41 SQL 保持字节冻结；API/Worker 不执行迁移。本轮只在 dot 自有云端编译和隔离验证，不操作用户电脑或原服务器。下文“事件未接入”是历史边界。

- 搜索词保持原文、不 trim，规则名称和代码仅做 PostgreSQL 根 ICU lower + strpos 字面子串匹配；`%`、`_`、引号均不是通配或 SQL。只按当前会话租户查询，不查根因、更新者、原始载荷、指纹、IP 或隔离区数据。
- 资产/事件/报障/脚本固定 5/4/3/3。事件按 `last_occurred_at DESC, id DESC` 取 5 条哨兵，最多返回 4 条和 has_more；不返回推断总量，不局限于事件中心首页 25 条。
- 活跃 user/成员、权限版本及有效 `search:read`、`assets:read`、`tickets:read` 在同一个 REPEATABLE READ 只读事务内复核，所有分组来自同一事务快照。事件还要求 msp/padmin，与现有 GET /events/{id} 内部权限一致；不扩大角色上限或自定义角色权限。
- msp/padmin 事件组 AVAILABLE；viewer/tadmin 事件组 NOT_AUTHORIZED，reason=INTERNAL_EVENT_READ_REQUIRED，items/returned/has_more/source_scope/matched_fields 均为 null，资产与本人报障登记照常返回。
- EventHit 仅含 id、tenant_id、rule_code、rule_name、version、severity、monitor_state、handling_state、occurrence_count、last_occurred_at、resource_kind=MONITORING_EVENT。合法的恢复和处理状态保留原值，不从严重级推断健康或处置结论。
- coverage 仍为 PARTIAL；内部原因为 AUTOMATION_SOURCE_NOT_IMPLEMENTED，客户原因为 AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED。脚本及铃铛仍 NOT_IMPLEMENTED；搜索不写审计成功记录、事件、受理回执或通知，不执行自动建单或外部派发。

`fixtures/global-search-controller.json` 由真实 GlobalSearchController + MVC + 生产 remote_test 安全过滤器序列化生成，仓储输入为明确标识的合成投影；它是前后端共享线格式，不是数据库或真实采集证据。正常 Java 测试只比较、不覆盖；仅显式 `-Dmop.globalSearchFixtures=write` 更新。新增真实 PostgreSQL 测试由 `MOP_RUN_EVENT_GLOBAL_SEARCH_TESTS=true` 门控，编译或跳过不表示已运行。

离线九模块 Java 3428 项，其中执行 2449、门控跳过 979，失败/错误 0；搜索针对性 33 项含 13 项真实 MVC/过滤器检查。149 项 Node 契约及生成一致性检查通过，API 路径仅 /search 变化，新增 SearchEventHit 并更新 GroupedSearch。23 项新增真实 PG 测试已编译，尚未在此离线记录中执行。见 [离线证据](evidence/event-global-search-offline-20261019.json)。

本切片不是完整 M1-8、自动化来源、铃铛、生产授权矩阵或大规模检索性能验收。离线测试、真实 PG/HTTP 与浏览器视觉按各自实际运行证据记录，不混为完成。


## 2026-10-15 键盘与弹窗验收优化

当前入口改为 Ctrl+K / Command+K，裸 `/` 不再全局触发。组合键避开编辑区、输入法及其他模态窗口；按钮提供可见提示和 aria-keyshortcuts。复用 Element Plus 焦点机制，移除打开动画前的抢焦点，取消后恢复入口。搜索弹窗挂到 body，避免页头叠层遮挡；长结果内部滚动，标题与关闭按钮保持在视口内。

27项针对性测试、类型/生产构建/发布门禁通过。真实指定API浏览器检查快捷键、Tab/Shift+Tab、Escape回焦、已有弹窗、输入区斜杠及无匹配结果；1440×760、768×800、390×844、320×720均实测按钮可点击、弹窗边界完整。无业务写入。API、分组上限、权限及部分来源null约定不变。见[规范](frontend-acceptance-standard-20261015.md)和[搜索证据](evidence/frontend-acceptance-20261015/search.json)。下文斜杠入口是历史，不是当前行为；本轮尚未部署服务器。

## 2026-10-03 M1-8 分组搜索核心与真实仓储

按合同固定上限资产5/事件4/工单3/自动化脚本3建立查询边界。本切片只接入已存在的资产记录和customer_report耐久报障登记：资产仅名称，登记仅标题/受理编号字面匹配；不查正文、联系人/电话、内部评论、IP、凭据或其他租户作为回退。UTF-8/Unicode搜索词1–100 code points，禁止全空白/控制/不完整代理字符；%/_/引号作为字面值，不转换SQL通配。原文空格不被悄悄清洗。

PostgresGlobalSearchRepository在一个repeatable-read只读事务中核验当前启用user/成员、权限版本、assets:read/tickets:read角色能力，再按当前租户读取两组。viewer/tadmin登记额外限定本人reporter_id；msp/padmin当前租户内部记录。PG根ICU大小写匹配、created_at/id倒序，分别最多取6/4作为下一条哨兵，返回5/3及more标志；不返回推断总量。最小结果字段不含联系方式/IP/正文/内部actor。事件/脚本尚未接入，不能拿手工来源登记冒充实时事件；铃铛未确认告警条件仍未执行。

8纯规则+9新增真实PG通过；排序测试显式按created_at/id复合键而非假设插入时间互异后完整重跑，根892=879执行（273真实PG）+13历史fixture跳过/6MinIO排除，45契约通过。覆盖5/3上限溢出、客户与客户管理员各自仅本人、两租户同关键词不泄漏、Unicode/%/引号字面、受理编号搜索/正文不检索、DTO最小字段、旧权限/无成员拒绝、只读资产/审计不变和复合排序。不是RLS验收、生产授权矩阵或大规模性能证明。

当前仓储阶段，无公开搜索API/页面、真实事件/脚本源或铃铛数据，API0.23/V1–V14不变，M1-8及128项未DONE。见[仓储证据](evidence/niko-global-search-repository-20261003.json)。

## 2026-10-03 API0.24 分组搜索与部分来源标记

GET /api/v1/search只接受一次q，当前租户/操作者来自会话，不接受tenant/actor/limit/来源覆盖。search:read仅四类既有角色，仓储同一只读事务再核验该能力及资产/登记读取能力；客户与tadmin仍仅本人登记。固定顺序assets/events/tickets/automation_scripts与5/4/3/3上限，分别结构化返回最小对象类型。工单组resource_kind=CUSTOMER_REPORT_REGISTRATION，不冒充完整工单流程。

当前覆盖PARTIAL；事件和自动化脚本status=NOT_IMPLEMENTED且items/returned/has_more/source_scope/matched_fields均null，不当0或空结果。已接入组确实无匹配才items=[]、returned=0；没有全局总量。notification_bell_status=NOT_IMPLEMENTED，不从搜索数量推断未确认告警。每组严格契约限制字段/返回数/溢出；没有IP、联系人、电话、正文、内部评论/历史或权限身份字段。

8新控制器、完整根900=887执行（273真实PG）+13历史fixture跳过/6MinIO排除、47契约/520Web/类型构建通过。正常退出拥有栈后按新固定JAR启动、6新OIDC；66真实HTTP均匹配API0.24，设置8合成资产+7客户登记=15写入及2合法租户切换。5/3溢出、四身份范围、两租户同关键词隔离、客户管理员不能搜索他人受理编号、Unicode字面查询、正文不检索、100emoji接受/101拒绝、额外/重复q拒绝、只读审计尾不变，事件/脚本/铃铛始终未实现。

无迁移，V1–V14冻结；公开API切片不是完整M1-8/全局搜索或通知集成验收。页面、/键与DOM、事件/脚本提供方和真实铃铛仍待，规模未验，全部128项非DONE。见[真实HTTP](evidence/niko-global-search-api-http-20261003.json)、[回归证据](evidence/niko-global-search-api-20261003.json)。

## 2026-10-03 全局搜索入口、分组弹窗与实际前端模块

顶栏新增搜索按钮与/键入口。仅非输入框/文本域/select/contenteditable、非IME/已处理/重复/修饰快捷键时聚焦搜索；弹窗关键词编辑立即取消旧请求并清除结果，关闭/路由切换/租户或权限变化清空查询和数据，卸载移除监听。明确提交才GET，不自动搜全部。分组固定5/4/3/3，资产记录和报障登记可打开类型限定的本平台详情；客户明确只本人。不存在或无权限仅用于已接入组；事件/脚本显示来源未接入、未知，铃铛仍未实现。

读取模块严格校验query/租户/角色范围/4组顺序/上限/计数/溢出/最小字段，拒绝额外IP/联系人/正文与对象伪装类型，未接入不能冒充空列表。双击抑制、网络失败清旧结果、迟到输入/关闭/权限响应不恢复旧数据。导航取消或失败保留搜索结果；已在目标页面的重复导航正常关闭。等待导航确认时暂不允许关闭弹窗，防止用户取消搜索后旧跳转继续，真实路由守卫仍由原页面执行。

20新增Web共540、类型构建和47契约通过。实际globalSearchWorkspace/ApiClient/类型目标对真实PG/OIDC/API36HTTP，模块9GET/0写入；设置6合成资产+4登记=10写入。5/3溢出、事件/脚本null、客户本人范围、正文不检索、编辑/关闭/权限后的3个迟到GET丢弃、重复提交、丢弃1成功GET清旧结果、只读审计不变，以及真实资产/本人工单详情GET与他人登记404均已验。这是受控响应延迟/丢弃，不是物理断网或进程重启。

按钮、/键、导航取消和手机布局仅源码/单测/类型验证，未运行实际DOM/截图，B03不解除。事件/自动化脚本源、真实铃铛仍缺；M1-8和全部128项非DONE。API0.24与V1–V14不变。见[真实模块HTTP](evidence/niko-global-search-client-http-20261003.json)、[Web证据](evidence/niko-global-search-ui-20261003.json)。
