# P02 附件隔离实际调用链接续

## 已实现，尚未真实联调

恢复 WIP `678a5a16be0cb784662b5abf62225a597e0c61b3`，接入 PostgreSQL 追加日志、附件安全 API、原图授权/读取、照片面板和显式单次扫描 Worker。存储 READY 不代表安全；初始无记录视为隔离。CLEAN 仅为有效扫描证据，仍须授权成员明确 RELEASE。公开 API 不接受扫描 verdict。

- GET `/api/v1/files/{id}/security`：租户对象授权、规范 UUID、拒绝 query、返回前完整成员复核、明确 snake_case/ISO 时间。
- POST 同路径 `/actions`：严格动作/版本、单个幂等头、已有 CSRF、授权重放、乐观锁、追加审计；跨租户对象 404。
- 原图 grant 的首次与幂等重放均复核 RELEASED；存储读取前后复核当前版本/授权，撤回后旧 grant 不可绕过隔离。
- 原请求先保存在当前浏览器会话、账号/租户/权限/文件隔离的最小日志中，才发送 POST。未知结果重进页面保持原 key/body；回执必须是期望版本加一及对应动作状态。拒绝重放不能证明之前未提交，继续保留原请求。存储失败不发起不可恢复写入。
- 未确认时阻止主动跳转/上下文切换、提示浏览器离开、禁用移除对应照片。强制撤权取消请求并清空可见状态，迟到响应不恢复内容；刷新期间撤回已展示的原图 URL。
- API/Worker 运行时固定 public，不能通过环境变量改成无守卫测试表。队列/业务事务检查结构、受限 ACL、复合外键及精确触发器函数体。集成测试旧隔离 schema 构造器仅用于显式测试，不是运行配置。

## V52 候选与协调

已 fetch 并核对 47 个远端 ref 的迁移谱系；V51 原字节 SHA256 保持 `51f26c2d0d74903a10ea04525335fff8abfa8eecf1e048e1a623a535e609d37b`。远端 main `91996973` 仅登记 V52 预约，没有应用 DDL。

候选 `V52__attachment_security_journal.sql` SHA256：`ca8140bb6f6958408a7683d41ccb9ba0a03657bcfe3fb76f3f6fede361d103ef`。未执行，因此不是已冻结迁移。追加日志锁定当前成员和文件；版本严格递增、扫描请求唯一、审计时钟由数据库产生；CLEAN/RELEASED 绑定原文件哈希、扫描请求、引擎/签名、策略哈希、有效期。运行角色只有 SELECT/INSERT，禁止变更旧决策。

显式迁移工具识别 `--apply-attachment-security`，必须完整 V51 前序并通过结构核验。API/Worker 不迁移。未来执行前再次核对远端版本和实际共享环境协调，预约文档或本地锁都不能替代跨主机协调。不向活动 mop_test 应用候选。

## 配置，默认不启用

通过私有运行配置/环境变量提供，仓库不保存连接秘密：

- `MOP_ATTACHMENT_SCAN_ENABLED=false` 默认；显式 true 时缺字段直接失败。
- `MOP_ATTACHMENT_SCANNER_PORT`：已有本机环回 ClamD 端口，无默认服务或安装行为。
- `MOP_ATTACHMENT_SCANNER_ENGINE_VERSION`、`MOP_ATTACHMENT_SCANNER_SIGNATURE_VERSION`：明确允许的版本。
- `MOP_ATTACHMENT_SCANNER_SIGNATURE_VALID_UNTIL`：明确 UTC 截止，过期不能放行。
- `MOP_ATTACHMENT_SCAN_EVIDENCE_SECONDS`：明确证据有效秒数，1–86400，无猜测默认。
- `MOP_ATTACHMENT_SCAN_TENANT_ID`：显式单次任务租户，未设置不创建客户端。

Worker 参数 `--attachment-scan-once` 每次最多处理一条已有申请，读取存储后、调用扫描器前再次核对原队列身份，结果写入时再核对；从不自动 RELEASE。现有指定服务器若未配置受控环回扫描服务，记录组件依赖，不另起替代服务。引擎/签名策略需真实服务核验，不把手工配置等同验证。

## 测试及边界

证据见 `docs/evidence/dot-continuation-20261023/p02.json`。首次迁移测试因脚本集合仍停留 V51 失败，新增 V52 期望项并保持原序号检查；新增守卫测试最初误放在无 Mockito 依赖的 persistence 测试模块，移至现有 api 测试模块，不修改产品依赖或放宽断言。HTTP 测试配置使用独立测试 profile，避免污染应用启动测试。

pglast 8.7 解析候选 9 条 SQL 和 2 个 PL/pgSQL 块通过，仅语法检查，不替代数据库执行/权限/并发实测。ClamD 传输替身、内存浏览器存储和 jsdom 是单元/交互模拟，不是真实联调或浏览器验收。

指定 PG、MinIO、ClamD、真实授权浏览器缺受控配置/信任资料，NOT_RUN；旧隔离 schema 集成测试还需在已协调的指定测试环境升级为完整 V52 公共 schema 实验，不能拿旧表测试替代新迁移验证。凭据轮换/KMS 和审计留存其余 P02 范围未完成。未部署、未扫描用户附件、未外发、未变更活动数据库。
