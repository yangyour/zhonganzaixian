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

当前没有新迁移、真实报备仓储/API/页面、审批、设备采集、diff事件或自动P3；API0.39与在线固定包未改变。纯模型与既有PG回归不能冒充报备联调、已批准数据或M6-7完整验收。V1—V24保持冻结，后续持久化需新增显式迁移；所有写入仍须当前授权/CSRF/幂等/CAS/审计，跨租户对象404。实际计数见[核心证据](evidence/niko-change-notice-core-20261004.json)。
