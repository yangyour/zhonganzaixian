# M6-7 变更报备草稿：计划事实与审批门禁

## 合同依据与当前范围

M6-7/T00R084要求报备设备、变更摘要、计划执行时间、申请人和审批状态；M4-17与M6共用每6小时diff链，不重复建设。02 M6-D05还记录工单、资产集合与窗口；03要求有效报备必须覆盖设备/窗口且已批准。Q22仍未确认审批通过范围、容差、紧急补报、多设备及部分失败。

ChangeNoticeDraft本切片只建模未审核提案。字段为tenantId、applicantId、可空ticketId、changeSummary、明确plannedStartAt、可空plannedEndAt与有序assetIds。可选工单引用是02设计字段，不强制把合同未要求关联的所有报备绑定到一张工单；没有默选最近工单或默认当前时间。

## 校验与未知值

租户/申请人/开始时刻/摘要/非空设备均须明确。摘要1—2000 Unicode码点，允许原始换行和制表，保留前后空白但拒绝全空白、孤立代理字符和其余控制字符。设备1—1000个唯一非空UUID，防御复制并保留顺序；1000是技术保护，不是业务配额，超限拒绝、不截断或静默去重。

plannedEndAt可以null，不补一小时、次日或默认容差；非null时必须严格晚于开始。两时刻使用UTC整毫秒，且UTC与固定Asia/Shanghai业务日期均处0001—9999年。未来或历史计划时刻都可记录为自述草稿，不据此认定真实执行、紧急补报获批或窗口有效。没有端点包含规则、时间容差或事件匹配算法。

requireTenant只比较明确调用范围，不同租户404；它不是实际身份授权。设备、申请人和可选工单UUID都仅为引用，referenceVerification=NOT_VERIFIED_BY_THIS_MODEL；未来仓储须真实核验当前租户对象及申请人，不能拿UUID格式当存在性或授权证据。

## 审批与动作边界

lifecycleStatus=DRAFT、recordSource=UNREVIEWED_CHANGE_NOTICE_DRAFT；approvalWorkflowStatus和matchingPolicyStatus均CONFIG_PENDING，approvalStatus与automaticP3Decision为null。effective、approvalEvidenceVerified、deviceExecutionAllowed、alertSuppressionAllowed均false。

填齐设备、摘要、时间和申请人不能让提案变成已批准报备；未知审批不能当作“有效报备”压掉告警，也不能据此断言全局不存在其他有效报备并自动建P3。没有审批/生效/取消告警/SSH动作或调度实例。后续真实匹配必须读取有权威证据的批准修订及已确认Q22策略，而不是调用本模型作为审批判据。

## 确定性内容指纹

FORMAT=MOP_CHANGE_NOTICE_DRAFT_V1。F表示大端int32 UTF-8字节长度及原字节，null长度为-1。字段顺序：F格式、F租户、F申请人、F可空工单、F摘要、大端int64开始EpochMillis、结束null时int32(-1)或非null时int32(8)+int64结束EpochMillis、大端int32设备数及依次F设备UUID。返回新byte[]；SHA-256是内容指纹，不是签名、审批或对象真实性证明。

独立Python struct/UTF-8金样243字节、SHA256=36e3b2c1427636355944e0a1e256b0305a82946add4498e036246f4d0165f3be；明确虚构输入见ChangeNoticeDraftTest。租户、申请人、可选工单、完整原文、开始/结束与设备顺序均影响指纹。

## 未完成部分

V25结构、受保护真实PG仓储及API0.40草稿HTTP已实现；页面已接通，仍没有审批、设备采集、diff事件或自动P3。纯模型与既有PG回归不能冒充报备联调、已批准数据或M6-7完整验收。V1—V25保持冻结，后续结构修改需新增显式迁移；所有写入仍须当前授权/CSRF/幂等/CAS/审计，跨租户对象404。实际计数见[核心证据](evidence/niko-change-notice-core-20261004.json)。

## 2026-10-04 V25真实草稿结构

在既有niko PG17.11使用独立迁移身份显式执行V25一次，重复、只读检查及旧V24模式均0迁移。53张既有项目表在任何新增测试写入前完整行指纹一致、无排除字段；原服务器未操作。SQL SHA256=650ce8f83e9fc673cfb6a4838872638149650f735a90f37c5861a6fce771bc67，V1—V25冻结。API/Worker启动不自动迁移，在线API0.39固定包未重启或替换。

mop_change_notice_draft记录不可重新绑定的原申请人与严格递增版本；revision保留每次CREATE/UPDATE/ARCHIVE/RESTORE及原始内容；asset保留有序设备。同租户申请人/记录人/设备/可选工单外键不等同业务资格或审批授权，后续仓储仍须校验当前权限。runtime只能追加/读取修订及设备、更新头version/updated_at，禁止历史修改、删除、TRUNCATE或申请人重绑。

四个固定invoker守卫验证创建事务、版本完整、设备数量/顺序、Java一致的二进制内容SHA；提交后的修订不能再追加设备，提前SET CONSTRAINTS也不能超出声明数量。归档和恢复须逐字保持前一提案，未归档不能RESTORE、已归档不能UPDATE。结构保持approval_status/automatic_p3_decision为SQL NULL，approval/matching CONFIG_PENDING、所有执行/生效/抑制标志false。不生成审批结果、设备命令或告警事件。

新增21项真实受限runtime PG测试通过，覆盖Java↔PG摘要/UTF-8/顺序/可空结束、未知审批、同租户引用、历史ACL、状态转换、事务完整性、控制字符与固定上海9999年边界。另新增2项纯迁移策略测试。完整计数和文件指纹见[结构证据](evidence/niko-change-notice-schema-20261004.json)。这不是M6-7完整验收；审批/Q22、报备API/UI、实际采集/diff/有效报备联动仍NOT_RUN。

## 2026-10-04 当前租户草稿仓储

ChangeNoticeDraftRepository及PostgresChangeNoticeDraftRepository增加create/get/list/update/history/archive/restore。本切片只开放当前有效padmin成员维护未审核草稿；不据此宣称全部MSP/客户角色流程已完成。创建申请人必须等于当前授权操作者，更新不可重新指定申请人；后续操作者与原申请人独立记录。可选工单和全部设备在同一受保护事务中核验真实当前租户对象并加共享锁，缺失/跨租户均404，不以UUID格式冒充存在性。

写入必须从平台idempotentAuthorized原子事务进入；当前成员/权限版本回查早于已有回执重放。头/历史/设备/审计/幂等回执一并提交；CAS不同key并发只有一个200、一个409；相同key重放原收据不追加，归档后原创建收据仍保持原样。归档/恢复无新提案参数，保持原申请人/工单/摘要/窗口/设备顺序，不批准或执行。

读取使用独立REPEATABLE READ只读事务，不能混入受保护写事务；列表和历史有显式游标/上限，每个完整定义重新计算SHA并校验未知审批和全部false标志。实际SQL读取后受控修改抑制标志或摘要值都会503且真实库未改；该受控返回损坏不是数据库已遭篡改。真实SQL审计除零、设备FK失败及调用方异常分别证明五表回滚。就绪只验证结构，不声称审批、SSH、存储或事件链正常。

新增24真实PG仓储测试；第一轮23全部通过，追加指纹损坏防护后最终24全部通过。合成数据留存并由测试归档；没有删除旧业务、执行设备动作或创建新凭据，V1—V25字节不变。实际计数见[仓储证据](evidence/niko-change-notice-repository-20261004.json)。本仓储批未验证HTTP/CSRF；后续API证据见下，浏览器页面仍待，正式M6-7验收NOT_RUN。

## 2026-10-04 API0.40未审核报备草稿入口

实际新增GET/POST `/api/v1/change-notice-drafts`、GET/PATCH `/{id}`、GET `/{id}/history`与POST `/{id}/archive|restore`；这是同步200草稿入口，正式 `/change-notices` 审批/有效窗口/事件联动仍未实现，不返回伪202或伪操作完成。current padmin拥有两个显式draft能力，其他角色403；CSRF、当前成员、幂等、CAS、审计与跨租户404沿用真实平台事务。

创建必须精确提交expected_version=0、change_summary、asset_ids、planned_start_at、planned_end_at、ticket_id；后两项未知须显式null，不默认执行时刻或工单。申请人只从当前会话确定，更新先读取不可重绑的原申请人再走受保护CAS，客户端不能传applicant_id。更新expected_version为正；归档/恢复只接受版本、不接受修改原提案。UTC整毫秒与上海日期范围、结束严格晚于开始仍由服务端校验。JSON最大262144字节/4000token/深度4，拒绝重复字段、未知字段、尾随文档和非整数版本。

列表只返回有界元数据（含原申请人、可选工单、明确窗口/设备数/内容SHA），不返回设备ID集合和变更摘要正文；详情与每条历史含完整原文/有序设备。page_size只10/15/20，列表成对游标、历史before_version；读取不追加审计/收据。摘要SHA不是审批证据，unknown审批/P3保持null，审批/匹配CONFIG_PENDING，生效/执行/抑制均false。

ready新增V25结构依赖，缺失/null/异常503，live独立；schema_access_ready仅表示结构权限，不表示审批策略、SSH/diff或告警健康。旧94路径除ready扩展均未改变、207个非ready旧schema不变；新增5路径9schema，总99路径217schema。实际契约见contracts/local-api.openapi.json版本0.40.0。

19新增控制器测试与1新增ready测试、全量2063报告/2050执行/687真实PG（13历史跳过、6MinIO排除）；109契约、1327Web/79文件、类型/构建、136Python通过。初次契约107通过2失败为旧ready样例缺新依赖与旧版本断言；补齐样例并保留原检查后109全部通过。

固定API工件普通拥有者守卫重启，56张既有表完整行指纹在6次真实OIDC新登录前相同；不声称崩溃/故障恢复。83真实HTTP（42×200、18×400、1×401、9×403、6×404、5×409、2×413）覆盖原收据重放/同key并发/不同key CAS/15版历史、同租户可选工单与设备/角色/CSRF/技术边界。仅2个自有合成草稿留存归档，不产生设备或审批动作；完整回读见[API证据](evidence/niko-change-notice-api-20261004.json)和[HTTP证据](evidence/niko-change-notice-http-20261004.json)。B03真实浏览器/手机/WeChat和Q22审批策略仍阻塞相应验收，所有128项NOT_RUN。

## 2026-10-04 前端原请求状态与内容校验

生产模块changeNoticeDrafts.ts / changeNoticeWorkspace.ts只接受当前padmin明确上下文及API0.40精确字段。摘要只核对元数据，不冒充全文指纹已验；详情/历史重算与Java/PG一致的243字节金样SHA，绑定租户、原申请人、工单、原文、时间和有序设备。UTC整毫秒、真实日历与固定上海0001—9999年同时校验，结束未知仍null；等价Z/.000Z按同一时刻核对，不擅改原请求负载。

创建回执申请人必须等于会话，后续修订保持原申请人（可与当前记录人不同）。历史逐版连续，归档/恢复内容一致；跨分页边界也核对申请人、时间、归档关系及内容摘要，不仅检查一页内部。初始摘要申请人与记录人必须一致，审批/P3只能null，执行/抑制false；损坏摘要、伪批准、外租户等返回不进入可展示状态。

原path/method/key/JSON负载/基线在异步前复制，调用者/回调/传输对象不能改写保留请求。写入在途或未知时不允许新提交；普通GET刷新不能确认原请求。原重试即使得到400/403/404/409/422，仍不解除先前未知状态；只有匹配原请求的完整有效回执才接受，随后新操作须明确begin。权限上下文改变/卸载使迟到成功失效；读取/哈希完成后重新检查上下文，不能把旧租户结果放回新上下文。首次已知拒绝可显式解锁修正，不自动合并CAS冲突。

49新增Web测试（22定义/27状态），全量1376/81文件及类型/构建通过。26真实模块HTTP（25×200、1×真实msp403），4次同key创建请求只有1提交；受控丢弃真实提交响应、损坏重放SHA、5种实际GET响应损坏、延迟GET跨本地上下文切换均有证。这里不是真实成员撤权或物理网络断链。最终模块另3次只读GET复核真实当前/5版历史与初始申请人保护，没有新写入。1合成草稿保留归档，5修订/10设备引用/5成功审计/1创建；API0.40固定包和V25未变。

最初HTTP脚本语法预检发现分号遗漏，未执行任何HTTP，修正后首次真实场景通过；新增跨页测试最初构造了页内不合法的ARCHIVE→UPDATE测试数据，48通过1失败，补成ARCHIVE→RESTORE合法前页后49通过，未弱化生产校验。证据见[客户端](evidence/niko-change-notice-client-20261004.json)与[模块HTTP](evidence/niko-change-notice-client-http-20261004.json)。客户端批未实现页面；后续页面见下，真实浏览器/手机/WeChat仍未验收。

## 2026-10-04 报备草稿页面与组件真实HTTP

新增 `/change-notice-drafts` 受保护页面，桌面/移动菜单同样要求当前padmin与change_notice_drafts:read。原生表单明确摘要、UTC开始、可空结束及可选工单UUID，不默认当前时间/计划窗口/最近工单；原申请人只展示，不可输入或重绑。开始必须含Z与整毫秒；结束勾选未知保持null，取消未知后空值不能保存。详情同时显示固定Asia/Shanghai业务时间与原UTC，避免浏览器本地时区或隐含转换。

用户先查询实际当前租户资产，再明确逐项勾选加入；保留点击顺序，已选不重复，1000技术上限不截断，选中引用按10个分组可上移/下移/移除。改查询立即取消旧候选/勾选；旧修订设备仅显示“名称未载入”，不冒充在线或已授权。列表不包含摘要正文，按明确UUID/版本打开当前详情，原收据/当前记录/历史分别展示；历史每次只展开一版，内容插值转义。

新提交、关闭、路由与自愿上下文切换都受原未知写入门禁；强制权限变化清空旧范围并忽略迟到结果。已知拒绝必须明确解锁，输入和旧版本保留，不自动合并新读版本。归档/恢复先确认，只发送expected_version，原提案保持且不批准/执行/抑制。3步条、44px控制和单列断点已经落到源码，尚未验证真实CSS外观。

26新增原生Vue/jsdom SFC测试，全量1402/82文件、类型/构建通过。显式生产组件连接既有PG/OIDC API0.40，18真实HTTP均200、6写请求、2次原创建同key/body只提交1次；经受控丢弃真实提交响应后普通刷新仍未知，原重试恢复收据，再编辑/历史/归档恢复。1自有合成报备保留并归档、5修订/10设备引用/5成功审计/1创建、pending=0。

此场景使用原生jsdom控件与确认对话框测试替身，不是浏览器导航、真实弹窗视觉、CSS、物理手机或WeChat验收。只新增前端页面，固定API工件未重启，V1—V25不变，无设备/审批/抑制动作；见[页面证据](evidence/niko-change-notice-page-20261004.json)与[组件HTTP](evidence/niko-change-notice-page-http-20261004.json)。审批/Q22、实际设备/diff/自动P3及完整128项仍NOT_RUN。

## 2026-10-04 时间词法一致性补正

代码核对发现：前端曾按“精确到整毫秒”判断时间，因而允许 `.0000Z` 等多余零小数位；服务端的既有输入语法最多3位，会400拒绝，并没有接受或截断这些输入。现在前端在生成key/发送HTTP前同样拒绝，表单明确提示“小数最多3位”。新增控制器用例同时固定该既有拒绝行为，不修改线上服务端逻辑。

同次检查修复共享utcInstantNanos的整串匹配：JavaScript的`$`可在最后换行符前匹配，原先可能只验证前缀。现在要求匹配内容等于完整输入，拒绝LF/CR/CRLF/U+2028/U+2029尾缀；保留其他功能所需的合法1—9位纳秒解析，不统一缩成毫秒。报备专属输入仍只接受0—3位。

API0.41版本与全部100路径不变，只校准ChangeNoticeDraftCreate/Update两个请求schema的已有时间语法，拒绝多余小数和尾换行；其余217schema原样保留。不是新增服务端能力、重新部署或数据库迁移。测试、全量回归和最终有效记录只读复核见[词法补正证据](evidence/niko-change-notice-time-lexical-20261004.json)，实际浏览器/手机与正式验收仍NOT_RUN。
