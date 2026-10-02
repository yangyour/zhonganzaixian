# 客户可见评论：核心规则切片

对应M5-7、M8-7和D07；与现有INTERNAL备注独立，不能把内部备注转换或混入客户评论。当前只新增纯领域值/访问规则和后续真实仓储接口，没有数据库表、公开API、页面、消息推送或ITSM同步。API仍0.16.1，现有能力列表不扩大。

文本按技术边界接受1至2000个Unicode码点，保留原始前后空白、换行与制表；拒绝全空白/FEFF、无效代理字符、其他控制字符，精确UTF8 SHA256不归一。该上限是安全技术边界，不代表客户已批准业务规则。

访问规则只接受后续同一真实授权事务读出的成员、当前权限版本、父租户及报障人事实：inactive/空主体/过期权限401；跨租户或其他客户404；未知角色403；报障关系不可用503。viewer/tadmin只能访问本人报障，msp/padmin限当前授权租户；Q09客户之间共享不被此规则放开。

作者类型在明确追加时冻结为REPORTER或ENGINEER，公共标签固定“报障人”或“工程师”，不取内部真实姓名。未来返回Entry本身不含actorId/name；真实操作者仍须在私有持久记录和审计中由当前会话归属保存，不能由请求字段指定。仓储接口要求有界只读分页、受保护幂等事务、父版本核对、评论及审计同事务；不推进父业务状态、版本、SLA或客户确认。

8项纯规则测试覆盖原文/hash、不归一、2000/2001emoji、空白/控制/代理字符、本人/内部角色、过期权限/未知角色/跨租户、缺关系/缺主体/缺父上下文、公共标签与Entry字段白名单。串行根634报告=621执行（其中165为已有功能真实PG回归）+13历史fixture跳过，6MinIO排除，0失败错误；既有PG测试不能作为评论持久化证据。30契约一致性通过，前端未改。

后续需新建V11、独立显式执行和冻结，再实施真实PG授权/不可变追加/审计、公开API及页面。V1–V10保持原字节；API/Worker不自动迁移。评论是否同步外部ITSM须单独受控，不把未知组件或Q07确认视为已完成，不使用fixture替代真实联调。完整M5-7/M8-7仍非DONE。见[核心证据](evidence/niko-ticket-comment-core-20261002.json)。


## 2026-10-02 V11独立结构已执行并冻结

V11新增独立mop_ticket_comment，不改V10内部备注。16列含私有actor_id、固定author_kind、原文/精确UTF8 hash、父版本、创建时间及REPORTER_AND_INTERNAL/MANUAL_TICKET_COMMENT/SELF_REPORTED标签。14个CHECK保证技术长度/空白控制符/摘要、允许作者类别和明确未改变工单/SLA/未同步ITSM；tenant/父/作者复合FK，创建时间/id分页索引。SQL仅约束结构，不能证明REPORTER确实是报障人或ENGINEER具有当前权限，后续真实仓储必须同事务重查关系、角色、权限版本、父版本并写审计。

运行角色仅SELECT/INSERT，不得UPDATE/DELETE/TRUNCATE，PUBLIC无访问；没有增加父工单授权。显式--apply-ticket-comments要求已验证V1–V10，默认--check只读，源和编译资源hash先验证，再加载私有设置。API/Worker不执行迁移。

niko真实PG已执行check0→apply1→重复0→check0，旧--apply-internal-notes也是0，结构和ACL逐次验证。V11 SHA256 d8535bbf13ca9e35c115278f36232a786b4562c99cc9afd9f5195a416028a8e3，自此V1–V11字节冻结，结构修正必须新增V12或更高，不能因应用测试失败重写已执行脚本。

7项真实PG schema测试覆盖原文/hash和父行摘要不变、跨租户FK、错误hash/父版本、空白/控制/2000与2001emoji、运行角色不可变权限/PUBLIC无访问、错误可见性/伪造状态或ITSM标签、两种作者类别及未知类别拒绝。测试写入全部回滚，另行只读核对新评论表0行；不是实际客户评论写入/业务仓储证据。12迁移策略、49 Python守卫通过，根642=629执行（含172真实PG）+13skip/6MinIO排除，0失败错误。

V11迁移及schema验证首轮通过；原6私有配置、既有组件版本/账号、原服务器不变。API仍0.16.1，评论API/仓储/页面与外部同步仍待，不标M5-7/M8-7 DONE。见[V11证据](evidence/niko-ticket-comment-schema-20261002.json)。


## 2026-10-02 真实PG评论仓储

PostgresTicketCommentRepository每次同快照核验活跃用户/成员、当前权限版本、授权租户及报障关系：viewer/tadmin只允许本人父登记，msp/padmin仅当前租户，其他客户/跨租户/错父404。客户的父行查询先带reporter条件，不对其他人的父登记加锁后才拒绝。只读REPEATABLE_READ完成授权、父版本、评论与摘要/标签核验，至多101条创建时间/id分页，无全量或fixture回退。

写入必须在已有受保护幂等事务，成员与可见父记录共享锁、expected_ticket_version核对，然后追加不可变评论、真实actor审计及原请求收据。新PlatformRepository.idempotentAuthorized在读缓存收据之前执行当前资源授权回调，防止把旧收据当作绕过现有可见性的通路；评论调用者必须使用该入口及authorizeCurrentAccess，不能仅在实际写入lambda内检查。回放授权检查不强制旧父版本等于当前版本，原收据与后续父版本变化分开。既有idempotent入口继续原行为，历史fixture不提供新授权入口。

作者类型从受锁的真实成员角色确定并固化；返回Entry不含actorId/name，只有REPORTER/ENGINEER及固定公共标签。V10内部备注始终走独立仓储，不混入评论或原客户ticket DTO。新评论不改父登记/版本/SLA、不生成ITSM同步或通知事实。读取发现持久标签、原文hash或父版本不一致返回503，不冒充可信内容。

16项真实PG首轮全部通过：独立连接原文/hash、本人及双内部角色/其他客户隔离、内部备注不泄漏、父DTO/版本不变、原键重放/冲突、父CAS与未保护事务拒绝、错父/跨租户、权限版本、整笔回滚及原键恢复、同原键两连接仅一条评论/审计、不同作者并发保留两条、分页/2000emoji、成功读不追加审计、单连接池无嵌套借用、关闭单独真实连接池后明确503、缓存收据前资源守卫仍执行。最后一项用不存在父资源的真实PG守卫拒绝验证，没有修改任何账号角色来假称撤权演练。

根658=645执行（含188真实PG）+13历史fixture跳过、6MinIO排除，0失败错误；30契约不变。schema阶段的0行是当时全回滚后的历史核验，现在仓储测试已有明确合成评论提交，不能继续说新表当前为空。V1–V11不改，公开评论API/页面尚待，尚未真实HTTP/浏览器验收。见[仓储证据](evidence/niko-ticket-comment-repository-20261002.json)。


## 2026-10-02 公开评论API V0.17

GET/POST /tickets/{ticketId}/comments和GET .../{id}已接真实仓储。四种既有角色取得明确comment读/写能力，但客户仍只限本人报障，内部限当前授权租户；其他客户/跨租户/错父404。POST只接受expected_ticket_version与text，32KiB正文/重复键/尾随JSON/Unicode和父版本严格校验；不接受actor/visibility/author_kind/附件/ITSM同步字段。CSRF、原键/原body与版本边界保持，缓存收据前也必须先通过当前资源授权回调。

响应仅包含固定作者类别及报障人/工程师标签，不含结构化内部actor ID/name；自由文本保留作者主动提交的原文，不把内部备注转换为评论。immutable=true、状态/SLA不变，itsm_applied=false、itsm_sync_status=NOT_REQUESTED、itsm_sync_available=false；没有消息送达或外部同步声明。只读分页至多100、配对创建时间/id降序；没有PATCH/DELETE。ready实际检查V11结构及运行ACL，失败503，live不探测；API/Worker不迁移。

10控制器+1就绪新增；最终根669=656执行（含188真实PG）+13历史fixture跳过、6MinIO排除，0失败错误；32契约/298Web/类型和生产构建通过。首次单元编译因新测试残留旧幂等方法签名失败，修正到新授权入口后23项聚焦测试通过；原ready契约样例未带新字段导致1断言失败，补齐样例后32通过，均保留原日志。

首轮84次真实PG/OIDC/API检查全部逐响应契约通过（无PATCH/DELETE端点的405使用共同Error schema）：6身份、本人和内部读写/其他客户与跨租户404、明确作者标签/内部备注隔离、CSRF/字段/正文/分页门禁、原键旧收据与冲突、父CAS、同键并发2响应1行、2000emoji及10+2分页。主父登记最终14评论/14成功审计，其他报障人另1条；真实actor只留内部审计，回放无重复成功审计，原父状态/SLA/版本和客户DTO不变。成功只读查询不追加审计；拒绝请求安全审计保留。

见[API HTTP证据](evidence/niko-ticket-comment-api-http-20261002.json)及[汇总](evidence/niko-ticket-comment-api-20261002.json)。仓储d01d0ab及CI37078043122成功。当前公开API完成的是本地手工评论切片，评论页面/实际浏览器/通知/附件/ITSM和完整M5-7/M8-7仍待，不能标DONE；V1–V11不改。
