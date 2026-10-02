# D05 原始资产来源、解析选择与15列映射

更新：2026-10-02。本记录描述代码切片与当前验证边界，不把 M3-6 或全部 D05 标为完成。原文件存储、技术解析、映射保存与最终资产导入分别计状态。来源仍 `business_status=CONFIG_PENDING`、`can_import=false`，有效规则版本和配额占用均为 null。

## 接口与授权

实际代码契约为 [local-api.openapi.json](../contracts/local-api.openapi.json) V0.8，由 [生成器](../tools/build-api-contract.mjs)生成。JSON 使用 `{data,trace_id}`，错误沿用统一信封。六个接口都要求当前成员有 `assets:write`，角色为 padmin/msp/tadmin；viewer 禁止。来源、操作及原始预览仅当前租户的来源创建者可见，同租户其他管理员也不可读。跨租户、跨创建者及不存在的对象统一404。

|方法与路径（`/api/v1` 下）|输入|结果与边界|
|---|---|---|
|GET `/assets/import-policy`|无查询参数|200 技术政策与合同15列；不表示依赖或最终业务资格已就绪|
|POST `/assets/import-sources`|multipart 恰好 `file`、`purpose=asset_import_source`、`format=CSV或XLSX`|202 `{source_id,operation_id,status:PENDING}`；未知/重复 part、JSON/base64、客户端主体禁止|
|GET `/assets/import-sources/{id}`|无查询参数|200 一致 PG 来源及当前 `parsed_artifact/mapping`，不返回桶、对象 key 或凭据|
|POST `/assets/import-sources/{id}/parses`|扁平 `expected_version,declared_format,encoding,delimiter,sheet_index,header_row`，全部显式出现|202 同一来源的 PARSE 操作；XLSX 缺 sheet/header 只生成目录|
|POST `/assets/import-sources/{id}/mappings`|`expected_version,source_parse_hash,target_source_columns,ignored_source_columns`|200 完整来源 DTO；保存原始映射和 preview_hash，`precheck_status=NOT_RUN`|
|GET `/asset-import-operations/{id}`|无查询参数|200 独立 STORE_RAW/PARSE 状态；GET 不执行任务|

写接口要求 CSRF、有效幂等键、当前成员权限版本及事务内重新授权。解析与映射使用来源 CAS；409 必须先回读比较，再手动提交新请求。上传幂等摘要覆盖 purpose、原文件名、声明格式、大小及原始 SHA-256，不依赖 MIME 声明或 multipart boundary；解析/映射对象键递归排序，数组顺序保留。同键同输入复用已受理结果，同键不同输入为冲突。新原文件请求要求精确 `MOP_IMPORT_ALLOWED_FORMATS=CSV,XLSX`、真实 schema/ACL 与存储可用；已有耐久受理的同键重放不重新执行这些依赖检查。固定政策 GET 不代替就绪检查。

400 表示字段校验错误，401 为会话/成员关系失效，403 为动作权限/CSRF错误，404 为对象不可见，409 为版本/幂等冲突，422 为状态禁止，503 为指定组件不可用。所有接口拒绝查询参数。声明格式仍须解析器核验，没有最终资产导入接口。

## 耐久技术阶段

来源在 PG 事务中受理原 bytes、SHA-256、job、operation、审计与幂等结果。STORE_RAW Worker 校验原始摘要，条件写入独立 MinIO 项目命名空间并读回校验。`storage_status=READY` 只表示原 bytes 已存储，解析仍可 NOT_REQUESTED。存储失败显示 FAILED；暂时组件故障进入 RETRY_WAIT，不返回 fixture 或内存仓储替代结果。

PARSE 受理要求 READY，不能已有 PENDING 解析。新解析清空当前 artifact/mapping 引用并增加来源版本，历史 artifact/mapping 保留。Worker 执行与提交前复核授权，使用租约和 fencing token 防止旧任务覆盖新状态。解析结果保存绑定租户、来源、原始摘要、明确参数及 schema 的 parse_hash；XLSX 目录阶段为 SELECTION_REQUIRED，实际行阶段为 PARSED。操作 result_ref 为固定平台来源地址，终态、来源和 artifact 必须一致。

CSV 明确选择 UTF-8、UTF-16LE、UTF-16BE 或 GB18030，分隔符为逗号、分号或实际 TAB；表头是1起逻辑记录号，另保留物理行范围。XLSX 先读取全部工作表与 hidden 目录，再明确选择0起工作表及1起表头；多表和隐藏表不自动选择。单元格保留 lexical/display/kind/style_index/format_code 与 date_epoch，不把 WINDOWS_1900、MAC_1904 或 NOT_APPLICABLE 变成客户批准的业务日期规则。公式、外部链接、宏及加密安全拒绝，不用缓存值代替公式解析。公开失败消息不包含源文本、库异常或路径。

技术上限为原文件20MiB、500数据行、64源列、32工作表、表头行20,000、单元格文本4,096；第501数据行拒绝而非截断。全局 multipart 文件20MiB、请求21MiB不改变照片独立5,000,000 bytes验证。解压/XML/文本总量、解析预算和子进程超时另有保护；这些值不代表生产批准或完整导入 SLA。

映射使用 [领域 Column](../server/domain/src/main/java/cn/mop/domain/assetimport/AssetImportPrechecker.java)的15个标准标签。精确表头匹配只形成建议，重复表头按源列 index 保留，歧义保持待明确。15个目标必须全部显式出现：index 为所选源列，null 为明确未映射；NOTE 必须映射。非 null 源列不能重复分配，忽略与映射集合不交叉，每个当前源列都须映射或显式忽略。映射保存保留原始行号/单元格与 schema，绑定当前 parse_hash 形成 preview_hash，不运行最终业务预检或占用配额。

JSON Schema 检查字段、类型、固定未知状态与技术边界。tenant/source/operation/hash 跨对象一致性、列是否存在和映射集合交叉另由领域/仓储/前端验证，登记为契约 `x-mop-binding-checks` 和 `x-mop-semantic-checks`。Schema 测试不能证明事务或授权联调。

## 前端和运行边界

资产页对允许角色显示来源入口，展示文件、解析选择、原始结果、映射回读及业务待确认状态。没有最终导入按钮或成功资产数量。原 File 保留在内存中，本地 SHA-256 与来源摘要比对。网络结果不确定时固定原文件/参数/body/目标来源/幂等键，不能更换输入或生成新键。202 后只 GET 操作与来源；映射200后继续 GET 来源，确认原 body/hash/原始单元格一致才显示“映射已保存并回读”。

上传、解析、映射及回读共用动作锁。409保留原请求，回读并比较后，由用户明确准备新请求。关闭抽屉保留输入；未确认请求阻止离开资产页。租户、会话或权限上下文变化终止请求并清空，迟到响应不能进入新上下文。业务数据不写 localStorage。

指定 PostgreSQL/MinIO 通过 SSH 转发复用。原始来源用独立 `mop-test-asset-imports` 命名空间及项目前缀受限账号，见[测试存储说明](asset-import-storage.md)。私有配置引用为 `MOP_IMPORT_S3_ENDPOINT/BUCKET/ACCESS_KEY/SECRET_KEY` 和 `MOP_IMPORT_ALLOWED_FORMATS`；Worker 另要求 remote_test、`MOP_IMPORT_WORKER_ENABLED=true`、受限数据库账号与工程内绝对 `MOP_IMPORT_PARSER_JAR`。秘密只读取 Git 忽略私有配置或环境变量，公开记录仅含引用名。API 启动不执行 DDL，也不启动 Worker/解析器。

`/health/ready` 新增 asset_imports：enabled、policy_ready、schema_ready、storage_ready及实际 reasons。已启用但缺策略、schema/运行ACL或存储时503。`worker_activity=NOT_PROBED` 表示 API 没有探测 Worker 进程；依赖 READY 不证明 job 已消费。业务仍 CONFIG_PENDING/can_import=false，扫描 UNSCANNED、生产许可 UNVERIFIED、留存 null。

## 当前证据与剩余验收

|项目|当前记录|能证明的范围|
|---|---|---|
|指定 MinIO 命名空间/IAM|真实通过；[组件证据](evidence/asset-import-component-verification.json)|受限 Get/Put、拒绝删除/越前缀/其他桶/匿名访问；不证明应用链路|
|CSV/XLSX parser|25项隔离测试通过，根工程编译/打包通过；真实独立Worker执行CSV/XLSX解析并由API回读|技术解析与边界；不等于最终资产导入|
|前端|最新14文件172项、类型检查与Vite构建通过|隔离状态保护与纯映射；页面部分证据另列，不能替代组件联调|
|V0.8契约|本地18项 Node/Ajv检查、生成一致性通过；新增8项含正负例|请求/响应 Schema 与固定门禁；不是完整OpenAPI规范认证|
|V6迁移/真实PG事务|Apply1、Repeat0、只读Check0及结构/ACL通过；69项指定PG测试通过，其中新增导入18项|不自动迁移；隔离存储/解析端口不冒充整个真实链路|
|指定Java MinIO|新增3项真实存储与8项隔离安全检查通过；6MiB明确HTTP412拒替换及原始hash保持|大正文条件PUT使用Expect:100-continue；不把SocketException当拒写证明|
|真实Worker/API|CSV70响应/178检查、CSV+XLSX124响应/327检查通过|[HTTP证据](evidence/local-asset-import-http-9da3050c-243e-45e9-9275-9e41818682df.json)；两批有重叠及轮询，不合并为互不重复用例|
|真实页面部分验收|桌面8项恢复检查通过；移动XLSX目录/明确选择至PARSED已运行|[部分UI证据](evidence/local-asset-import-ui-partial.json)；移动重复表头映射及最终视觉验收未完成，COMPLETE后旧操作显示仍待修|

最新保存/验证边界见[Dot交接](dot-development-handoff.md)与[构建摘要](evidence/dot-handoff-verification.json)。解析/映射JSON在构造树前限制16384 bytes、深度4、节点256、字段名64字符。首次完整远端verify的条件大正文测试失败随后已定向修复/复验，不能改称一次完整verify成功。此批代码交由Dot继续，明确保留已知问题和未执行验收。

TC-M3-6与128项合同验收继续NOT_RUN。后续仍需标准xlsx模板、正式字典及资格、真实事实接入、逐行预检、最终入库/并发配额/去重、错误回执、全过程统计审计及500行完整导入≤60秒。Q20九类名称/采集方式等字典与Q31冲突域/日期/规范化规则未被本切片确认；未知值保持null，不以测试字典或历史已解析行纯预检补造结论。
