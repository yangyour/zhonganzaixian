# 实填工时记录的实施边界

来源：M5-D10、TC-M5-21、TC-M10-13及Q23。Q23明确允许先实现实际工时记录，同时禁止缺工时自动按0、使用演示成本/默认值形成经营结论。此模块不修改工单状态、SLA截止或客户确认规则。

## 已实现的纯输入核心

`ManualWorklog`只接受明确REMOTE/ONSITE/INSPECTION/COMMUTE类型、显式起止Instant和可为null的备注。结束不得早于开始；明确相同起止是操作者实际填入的0，与未填写起止不同。缺失时间拒绝，不悄悄采用4小时或其他默认值。

外部时间必须含秒及显式Z/offset，最多毫秒精度；规范化后UTC范围0001..9999以匹配协议和存储表示。更细精度拒绝而非静默截断。elapsedMillis仅物理经过时间，跨午夜、月界和夏令时offset不按墙钟差误算；没有按15分钟计费取整、工作日历/SLA折算或0–24小时演示限制。49小时等明确输入可以计算。备注保留原文，2000 Unicode字符上限仅技术防护，拒绝控制字符/不完整Unicode；允许换行/制表，空白不是自动说明。

6项隔离回归覆盖4类类型、49小时、明确0/缺值、反向区间、两个offset/DST场景、毫秒跨月、非法日期/缺offset/精度、协议范围和Unicode。根456=443执行通过（含既有84真实PG）+13fixture跳过、6MinIO排除。这里的84项是其他既有PG回归，不是新工时持久化证明。

## 下一步持久化与权限边界

工时V8已在niko独立执行1、重复0、只读check0，原V1–V7未改；仓储与API已实现并完成真实PG/HTTP回归，页面已接线并有单测/真实客户端HTTP证据，真实浏览器仍未验收，不能标工时填报完成。接续拟保存工单关联、本人执行者、实填来源及不可变修订；新结构独立迁移，V1–V7不改字节。修改必须本人、同租户、CAS、幂等和审计，不给系统管理员自动代改工时或推定Q09服务经理岗位。

默认工时分钟、适用范围、人力单价、交通成本、分摊和毛利均继续null/CONFIG_PENDING；本核心不输出成本/毛利，不把人工填写等同于外部考勤核验。缺工时的默认来源应作为独立确认配置/计算来源处理，不能用虚构起止填入实填记录。完整M5-21/M10-13及页面/毛利验收仍未执行。

## V8实际结构验证

新增mop_worklog与不可变mop_worklog_revision；同租户工单/成员复合FK、修订actor必须是固定owner、指向最新修订的延迟FK。实填source只能MANUAL_INTERVAL，4类类型及时间顺序/协议范围有DB约束；运行账号只有头记录version/updated_at列更新权，没有改owner/parent/历史或删除权限。没有增加默认分钟、成本、计费字段。

9项迁移策略、6项真实PG约束和20项Python守卫通过；根463=450执行（含90真实PG）+13fixture跳过，6MinIO排除。首次约束测试有一个INSERT占位符数量错误，修正测试后全量通过；已执行V8未改。所有约束测试数据事务回滚。详见[独立证据](evidence/niko-worklog-schema-20261002.json)。

## 2026-10-02 本人实填工时持久化

WorklogRepository及PostgresWorklogRepository已接入既有幂等事务，限定当前启用msp成员本人：父工单同租户、创建检查父版本、修改检查工时版本、固定owner/parent、不可变修订和同事务审计。本人列表/历史使用有界keyset；外人记录和错误父范围404，权限版本失效401，管理员没有自动代改权。物理经过时间不改变工单、SLA、默认工时或成本。

12项新增真实PG仓储回归通过，覆盖持久回读、49小时/显式0、两类重放、CAS、本人权限、其他owner隐藏、下游失败回滚、两个独立连接并发、分页及0001..9999毫秒边界。首次并发测试未获得预期409，定位为联表FOR UPDATE等待后旧修订快照与新头版本不匹配；改为先锁稳定头记录、再单独读取当前修订，完整回归通过。其他owner场景为显式标记的历史合成记录，未提升viewer角色或宣称viewer经API创建成功。

根475=462执行通过（含102真实PG）+13历史fixture跳过，6MinIO排除，0失败错误；断库503/无受保护事务拒写守卫同时通过。API仍V0.10，尚无公开工时端点/页面；真实OSS、浏览器及完整M5-21/M10-13验收不在本切片内。V1–V8和6个原私有配置均未修改。见[仓储证据](evidence/niko-worklog-repository-20261002.json)。上一95dca54的CI37026678180已回读success。

## 2026-10-02 本人工时API V0.11

新增同租户msp本人工时list/create/get/PATCH/history；仅该角色增加worklogs:read_own/write_own，padmin/reviewer/customer未获团队查看或代改。创建CAS父登记版本、修改CAS工时版本；每次重新授权，CSRF/幂等/不可变历史/审计，GET不写入，跨租户或错误父范围404。严格重复键/未知字段/有界32KiB请求与有界keyset查询。工时schema及列权限加入readiness，不自动迁移；liveness独立。

响应明确MANUAL_INTERVAL/SELF_REPORTED，elapsed_millis是实填起止物理差；默认分钟、人工成本、交通成本、毛利继续null/CONFIG_PENDING。无24小时上限、计费取整、默认4h、管理员代填或工单/SLA变更。

8项controller+1项readiness新增隔离测试、23项契约、Web207及类型/构建通过。最终根485=472执行通过（含102真实PG）+13fixture跳过，6MinIO排除；包含另外独立提交的解析器关闭竞态第9项测试。75项真实PG/OIDC HTTP/Ajv检查通过：49h/显式0、两类精确重放、409冲突、同租户本人权限、跨租户/错误父404、并发200/409、12条分页及父登记完全不变；另25项6个既有身份当前能力回归通过。首轮HTTP父登记误传asset_id:null导致400，修正测试为省略可选字段后全轮通过，未改报障契约。

本API阶段公开接口已接线，页面后续进度见下节；M5-21/M10-13及全部128项仍非DONE。原服务器、OSS、浏览器阻塞保留，V1–V8和原私有配置不动。工时仓储提交ecdc95d的CI曾因既有解析器关闭竞态失败，已独立修复422f118并确认CI37030716992成功，详见[说明](parser-shutdown-race.md)。见[工时HTTP](evidence/niko-worklog-api-http-20261002.json)、[当前角色HTTP](evidence/niko-worklog-role-http-20261002.json)、[本地回归](evidence/dot-worklog-api-20261002.json)。

### 当前端点与分页

- GET/POST /api/v1/tickets/{ticketId}/worklogs
- GET/PATCH /api/v1/tickets/{ticketId}/worklogs/{id}
- GET /api/v1/tickets/{ticketId}/worklogs/{id}/history

创建字段为expected_ticket_version/kind/started_at/ended_at/note，修改将版本字段改为expected_version。note必须显式null或原始非空白文本；不得提供owner/tenant/ticket/default/cost。日期含秒及Z/offset，最多毫秒精度。page_size选项10/15/25/50/100，默认15；列表以created_at/id倒序，下一页成对传next_before_created_at/next_before_id为before_created_at/before_id，历史传next_before_version为before_version。has_more=false时边界为null，不自动回第一页。边界只裁剪已授权的本人范围；不是权限令牌，新写入需刷新第一页查看。

## 2026-10-02 本人工时页面与原请求重试

报障详情新增“我的实填工时”独立抽屉：15条keyset列表、本人记录及不可变历史、明确类型/含时区起止、原始备注、物理秒数预览、新增/修改。表单不填默认日期/类型/工时，成本和默认值继续待确认；修改绑定操作者明确选中的id/version，后续读取到新版不能悄悄替换原编辑基线。

抽屉置于详情destroy-on-close之外，收起或切换登记详情保留原目标与未确认请求；存在未确认写入时不切到另一目标、离页提示，租户/身份/权限变动清理敏感上下文并拒绝迟到响应。保存结果未确认保留原body/key/CAS，仅明确点击原请求重试；普通刷新只GET，不自动补写。成功回执与当前列表分开，后续回读失败保留回执，不伪装整个页面已刷新。重试确认成功清空表单，避免误将原表单再次新增。

18项新增前端输入/DTO/分页/历史/状态回归通过，全Web225、类型检查/构建、契约23及生成一致性通过。实际前端WorklogWorkspace状态模块与ApiClient打包后连接真实PG/OIDC，28次HTTP完成：故意丢弃创建及修改的两个成功200响应，明确同键同body重试；普通刷新没有写入；另一客户端修改后旧编辑得到409，用户重新读取明确选新版；最终修订1/2/3且仅3条对应写审计。5次受测写尝试包含两次重放和一次409；另一个真实客户端的修改单独在28次HTTP内，不混淆为5次都新增版本。

这是受控客户端响应丢失注入，不是物理断网，也不是浏览器DOM/控件操作。桌面/390px视觉验收仍受B03阻塞，完整M5-21/M10-13及128项非DONE。V1–V8、API V0.11、私有配置未改。API提交e18ffac的CI37032139921已回读success。见[客户端HTTP证据](evidence/niko-worklog-client-http-20261002.json)、[页面回归](evidence/dot-worklog-ui-20261002.json)。

### 复现实际前端模块HTTP验证

先安装项目锁定依赖并运行node tools/build-worklog-client-test.mjs，再在获准组件同一命名空间capture现有OIDC会话后显式运行node tests/niko-worklog-client-http.mjs。生成物与会话只在忽略的.local/niko-test，脚本创建标记明确的合成报障/本人工时，不覆盖原证据。已有输出时拒绝覆盖，应另设明确新证据方案；不会启动浏览器或另建组件。

## 2026-10-04 本人跨工单只读查询仓储

M5-21/M10-13的本人实填记录新增跨工单查询基础：OwnWorklogQuery + listOwnAcrossTickets。身份与租户只来自已授权Access，当前活跃msp成员/permission_version重新核对；padmin、viewer没有代看团队权限，不推定Q09服务经理映射。可选工单UUID只在当前租户存在时接受，缺失/外租户404；没有指定工单时仍只查询当前执行人自己的记录。

过滤字段仅可选ticketId、四种kind、成对startedFrom/startedBefore。起始范围是对当前修订started_at的前闭后开匹配，UTC整毫秒、明确严格非空区间；不填就不选月份/时区/本月。返回完整自述区间，不按过滤窗口裁剪时长，也不把结束时间包含在选择规则中。明确0时长仍可读，未填写记录不会被制造成0或默认工时。不是期间总工时、费用、团队汇总、工作日日历或月报口径。

每次查询使用独立REPEATABLE READ只读事务，只连接当前修订；按(created_at,id)降序有界keyset（内部最多21含前探），不重复输出历史版本。跨请求页不是冻结快照，其他操作修改当前修订后过滤结果可能改变，不能冒充冻结报表。新查询原生UUID绑定，不把索引列转为文本；本次没有大规模数据或性能验收，不推定10秒语句超时等于满足业务SLO。查询不能嵌入受保护写事务，成功/空查询不增加审计/幂等/业务记录。备注/原起止保持原样，返回条目再核对操作者/租户/过滤条件。

新增4纯查询校验与12真实PG读取测试，涵盖跨两工单、当前修订筛选、范围端点/不裁剪/明确0、24条keyset、真实外租户工单404、历史其他owner隐藏（只在既有真实PG插入标记合成历史，不提升该身份）、角色/PV、读不写与嵌套拒绝。测试数据保留，不另建组件或凭据，不修改V1—V25。这个提交尚不新增公开HTTP或页面，在线API0.40保持不变；实际计数见[读取仓储证据](evidence/niko-own-worklog-reader-20261004.json)。团队Q09、默认工时/成本Q23、正式统计/验收仍NOT_RUN。

## 2026-10-04 API0.41本人跨工单查询

新增且只新增GET `/api/v1/my/worklogs`，复用原worklogs:read_own能力与真实V8仓储；仅当前msp本人/当前租户，不增加服务经理、padmin代看或团队权限。旧99条路径及217个schema全部原样保留，新增1路径2schema，总100路径219schema。

允许page_size=10/15/20（默认15）、成对before_created_at/before_id、可选ticket_id、四类kind及成对started_from/started_before。拒绝未知/重复/空参数、raw查询超过2048字节、owner/tenant/team/month/aggregate覆盖；时间必须显式Z/offset且最多毫秒，归一化UTC。全部过滤AND作用于当前修订；起始区间前闭后开，返回完整自述记录，不裁剪49小时、不把明确0当缺值，也不计算期间总工时。

返回明确tenant_id/owner_id、已应用filters、OWN_CURRENT_TENANT、CURRENT_MANUAL_ENTRIES、STARTED_AT_HALF_OPEN_IF_SUPPLIED、FULL_RECORDED_INTERVAL_NOT_CLIPPED；snapshot_frozen=false、aggregates=null、financial_status=CONFIG_PENDING，原每条Worklog的default_minutes/各成本/毛利继续null。新接口没有POST/PATCH；详情/历史仍用既有按工单本人路径。没有用来源完整性替代正式经营口径，也不声称跨请求分页是冻结快照。

10新增控制器测试、根2089报告/2076执行/699真实PG（13历史跳过、6MinIO排除），112契约、1402Web/82文件、类型/构建、136Python通过。普通拥有者守卫部署重启后56旧表完整行指纹在6OIDC新登录前一致，不声称崩溃/物理故障恢复；V25只读check0执行，固定API SHA14ebbdd17037c706ee9094f0a8e70fc1e36f1727046dcedc98b8caa0a52a8872。

47真实HTTP通过（25×200、3×202、11×400、1×401、5×403、2×404）；其中7写请求仅为既有报障/工时接口构造3合成父登记、3本人工时及1修订，新查询本身只GET。验证真实分页、两个工单、显式offset窗口/边界排除、49小时原区间、实填0、当前修订替代而非累加历史、角色/外租户工单及输入拒绝。成功只读窗口审计序号不变；3父登记仍版本1/状态优先级SLA未知，3工时/4历史/3创建+1修改审计留存，无新身份/凭据/存储/迁移。

证据见[API](evidence/niko-own-worklog-api-20261004.json)及[HTTP](evidence/niko-own-worklog-browse-http-20261004.json)。尚无跨工单查询页面，真实浏览器/团队Q09/默认成本Q23/正式经营验收仍NOT_RUN；已完成控制.local/niko-test/own-worklog-browse-api-control.json不得盲目重跑或覆盖原请求/收据。

## 2026-10-04 本人查询前端只读模块

新增ownWorklogBrowse.ts/ownWorklogBrowser.ts，固定GET-only入口；复用原Worklog字段/历史校验（仅导出历史读取函数，不改其写入逻辑）。明确当前msp、本人、租户及权限版本，拒绝把单个能力位或padmin当团队授权。过滤对象必须包含4个明确可空字段，显式offset按同一时刻规范化UTC；不补月份，不按查询窗口裁剪完整区间，49小时与明确0保留。

响应逐项核对精确字段、应用条件、owner/tenant、固定SELF_REPORTED/CONFIG_PENDING及成本null；拒绝聚合0、已冻结标志、假成本、错过滤、时长不等于原区间等数据。页内键唯一/降序，继续页必须严格位于旧游标后；查询修改立即失效旧页/详情，后发读取取消旧请求，错误不能留下旧结果或变成“成功空页”。只有用户明确选择当前已知记录才下钻父工单路径，不自动选择第一条。

详情核对原父对象/原创建时刻、非倒退版本与同版本内容（对象键顺序不影响语义）。历史复用本人逐版校验；翻历史前重新读取当前版，若已更新则回到最新历史，避免拼接不同版本边界。上下文/权限变化或卸载丢弃迟到响应；在详情返回后失效的请求不能再发历史请求。没有保存/修改/幂等键或代填接口。

28新增测试（14协议/14只读状态），全量1433/84文件、类型/构建通过。生产模块17真实GET（16×200、1×外租户工单404），复用此前3条合成工时，零业务写请求；7种实际200响应的受控篡改均被拒绝，实际当前2版历史/原49小时/明确0时间窗/空页未知聚合通过，成功只读窗口审计序号不变。延迟响应跨本地权限epoch测试不是真实成员撤权或物理网络故障；既有OIDC会话在该窗口前单独刷新。

证据见[只读模块](evidence/niko-own-worklog-client-20261004.json)和[模块HTTP](evidence/niko-own-worklog-client-http-20261004.json)。仍未实现跨工单查询页面，未做真实浏览器/手机/企微验收；没有增加团队权限、成本口径、新凭据或迁移。正式M5-21/M10-13仍NOT_RUN。

## 2026-10-04 本人跨工单只读页面

新增受保护的/my-worklogs路由与双菜单，仅当前msp且有worklogs:read_own时读取。明确可空工单/类型/开始下界与上界，不默认本月；修改条件立即清除旧结果，必须明确应用。已应用条件与输入草稿分开，刷新/续页保持当前条件；无默认第一条选择。当前记录和不可变历史分别读取，保留49小时完整原区间及明确0；固定Asia/Shanghai展示和UTC原文，不裁剪/汇总/默认成本。关闭详情取消迟到读取但保留列表，权限/租户变化与卸载清除旧scope。

18新增SFC测试与1关闭详情状态测试通过；全量1452测试/85文件、最终类型/构建通过。实际Vue组件/native jsdom连接既有真实PG/OIDC/API0.41，15次GET（14×200、1×外租户404）通过，复用3条原合成工时，零业务写请求，成功只读窗口审计序号不变。没有新凭据、迁移或后端重启。

首次真实组件运行在最终外租户场景等待超时，记录14次GET尝试、13次200且没有404返回；失败证据完整保留。独立同会话只读探测返回404/OBJECT_NOT_FOUND（29ms）。仅调整测试点击后的Vue渲染等待、补逐请求阶段台账后复跑通过，生产代码未因该失败修改；旧DOM等待条件提前满足是同步竞态推断，不宣称实际服务器曾发生故障。

证据见[页面](evidence/niko-own-worklog-page-20261004.json)、[首轮未完成](evidence/niko-own-worklog-page-http-20261004.json)、[复测](evidence/niko-own-worklog-page-http-20261004-r2.json)。44px控件/单列样式仅已实现，真实浏览器/CSS/手机/企微未验；团队授权Q09、默认工时与经营成本Q23继续待确认，M5-21/M10-13及全部128项正式验收仍NOT_RUN。
