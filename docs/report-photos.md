# 客户报障照片实现与验证

更新：2026-10-01；云端最后交接`1db3976`已接回本地，云端停止业务写入。本批V5、完整构建、指定PG/MinIO、基础HTTP80次、恢复/撤权HTTP52次、运行中重试HTTP25次及UI36项分别通过。指定测试环境照片切片已验证；完整M5-5/M8-6仍PARTIAL，不标DONE。历史云端NOT_RUN保留原版本边界，见[验证记录](verification.md)。

## 接口与状态

实际契约为`contracts/local-api.openapi.json` V0.6，所有JSON响应沿用`{data,trace_id}`或统一错误信封；照片就绪使用typed投影。

|接口|当前代码行为与门禁|
|---|---|
|GET /api/v1/files/policy|当前授权成员读取；JPEG/PNG白名单、5_000_000 bytes、3张；未配置或无效白名单为null，上传422 CONFIG_PENDING/Q28|
|POST /api/v1/files|仅multipart一个file及purpose=customer_report_photo，CSRF/Idempotency-Key；新受理需存储可用与照片结构齐备。202前原图bytes/file/job/operation/审计/重放结果同PG事务；同键原请求重放不再次PUT|
|GET /api/v1/files/{id}|未绑定文件仅上传人可读；绑定本人登记后客户本人可读，内部角色按当前授权租户读取已绑定文件；PENDING/READY/FAILED，不公开object_key、桶、暂存bytes或秘密|
|GET /api/v1/operations/{id}|区分报障CUSTOMER_REPORT_REGISTRATION门禁和FILE_UPLOAD；GET不领取任务或重新提交|
|POST /api/v1/files/{id}/content-grants|空JSON对象、CSRF/幂等；仅READY签发15分钟持久平台grant，绑定actor/tenant/file/permission_version|
|GET /api/v1/files/{id}/content?grant=UUID|当前会话与grant联合校验；实际GET核验原图size/SHA，读前后重新授权；no-store/nosniff/inline。格式不符400，不可见/过期/撤权404|
|POST /api/v1/tickets/{id}/attachments|expected_version及1–3个不重复UUID，CSRF/幂等；仅报障人追加同租户、同上传人READY图片，原登记累计最多3张，冲突409；历史动作ADD_ATTACHMENTS|
|GET /api/v1/health/ready|PG/OIDC及已配置照片策略、V5结构/ACL、MinIO真实探测；照片enabled/ready/policy_ready/schema_ready/storage_ready全true才就绪，失败503及安全原因码；production_ready=false|

FILE_UPLOAD初始PENDING/QUEUED，领取后RUNNING/UPLOADING；存储核验成功才SUCCEEDED/STORED且file为READY。依赖失败保留耐久任务PENDING/RETRY_WAIT；授权变化FAILED/AUTHORIZATION_REVOKED；已有对象摘要不符FAILED/STORAGE_CONFLICT。进度为null，不由浏览器计算假进度。报障operation仍PENDING/AWAITING_TICKET_CONFIGURATION，正式state/priority/SLA截止仍null；图片成功不能解除这些门禁。

## 原图、身份与并发

每张限1..5_000_000 bytes，只接收JPEG/PNG。扩展、声明类型及真实内容须一致；未声明MIME或application/octet-stream按服务端内容核验。原图进行签名、结构及完整解码检查，尺寸≤16_384、像素≤25_000_000属于技术资源防护，文件名≤200个Unicode字符并拒绝路径、控制字符及不安全名称。原bytes不重编码，SHA绑定受理及回读内容。解码不代表病毒扫描、EXIF去隐私或客户生产批准。

扩展名必须有位于首尾之间的点号，png/jpg/jpeg无点号文件名一律拒绝。JPEG检查以首个结构EOI结束原图，拒绝尾随junk或拼接JPEG；APP内marker样字节不会被误作结束。历史56个JDK策略断言保留合法baseline/progressive原bytes/hash，见[云端策略证据](evidence/cloud-photo-policy-verification.json)；本批完整JUnit与真实HTTP另留证。合成TEM/DNL样例仍被完整decoder拒绝，不宣称额外codec支持或实机兼容性。

客户viewer/tadmin只能上传本人照片及追加本人报障。未绑定file/FILE_UPLOAD operation仅上传人可读；内部msp/padmin只读当前授权租户中已绑定登记的文件，不读取其他人的未绑定上传。跨租户及同租户无关客户统一404。权限版本在事务内重新核查，已受理重放、Worker及grant不能绕过撤权；浏览器不持有S3账号或存储直链。

报障可先无图登记，照片失败不撤销已受理记录。前端当前页面保留原File、原multipart幂等键及原请求；网络结果不确定时手动同请求重试，已收到202后只GET回读。追加请求保留原ticket版本、照片列表与幂等键；409显式刷新并比较后才能形成新请求。切租户、会话变化及离开页面取消请求并释放预览/原图Blob，不把图片或业务记录放入localStorage。

跨登记详情会在抽屉和主列表明确显示保留照片所属登记号/标题，可返回原登记处理或安全放弃本地选择。上传中、结果不确定、202未终态回读或追加结果不确定时禁止移除/放弃，保留原File、键、版本和目标；结果确认后才可清队列为新登记选择。新登记表单只GET完整校验operation及关联detail：tenant/reporter/operation_id和CONFIG_PENDING门禁一致后才展示与完成，异常不触发再次登记，也不渲染未经校验的error。

有本地照片的登记回读只刷新列表并暂存详情ID；表单关闭后才打开抽屉，保留表单内补传控件可达。无照片登记仍即时展示详情；上下文重置清除待展示ID。此遮挡由真实UI发现并修复，正常控件回归通过。

附件写入先锁ticket，再按稳定文件ID顺序锁定照片；验证同租户/上传人/READY、未绑定其他登记、现有数量和CAS，关系/版本/history/audit/重放结果同事务。已知设备、同报障人、同分类30秒语义去重带新图片会追加原登记，不覆盖原正文及联系人；无新照片保持原记录。HTTP幂等与语义去重独立；未知设备及跨报障人共享去重范围仍待确定。

## 照片Worker与存储

Worker仅轮询PG照片任务，不消费报障outbox、RabbitMQ或其他业务。Windows以`deploy/start-dev.ps1 -WithPhotoWorker`显式启用`remote_test`及独立Worker，限定项目运行账号、loopback PG及项目MinIO。启动检查V5结构/ACL、策略、存储及Worker启动信号；条件不齐拒绝运行。`stop-dev.ps1`核验拥有者记录后停止本项目API/Web/Worker，PID复用或路径/启动时间失配时保留记录并拒绝停止。每实例串行轮询不表示已完成全租户调度治理。

任务使用PG时钟、120秒租约及单调fencing token，重启后可接管到期任务。领取、调用存储前及完成事务复核当前上传授权；完成/失败/重试写回校验owner/token/未过期租约，旧Worker不能提交接管后的结果。依赖重试使用技术退避并保留任务；未知PUT结果通过实际对象回读核验，不盲目覆写。

key为`photos/<tenantUUID>/<fileUUID>`，条件创建；已有对象只有真实size/SHA吻合后认领。使用现有MinIO受限账号，无DeleteObject或生命周期修改权限。桶存在检查不证明全部IAM或匿名访问约束，仍须真实组件验证。暂存bytes、对象及grant没有已批准清理规则，不能擅自清除。

API与Worker启动不执行Flyway。独立`deploy/migrate-test-schema.ps1`默认Check，显式`-ApplyPhotos`才以迁移身份执行V5；需先完成根工程编译/安装及`prepare-migration-classpath.ps1`，停止本项目应用。V1–V4历史/校验和不符、构建资源过旧或结构/ACL不符时拒绝。V5已Apply一次、再次Apply为0DDL，SQL冻结SHA-256：`5A84F62BDAAB9BADFD5D87F4D30AE0AC1F9C1AE2F48D0D84E0635485A7BBDC55`；后续变更新增迁移。原target4基线测试不是V5执行入口。

## 验证与剩余门禁

本批现存Surefire核对：96纯单元/隔离、43指定PG（照片24+基线19）、3指定MinIO通过，fixture13项跳过，0失败/错误。前端全量113项及V0.6契约8项通过；遮挡修复后operation41+workspace3项定向回归、类型和最终构建通过，44项包含于113项，不重复累加。最终主包464.09kB、Tickets54.49kB、Configurations49.84kB，无包体警告。见[构建摘要](evidence/local-photo-build-summary.json)及[本地接续验证](local-runtime-verification.md)。

SDK提交eba5630的CI历史见[安全步骤摘要](evidence/github-eba5630-verification.json)。本批照片提交`f56ad1b`的[独立GitHub CI](https://github.com/yangyour/zhonganzaixian/actions/runs/36846410035)已实际回读，契约/Web、纯Java和后端编译/单元/打包三个job及28条步骤全部成功，见[照片提交摘要](evidence/github-f56ad1b-verification.json)。CI不连接私有组件，本批真实PG/MinIO/HTTP证据独立记录。

后端全部模块已实际编译与打包。工单详情/列表使用只读REPEATABLE READ快照回读版本、历史和附件；交错回归包含在24项照片PG测试中。主查询复核当前成员及权限版本，随后撤权不追溯取消已授权的此次读取。

四个身份的80次基础HTTP全部通过并经V0.6 Ajv校验，真实Worker完成原图存储、grant/hash回读，见[HTTP证据](evidence/local-report-photo-http.json)。[52次专项HTTP](evidence/local-photo-resilience.json)通过5_000_000 bytes原JPEG接收/5_000_001拒绝、耐久API/Worker重启、项目MinIO转发503/恢复、成员撤权及旧授权拒绝，finally恢复。该脚本断链前已停Worker，其running worker RETRY_WAIT=NOT_RUN是历史单脚本边界，不能改写。

另[25次运行中重试HTTP](evidence/local-photo-worker-retry.json)独立通过：ready200正常启动后仅断本项目MinIO通道，真实PENDING/RETRY_WAIT和UPSTREAM_UNAVAILABLE/retryable=true；单一PG快照确认lease已释放、next_at在未来、file PENDING/version2、attempt1/token1及重试审计。移除本次marker后同PID/startTime的Worker按退避自然完成同job/file/operation为SUCCEEDED/STORED/READY，原图hash一致。只对新虚构任务next_at执行两次各1行CAS以控制观测，不修改lease/token/attempt/status；finally解除hold、恢复marker、三进程ready200及四会话。三个HTTP脚本分别计数，不累加为一个脚本；V5、IAM、全局配置及成员未因本专项改变。

[照片UI36项](evidence/local-report-photo-ui.json)通过：Edge154.0.4258.48、持久Playwright无头、1440×900和390×844手机仿真，正常控件操作、0 pageerror、366px表单/长名换行且无水平溢出。覆盖先登记后补传、授权PNG下载/hash、真实route.fetch提交后丢响应再用同File/key或同追加key/body/version/target重试、两页真实CAS409→比较→重试、三张已存配额、跨A/B队列保留/放弃、策略失败重载、租户A/B/A及注销旧会话401；另有50秒探索。截图仅在Git忽略的`.local`并已逐张检查；未执行物理相机、企微嵌入身份或全浏览器矩阵。

JPEG/PNG是用户开发方向，策略保持USER_DEVELOPMENT_DIRECTION、UNSCANNED、生产UNVERIFIED、retention_until=null。Q28扫描/隔离及其他文件策略、Q10保留/清理仍待客户正式确认。Q07剩余状态、Q08日历、Q09岗位资格、Q14优先级/通知、Q16档位继续保留。

M5-5还要求手机拍照、10秒内工单号、值守30秒企微推送及原图；M8-6要求真移动拍照到提交≤30秒、工单号与按档位预计响应时限。没有真手机/企微及完整规则证据，不能把照片切片标为两项全部DONE。原128个合同TC保持NOT_RUN，后续逐功能验证并更新证据后提交推送。
