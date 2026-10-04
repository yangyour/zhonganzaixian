# M8-10 本人通知偏好请求草稿

## 范围与门禁

依据02 M8-D07、TC-M8-10与Q14。客户可明确请求接收P1、P1+P2或全部通知，以及跨午夜静音和合同渠道；P1突破静音是已知合同事实。严重级到P级映射、联系人停用/决策人必达与偏好冲突、实际允许渠道和生产派发仍依Q14/Q18等确认，不能凭保存一个偏好草稿决定发/不发。

OwnNotificationPreferenceDraft为纯不可变模型，V26另提供请求草稿结构。tenantId/ownerId必须明确；当前成员与本人授权由未来仓储/API从可信会话核验，构造器本身不认证调用者。requestedLevel为可空P1_ONLY/P1_P2/ALL；requestedChannels为可空六通道集合，未知null与明确[]不同。拒绝重复/null项，不静默去重；顺序按通道代码ASCII名称排序，仅做规范编码，绝不是渠道优先级。

通道复用已有EMAIL、SMS、DINGTALK、WECHAT_WORK、WEBHOOK、VOICE枚举，不新增端点、接收地址、token或密钥字段。请求选中VOICE不表示可以外呼，空集合也不表示P1可以被停用。

requestedQuietWindow复用现有CustomerContact.QuietWindow的明确分钟语义：整个null未知；对象enabled=null时start/end只能null；明确false也不能带隐藏时段；true必须两个不同HH:mm，允许跨午夜，拒绝秒/纳秒与相同边界。整个null与显式未知对象仍是不同原始请求。固定Asia/Shanghai来自现有业务时区，不取JVM/浏览器默认时区。模型不把请求自动转成QuietHours.Policy。

recordSource=OWN_NOTIFICATION_PREFERENCE_DRAFT、configurationStatus=CONFIG_PENDING、effective=false；effectiveLevelPreference/effectiveChannels/effectiveQuietWindow/verifiedReceiverId/deliveryDecision均null，receiverBindingStatus=NOT_VERIFIED、notificationDispatched=false。p1QuietBypassIsContractRule=true只声明合同条款，不是已产生派发计划或电话30秒证据。

## 规范指纹

格式MOP_OWN_NOTIFICATION_PREFERENCE_DRAFT_V1。F(x)=大端int32 UTF-8字节长度加原字节，null长度为-1。顺序：F格式、F租户UUID、F本人UUID、F请求档位；渠道null写int32 -1，否则写int32个数及依次F规范通道代码；窗口null写int32 -1，否则写int32 3及F enabled字符串true/false/null、F开始HH:mm/null、F结束HH:mm/null；最后F固定时区。

独立Python struct/UTF-8金样202字节，SHA256=ecae66e7ed94c48a01fc19b2d53add2c683c66e4a5b0f84ca1720c2fca1942a5，虚构ID和输入见OwnNotificationPreferenceDraftTest。指纹绑定范围和明确原始请求，不是身份签名、客户审批或供应商送达证明。通道顺序归一化，null/空集、档位、窗口与scope变化会改变指纹；输入集合和返回bytes不可别名修改。

## 仍待完成

纯核心阶段未增加SQL、持久化、HTTP或页面；后续V26结构证据见下文，未绑定真实通知接收人，也不调用通道或修改联系人的既有偏好。下一步须当前本人授权、唯一当前草稿/不可变修订、CSRF、幂等、乐观锁与审计；当前V1–V26已冻结，结构增加只能新增迁移并显式独立执行，API/Worker不自动迁移。真实发送、P1外呼、跨渠道矩阵和M8-10完整验收保持NOT_RUN；根既有PG回归不计作本功能持久化证据。

## 本批验证

19新增纯测试，与已有QuietHours20/CustomerContact14共53聚焦通过；根2139报告/2126执行（706既有真实PG）、13历史fixture跳过、6MinIO排除，116契约/生成一致性通过。新增功能没有PG仓储测试，不将既有706项记成本人偏好持久化联调。没有新凭据、迁移、API/页面或后端重启，在线仍原固定API0.42。见[纯核心证据](evidence/niko-notification-preference-core-20261004.json)。

## V26 本人唯一草稿及不可变修订结构

2026-10-04在niko现有PG17.11通过独立CLI显式执行V26一次，重复执行与最终只读检查均0迁移。SQL SHA256=df3c59cc6c241316b110ae87b3cb7a80aee57b4847f4122e7786b90ac94c23d8，V1–V26全部字节冻结；后续结构修正须V27或更高。迁移前后56张旧业务表完整行等值，快照核对发生在23:16:19 UTC、结构测试之前，不将后续测试写入算作迁移保持不变。

两表分别保存同租户本人唯一当前头及连续不可变修订；版本、UTC毫秒记录时间、同一事务插入、前版本/当前版本、内容规范指纹、同租户成员和owner绑定由FK/check/三触发器保护。null渠道与空数组、整个null静音与显式enabled=null对象均保留。时区固定Asia/Shanghai，状态CONFIG_PENDING、effective=false，有效规则/接收人/派发决定null，notificationDispatched=false。结构不认证API调用者，后续仓储仍须从可信上下文核验当前本人和权限版本。

运行角色只能SELECT/INSERT及更新头version/updated_at，不可删头、改历史或扩展身份；固定pg_catalog搜索路径、invoker函数、SQL/函数体SHA与列/约束/触发器/ACL均独立校验。没有新凭据、用户、API启动或Worker迁移；在线仍原固定API0.42。

最终76聚焦（35迁移策略+22真实PG结构+19纯核心）通过；根2163报告/2150执行，含728真实PG、13历史fixture跳过、6MinIO排除，146Python、116契约/生成一致性通过。真实结构测试选取已有且未占用偏好槽位的活动成员，每项事务回滚；不会删除既有偏好以重跑测试，新加入的重复选择测试验证已占用槽位不会复用。测试后两张偏好表均0行。niko没有原生pwsh，本地该项NOT_RUN，等待本提交CI独立结果；不引用旧CI冒充新策略通过。

仓储本人授权、保存回执、历史查询、HTTP/页面、真实接收人和渠道发送仍待完成，M8-10及128项正式验收保持NOT_RUN。见[结构证据](evidence/niko-notification-preference-schema-20261004.json)。
