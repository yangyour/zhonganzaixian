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

V25结构已显式实施，但已新增受保护真实PG仓储；仍没有报备API/页面、审批、设备采集、diff事件或自动P3；API0.39与在线固定包未改变。纯模型与既有PG回归不能冒充报备联调、已批准数据或M6-7完整验收。V1—V25保持冻结，后续结构修改需新增显式迁移；所有写入仍须当前授权/CSRF/幂等/CAS/审计，跨租户对象404。实际计数见[核心证据](evidence/niko-change-notice-core-20261004.json)。

## 2026-10-04 V25真实草稿结构

在既有niko PG17.11使用独立迁移身份显式执行V25一次，重复、只读检查及旧V24模式均0迁移。53张既有项目表在任何新增测试写入前完整行指纹一致、无排除字段；原服务器未操作。SQL SHA256=650ce8f83e9fc673cfb6a4838872638149650f735a90f37c5861a6fce771bc67，V1—V25冻结。API/Worker启动不自动迁移，在线API0.39固定包未重启或替换。

mop_change_notice_draft记录不可重新绑定的原申请人与严格递增版本；revision保留每次CREATE/UPDATE/ARCHIVE/RESTORE及原始内容；asset保留有序设备。同租户申请人/记录人/设备/可选工单外键不等同业务资格或审批授权，后续仓储仍须校验当前权限。runtime只能追加/读取修订及设备、更新头version/updated_at，禁止历史修改、删除、TRUNCATE或申请人重绑。

四个固定invoker守卫验证创建事务、版本完整、设备数量/顺序、Java一致的二进制内容SHA；提交后的修订不能再追加设备，提前SET CONSTRAINTS也不能超出声明数量。归档和恢复须逐字保持前一提案，未归档不能RESTORE、已归档不能UPDATE。结构保持approval_status/automatic_p3_decision为SQL NULL，approval/matching CONFIG_PENDING、所有执行/生效/抑制标志false。不生成审批结果、设备命令或告警事件。

新增21项真实受限runtime PG测试通过，覆盖Java↔PG摘要/UTF-8/顺序/可空结束、未知审批、同租户引用、历史ACL、状态转换、事务完整性、控制字符与固定上海9999年边界。另新增2项纯迁移策略测试。完整计数和文件指纹见[结构证据](evidence/niko-change-notice-schema-20261004.json)。这不是M6-7完整验收；审批/Q22、报备API/UI、实际采集/diff/有效报备联动仍NOT_RUN。

## 2026-10-04 当前租户草稿仓储

ChangeNoticeDraftRepository及PostgresChangeNoticeDraftRepository增加create/get/list/update/history/archive/restore。本切片只开放当前有效padmin成员维护未审核草稿；不据此宣称全部MSP/客户角色流程已完成。创建申请人必须等于当前授权操作者，更新不可重新指定申请人；后续操作者与原申请人独立记录。可选工单和全部设备在同一受保护事务中核验真实当前租户对象并加共享锁，缺失/跨租户均404，不以UUID格式冒充存在性。

写入必须从平台idempotentAuthorized原子事务进入；当前成员/权限版本回查早于已有回执重放。头/历史/设备/审计/幂等回执一并提交；CAS不同key并发只有一个200、一个409；相同key重放原收据不追加，归档后原创建收据仍保持原样。归档/恢复无新提案参数，保持原申请人/工单/摘要/窗口/设备顺序，不批准或执行。

读取使用独立REPEATABLE READ只读事务，不能混入受保护写事务；列表和历史有显式游标/上限，每个完整定义重新计算SHA并校验未知审批和全部false标志。实际SQL读取后受控修改抑制标志或摘要值都会503且真实库未改；该受控返回损坏不是数据库已遭篡改。真实SQL审计除零、设备FK失败及调用方异常分别证明五表回滚。就绪只验证结构，不声称审批、SSH、存储或事件链正常。

新增24真实PG仓储测试；第一轮23全部通过，追加指纹损坏防护后最终24全部通过。合成数据留存并由测试归档；没有删除旧业务、执行设备动作或创建新凭据，V1—V25字节不变。实际计数见[仓储证据](evidence/niko-change-notice-repository-20261004.json)。HTTP/CSRF/浏览器页面仍待独立实施验证，正式M6-7验收NOT_RUN。
