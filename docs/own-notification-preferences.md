# M8-10 本人通知偏好请求草稿

## 范围与门禁

依据02 M8-D07、TC-M8-10与Q14。客户可明确请求接收P1、P1+P2或全部通知，以及跨午夜静音和合同渠道；P1突破静音是已知合同事实。严重级到P级映射、联系人停用/决策人必达与偏好冲突、实际允许渠道和生产派发仍依Q14/Q18等确认，不能凭保存一个偏好草稿决定发/不发。

OwnNotificationPreferenceDraft当前只有纯不可变模型。tenantId/ownerId必须明确；当前成员与本人授权由未来仓储/API从可信会话核验，构造器本身不认证调用者。requestedLevel为可空P1_ONLY/P1_P2/ALL；requestedChannels为可空六通道集合，未知null与明确[]不同。拒绝重复/null项，不静默去重；顺序按通道代码ASCII名称排序，仅做规范编码，绝不是渠道优先级。

通道复用已有EMAIL、SMS、DINGTALK、WECHAT_WORK、WEBHOOK、VOICE枚举，不新增端点、接收地址、token或密钥字段。请求选中VOICE不表示可以外呼，空集合也不表示P1可以被停用。

requestedQuietWindow复用现有CustomerContact.QuietWindow的明确分钟语义：整个null未知；对象enabled=null时start/end只能null；明确false也不能带隐藏时段；true必须两个不同HH:mm，允许跨午夜，拒绝秒/纳秒与相同边界。整个null与显式未知对象仍是不同原始请求。固定Asia/Shanghai来自现有业务时区，不取JVM/浏览器默认时区。模型不把请求自动转成QuietHours.Policy。

recordSource=OWN_NOTIFICATION_PREFERENCE_DRAFT、configurationStatus=CONFIG_PENDING、effective=false；effectiveLevelPreference/effectiveChannels/effectiveQuietWindow/verifiedReceiverId/deliveryDecision均null，receiverBindingStatus=NOT_VERIFIED、notificationDispatched=false。p1QuietBypassIsContractRule=true只声明合同条款，不是已产生派发计划或电话30秒证据。

## 规范指纹

格式MOP_OWN_NOTIFICATION_PREFERENCE_DRAFT_V1。F(x)=大端int32 UTF-8字节长度加原字节，null长度为-1。顺序：F格式、F租户UUID、F本人UUID、F请求档位；渠道null写int32 -1，否则写int32个数及依次F规范通道代码；窗口null写int32 -1，否则写int32 3及F enabled字符串true/false/null、F开始HH:mm/null、F结束HH:mm/null；最后F固定时区。

独立Python struct/UTF-8金样202字节，SHA256=ecae66e7ed94c48a01fc19b2d53add2c683c66e4a5b0f84ca1720c2fca1942a5，虚构ID和输入见OwnNotificationPreferenceDraftTest。指纹绑定范围和明确原始请求，不是身份签名、客户审批或供应商送达证明。通道顺序归一化，null/空集、档位、窗口与scope变化会改变指纹；输入集合和返回bytes不可别名修改。

## 仍待完成

本切片未增加SQL、持久化、HTTP或页面，未绑定真实通知接收人，也不调用通道或修改联系人的既有偏好。下一步须当前本人授权、唯一当前草稿/不可变修订、CSRF、幂等、乐观锁与审计；V1–V25已冻结，结构增加只能新增迁移并显式独立执行，API/Worker不自动迁移。真实发送、P1外呼、跨渠道矩阵和M8-10完整验收保持NOT_RUN；根既有PG回归不计作本功能持久化证据。

## 本批验证

19新增纯测试，与已有QuietHours20/CustomerContact14共53聚焦通过；根2139报告/2126执行（706既有真实PG）、13历史fixture跳过、6MinIO排除，116契约/生成一致性通过。新增功能没有PG仓储测试，不将既有706项记成本人偏好持久化联调。没有新凭据、迁移、API/页面或后端重启，在线仍原固定API0.42。见[纯核心证据](evidence/niko-notification-preference-core-20261004.json)。
