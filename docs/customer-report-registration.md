# 客户报障持久登记与查询

历史本地登记实现日期：2026-10-01。本页保留D07持久登记切片的真实OIDC/PG/HTTP及浏览器证据，不能算本轮云端新增代码通过。云端接续照片输入、附件绑定/补传及平台原图访问代码，见[照片代码切片](report-photos.md)；V5未执行、真实联调未完成，不写完整工单验收通过。

## 开发决策与当前边界

用户于2026-10-01明确选择“一期本平台工单为权威”。本轮据此使用`authority_source=MOP`，不再以缺少外部ITSM作为本平台报障登记的前置阻塞。这是已确认的开发方向，不自动代表客户正式生产岗位、完整状态图、SLA规则或合同全量验收已获批准。

收到HTTP202只表示“报障已耐久登记，正式工单规则待确认”。记录使用技术登记号`REG-<完整UUID>`，不制造外部工单号。`registration_status=REGISTERED`与正式业务状态分开：`authority_status=RULES_PENDING`，`state/priority/response_due_at/recovery_due_at=null`；页面显示“— · 未设”，不默认P1、正常或固定响应时限。`external_id/external_state=null`、`sync_state=NOT_APPLICABLE`，没有外部建单或同步阶段。

当前不提供接单、转派、解决、客户确认、重开、工时或正式SLA时钟。D07完整闭环与128项逐条验收继续推进，不能因本切片持久化或内部测试通过标为全部完成。Q07剩余状态与处理规则、Q08日历/时限、Q09正式岗位与资格、Q14优先级映射/通知矩阵、Q16服务档位仍显式保留。

## 输入与身份范围

|字段|本轮行为|
|---|---|
|source|固定`customer_report`，客户不能选择manual/incident或代填报障主体|
|category|网络不通、网速慢、WiFi问题、设备异常、其他|
|asset_id|可省略表示“不确定”；有值时仅从当前可见资产选择，服务端再次检查租户与对象归属|
|impact_scope|company/partial_area/individual，分别为全公司/部分区域/个人|
|description|必填，1–3000个Unicode code point；允许正常换行与制表，拒绝其他非法控制字符或孤立代理字符|
|title|可省略，由服务端按分类与设备生成；有值时1–200个字符|
|contact_name|必填，1–100个字符|
|contact_phone|必填，1–64个字符；仅数字、空格、+、-、括号，至少含一位数字|
|urgency|normal/urgent必选；“很急”仅供参考，不直接设置优先级|
|attachment_ids|可省略或空数组；新增代码可绑定同租户/同上传人READY引用，最多3张且不可重复；V5及实际组件未就绪则明确失败，无图登记不受影响|

除描述允许的换行/制表外，文本字段拒绝控制字符与孤立代理字符；技术长度限制不替代客户待确认的正式表单规则。联系方式仅作为填报信息，`contact_binding_status=UNVERIFIED`，不冒充已核验联系人或决策人关系。

报障人与租户取自真实OIDC会话及当前成员关系，不接受客户端指定tenant_id、reporter_id、priority、state或完成时间。`session.user.role`按当前租户membership投影，capabilities由同一成员岗位计算；用户全局展示记录不授予当前租户岗位。

`viewer/tadmin`的`tickets:report`允许本人报障，`tickets:read`仅返回本人的登记及关联operation；`msp/padmin`按当前授权租户只读查询，不能代客户提交或执行尚未实现的处理动作。跨租户或不可见对象统一404，错误不区分“存在但无权”与“不存在”。本轮权限切片不等同于客户正式岗位矩阵获批。

## 接口与客户端流程

历史47次HTTP证据对应实际契约V0.4。当前`contracts/local-api.openapi.json`已扩为V0.5照片代码契约，仍使用`/api/v1`、`{data,trace_id}`与Cookie/CSRF；不能将旧HTTP响应核验记作新增V0.5照片联调通过。

|接口|实际结果与边界|
|---|---|
|POST /api/v1/tickets|带Idempotency-Key；202返回`operation_id`与`status=PENDING`，仅代表持久登记|
|GET /api/v1/tickets|按cursor/page_size/category/urgency/q查询授权集合；返回items/next_cursor/has_more及PostgreSQL来源|
|GET /api/v1/tickets/{id}|回读登记内容、未核验联系方式、未设状态/时限与REGISTER历史|
|GET /api/v1/operations/{id}|回读当前授权可见的登记操作；不触发Worker、外部建单或重试|

登记operation保持`kind=CUSTOMER_REPORT_REGISTRATION`、`status=PENDING`、`step=AWAITING_TICKET_CONFIGURATION`、`progress=null`。其错误说明为`CONFIG_PENDING`、`retryable=false`，明确列出Q07/Q08/Q09/Q14/Q16。等待规则不转成SUCCEEDED，不计算假进度，也不因前端超时重发另一笔有副作用的请求。

前端收到202后只GET operation一次，并严格接受`result_ref=/api/v1/tickets/<UUID>`；提取UUID后调用本应用的详情接口，不导航任意URL、不重复拼接API前缀。不持续轮询，也不把待确认条件当作再次POST的理由。查询失败时保留已收到的登记回执，只允许手动重查结果。

未确定是否登记成功的网络错误会保留原填写内容与同一个幂等键；手动重试使用原body与key，不能静默更换键产生两条记录。403/409/422保留表单并显示错误码、trace_id与待确认项。未确认请求只保存在当前页面内存，业务数据不存localStorage/sessionStorage。

同一租户、同一报障人、已知设备、同分类30秒内重复提交复用既有登记及operation。新增照片代码在去重命中时追加新READY图片，累计3张/CAS/history ADD_ATTACHMENTS/审计同事务，仍保留原描述及联系信息；并发及真实联调尚待验证。未知设备和跨报障人共享去重仍待确定，不擅自合并不同人的报障。

列表筛选、游标和详情ID放在URL，刷新后恢复查询条件；切租户清除旧对象/游标/弹窗与内存内容，取消旧请求并拒绝迟到结果。分页10/15/25/50/100为当前测试接口支持值，100标记为`test_technical_limit`，不宣称客户批准了全平台生产上限。

## 持久化与组件复用

追加V4迁移，新增租户联合定位的ticket、REGISTER history、operation与outbox登记意图，复用指定测试环境PostgreSQL、真实Keycloak会话和既有事务幂等/审计。新登记、单次历史、operation、outbox、成功审计及幂等响应同事务提交；失败整事务回滚，同键重放先复核当前成员权限。

历史V4对四张登记表仅SELECT/INSERT，无UPDATE/DELETE。V5草稿为照片追加及任务增加受限列UPDATE，尚未执行；正式处理命令仍未实现，不能手改状态绕过流程。V1–V4不可修改，API与Worker启动均不自动迁移。

报障outbox仍`CONFIG_PENDING`、`next_at=null`，没有RabbitMQ投递或通知送达。新增消费者只处理照片PG任务，不能当作报障outbox/消息服务已接入；租约恢复及MinIO业务联调尚待执行。

可以先无图登记；照片失败不取消原记录。新增上传、补传、授权原图及前端代码尚待真实验证，Q28扫描/隔离与Q10保留清理继续保留；不能宣称M5-5/M8-6拍照全流程已验收。

## 已执行验证与证据

以下结果来自本轮指定组件检查与真实HTTP/UI操作，不与旧fixture结果合并。全量检查先执行22项Java纯单元与19项真实PG集成；随后会话岗位投影修正补跑2项纯测试，并重新打包成功，共43项后端测试。补跑没有冒充重新执行PG集成。

|检查|当前结果|
|---|---|
|Java纯单元与指定PostgreSQL集成|24项纯单元、19项真实PG集成通过，共43项；历史fixture 13项默认跳过|
|当前租户岗位会话投影|2项纯测试通过；真实HTTP降级、原Cookie报障/回读及管理员接口拒绝均通过，finally恢复成员且权限版本单调增加|
|前端|21项单元、类型检查与构建通过；主包462.32kB，无包体警告|
|契约|3项检查通过；实际契约V0.4|
|真实OIDC/HTTP报障流程|4个角色完成真实Keycloak登录；47次HTTP检查全部通过，响应按实际V0.4契约经Ajv验证|
|1440px/390px真实浏览器流程|16类控件/场景通过；客户登记/回读、筛选分页、内部只读、租户切换、已受理GET重试及提交响应丢失后同键同body重试；0 pageerror，无水平溢出|

公开脱敏证据：[汇总](evidence/customer-report-verification-summary.json)、[47次HTTP与契约检查](evidence/customer-report-http-verification.json)、[成员角色真实HTTP验证](evidence/membership-role-http-verification.json)、[浏览器验收](evidence/customer-report-ui-verification.json)。截图保留在本机Git忽略的`.local/qa-customer-reports/`。只记录环境、版本、行为与计数，不记录密码、Cookie、CSRF/OIDC token、完整含密URL或报障联系信息。正式状态/SLA/岗位、图片与其余D07要求仍未完成。

复现基础检查按README准备被Git忽略的私有环境配置，执行`deploy/check.ps1`，继续复用指定PostgreSQL/Keycloak；组件连接失败即失败，无fixture或本机服务回退。启动真实本地应用后进入`/tickets`，使用本项目真实测试身份验证客户本人记录与内部只读投影。

专用HTTP检查先为viewerA、tadmin、msp、viewerB逐一执行`node tools/capture-test-session.mjs <角色>`，将生成的私有`qa-session.json`复制为`qa-ticket-<角色>.json`，再执行`node tests/customer-report-smoke.mjs`。每次创建有MOP-FAKE标识的独立项目虚构数据，不清除其他记录。成员投影验证先捕获reviewer到`qa-ticket-reviewer.json`，按README的Python依赖设置执行`tools/verify-membership-role-projection.py`；它只临时修改该测试成员的fixture-A岗位，并在finally中按版本恢复。
