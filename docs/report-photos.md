# 客户报障照片代码切片

更新：2026-10-01；接续分支`handoff/cloud-20261001-photos`，交接基线`caff027d7e1fb2e65f0babf8b6de0369b46fe48e`。当前为DOING/PARTIAL：新增实现和实际API契约已接续，V5未执行，指定PG/MinIO、真实OIDC HTTP及页面联调未完成。此文不登记照片业务或完整M5-5/M8-6为DONE。

## 接口与状态

实际契约为`contracts/local-api.openapi.json` V0.5，所有JSON响应沿用`{data,trace_id}`或统一错误信封。

|接口|当前代码行为与门禁|
|---|---|
|GET /api/v1/files/policy|当前授权成员读取；JPEG/PNG白名单、5_000_000 bytes、3张；未配置或无效白名单为null，上传422 CONFIG_PENDING/Q28|
|POST /api/v1/files|仅multipart一个file及purpose=customer_report_photo，CSRF/Idempotency-Key；新受理需存储可用与照片结构齐备。202前原图bytes/file/job/operation/审计/重放结果同PG事务；同键原请求重放不再次PUT|
|GET /api/v1/files/{id}|本人或当前授权内部租户回读元数据；PENDING/READY/FAILED，不公开object_key、桶、暂存bytes或秘密|
|GET /api/v1/operations/{id}|区分报障CUSTOMER_REPORT_REGISTRATION门禁和FILE_UPLOAD；GET不领取任务或重新提交|
|POST /api/v1/files/{id}/content-grants|空JSON对象、CSRF/幂等；仅READY签发15分钟持久平台grant，绑定actor/tenant/file/permission_version|
|GET /api/v1/files/{id}/content?grant=UUID|当前会话与grant联合校验；实际GET核验原图size/SHA，读前后重新授权；no-store/nosniff/inline。格式不符400，不可见/过期/撤权404|
|POST /api/v1/tickets/{id}/attachments|expected_version及1–3个不重复UUID，CSRF/幂等；仅报障人追加同租户、同上传人READY图片，原登记累计最多3张，冲突409；历史动作ADD_ATTACHMENTS|

FILE_UPLOAD初始PENDING/QUEUED，领取后RUNNING/UPLOADING；存储核验成功才SUCCEEDED/STORED且file为READY。依赖失败保留耐久任务PENDING/RETRY_WAIT；授权变化FAILED/AUTHORIZATION_REVOKED；已有对象摘要不符FAILED/STORAGE_CONFLICT。进度为null，不由浏览器计算假进度。报障operation仍PENDING/AWAITING_TICKET_CONFIGURATION，正式state/priority/SLA截止仍null；图片成功不能解除这些门禁。

## 原图、身份与并发

每张限1..5_000_000 bytes，只接收JPEG/PNG。扩展、声明类型及真实内容须一致；未声明MIME或application/octet-stream按服务端内容核验。原图进行签名、结构及完整解码检查，尺寸≤16_384、像素≤25_000_000属于技术资源防护，文件名≤200个Unicode字符并拒绝路径、控制字符及不安全名称。原bytes不重编码，SHA绑定受理及回读内容。解码不代表病毒扫描、EXIF去隐私或客户生产批准。

扩展名必须有位于首尾之间的点号，png/jpg/jpeg无点号文件名一律拒绝。JPEG边界检查要求首个结构EOI结束原图，修复合法JPEG后加junk+FFD9或再拼接JPEG仍被接受的缺口；APP内容里的marker样字节不被误作图像结束。修复后56个真实JDK策略断言保留合法baseline stuffing/restart及progressive多SOS的原bytes/hash，详见[独立策略证据](evidence/cloud-photo-policy-verification.json)。这些纯检查不等于Controller HTTP或手机支持验收；合成TEM/DNL边界样例仍被完整decoder拒绝，不宣称额外codec支持。

客户viewer/tadmin只能上传本人照片及追加本人报障；客户读本人的file/operation/登记，内部msp/padmin按当前授权租户只读。跨租户及同租户无关客户统一404。权限版本在事务内重新核查，旧Cookie、已受理重放、Worker执行及下载grant都不能绕过撤权。浏览器不持有S3账号或对象存储直链。

报障可先无图登记，照片失败不撤销已受理记录。前端当前页面保留原File、原multipart幂等键及原请求；网络结果不确定时手动同请求重试，已收到202后只GET回读。追加请求保留原ticket版本、照片列表与幂等键；409显式刷新并比较后才能形成新请求。切租户、会话变化及离开页面取消请求并释放预览/原图Blob，不把图片或业务记录放入localStorage。

附件写入先锁ticket，再按稳定文件ID顺序锁定照片；验证同租户/上传人/READY、未绑定其他登记、现有数量和CAS，关系/版本/history/audit/重放结果同事务。已知设备、同报障人、同分类30秒语义去重带新图片会追加原登记，不覆盖原正文及联系人；无新照片保持原记录。HTTP幂等与语义去重独立；未知设备及跨报障人共享去重范围仍待确定。

## 照片Worker与存储

新增Worker仅轮询PG照片任务，不消费报障outbox、RabbitMQ或其他业务。显式`remote_test`及`MOP_PHOTO_WORKER_ENABLED=true`，限定项目运行账号、受控loopback PG和项目MinIO命名空间；启动前校验V5结构/权限及存储可用，条件不齐会拒绝运行。每实例串行轮询；当前不能据此宣称全部任务公平调度、消息服务或租户Worker并发治理已完成。

任务使用PG时钟、120秒租约及单调fencing token，重启后可接管到期任务。领取、调用存储前及完成事务复核当前上传授权；完成/失败/重试写回校验owner/token/未过期租约，旧Worker不能提交接管后的结果。依赖重试使用技术退避并保留任务；未知PUT结果通过实际对象回读核验，不盲目覆写。

key为`photos/<tenantUUID>/<fileUUID>`，条件创建；已有对象只有真实size/SHA吻合后认领。使用现有MinIO受限账号，无DeleteObject或生命周期修改权限。桶存在检查不证明全部IAM或匿名访问约束，仍须真实组件验证。暂存bytes、对象及grant没有已批准清理规则，不能擅自清除。

API与Worker启动不执行Flyway。V1–V4保持已执行版本，V5未执行且仍待审核/独立迁移；缺照片结构时无图登记沿用原基线，照片入口明确未就绪。当前无独立可用迁移CLI；现有远程PG测试限定Flyway target("4")，不能作为V5执行或照片验收入口。

## 验证与剩余门禁

本轮实际执行计数见[云端验证记录](verification.md)及[机器摘要](evidence/cloud-photo-verification.json)。纯契约、前端及纯JDK断言不能证明PG事务、MinIO条件写、Worker恢复或真实授权下载已联调。

当前后端保存为明确待验证WIP源码，不是已完成功能。工单详情/列表以只读REPEATABLE READ快照读取版本、历史和附件，已有写事务保持原隔离及锁；主查询时复核当前成员和权限版本，随后撤权不追溯取消本次读取。新增两个真实PG交错回归用例尚未运行，总计16项PG照片测试均NOT_RUN，完整模块类型检查仍未完成。

待真实执行：指定PG迁移/权限与事务回滚、跨租户/本人隔离、并发同键/异内容、附件CAS及3张总量、语义去重追加、grant到期/撤权、真实MinIO条件创建/同内容认领/异内容拒绝、依赖断链/重启、旧Worker fencing及恢复、真实OIDC HTTP/契约、1440/390px页面。不得以fixture替代组件失败。

JPEG/PNG是用户开发方向，策略保持USER_DEVELOPMENT_DIRECTION、UNSCANNED、生产UNVERIFIED、retention_until=null。Q28扫描/隔离及其他文件策略、Q10保留/清理仍待客户正式确认。Q07剩余状态、Q08日历、Q09岗位资格、Q14优先级/通知、Q16档位继续保留。

M5-5还要求手机拍照、10秒内工单号、值守30秒企微推送及原图；M8-6要求真移动拍照到提交≤30秒、工单号与按档位预计响应时限。没有真手机/企微及完整规则证据，不能把照片切片标为两项全部DONE。原128个合同TC保持NOT_RUN，后续逐功能验证并更新证据后提交推送。
