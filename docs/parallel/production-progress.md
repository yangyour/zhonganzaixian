# production 会话进度

## 用户停止开发后的收尾（2026-10-23）

用户最新要求结束现有开发、整理提交，剩余单文档由集成会话统一编写。本线已停止新增功能，不再申请main集成锁。以下是交接事实，不是P01–P03整项VERIFIED声明，也不改总JSON或128项正式TC。

最终核心代码提交 **`7e05923d423b1d21205e819ecb33d95ac030029e`** 已推送 `codex/launch-production-20261023`，本线未自行合main；验证基线为 `b122896b`，停止后上游新增入口不计入已验证范围。P03 `c2b8f0d7` 已在main `b122896b`。最新未完成对象仍为P01 `b97f437af8875a51323bcfd26f0c07bd8b30fbd2`、P02 `88cf0e3f311181e590a6dc77714018bd3e2f9c27`；工作区业务改动已全部提交或保留在这些stash，无本线运行资源或持有锁，后续不自动继续。

**已完成且单独验证的提交范围**：P01运行账号守卫核心新增 `RuntimeDatabaseGuard`／`RuntimeDataSources`，检查数据库／current_user／session_user、超级用户／BYPASSRLS／建库／建角色／复制权限、DDL权限、迁移角色成员关系及危险系统角色。API、照片／资产导入／联系人／导出Worker、工单自动登记／关闭／SLA提醒／通知中继的10个运行连接入口统一使用受限工厂，失败关闭连接池；现有健康就绪会重新检查角色漂移。旧身份bootstrap及独立迁移入口未执行，生产profile、数据库选型和RLS方案未放开。无新HTTP端点、无未验证管理界面混入核心提交。

**核心收尾验证**：在移走未完成代码后重新 `mvn -B clean test`，4571项发现、3500项实际执行通过、1071项跳过，0失败／错误。最新PG验证在指定17.9上使用真实受限账号和只读连接，1项实际通过，schema历史保持V50，数据库写入／DDL／组件安装为0；实际验证的守卫及工厂源码SHA256与本次提交文件一致。见[收尾汇总](../evidence/gap-p01-runtime-20261023/closing-validation.json)、[实际PG最终记录](../evidence/gap-p01-runtime-20261023/postgresql-final.json)。较早 `postgresql.json`／`postgresql-factory.json` 保留为逐步验证历史；不以旧WIP回归计数替代本次核心计数。

P03有界真实只读执行器提交 `c2b8f0d7`，已按锁合入main `b122896b`；9项单元和3次真实live GET通过，规模验收、恢复及RPO/RTO仍NOT_RUN。本次停止指令之后的P01核心提交只推送本线，交由集成会话决定合并，不再自行main集成。

### 剩余项按总清单ID交接

| ID | 已有／当前状态 | 不必等客户、仍可继续的工作 | 客户或真实环境前提 |
| --- | --- | --- | --- |
| P01 | 已有预检、直接TLS模式、回退工具；本次核心连接／就绪隔离守卫已验证。诊断API及管理面板未进入提交，整项未核销。 | 恢复并完成诊断API／桌面界面实际联调；补完整生产配置与迁移／隔离装配接口、启用流程和失败恢复。 | Q02最终数据库／租户隔离／生产DDL，生产TLS和代理信任、组件／资源批准；不得靠放宽mop_test守卫启用生产。 |
| P02 | 既有扫描传输／轮换协调器保留；附件真实仓储／授权API／前端／单次Worker已写但未完成SQL与真实PG验证，全部stash。凭据业务授权审计、安全留存尚缺。 | 补附件迁移和独立schema测试、隔离发布状态与扫描结果回填完整验证；接入凭据轮换业务权限／审计／消费者重载；补配置化留存与受控生命周期。 | 指定环境扫描／KMS受限接口及核验策略、保留／删除／轮换授权。未配置继续拒绝发布；不能在正在运行服务的mop_test公共谱系直接应用未发布DDL。 |
| P03 | 验证器、证据矩阵及真实只读请求执行已有；P03整项仍未完成。 | 补可恢复的隔离演练执行与数据／权限／对象／密钥恢复核验，业务负载／故障执行能力及逐项用例。 | 获核验隔离恢复空间与受限权限、备份／水位、压测资源／演练窗口、Q04口径和客户正式签收。小样本live请求不能外推合同规模。 |
| G01 | CUSTOMER_GATED，未开启M7正式开发或任何真实远程连接。 | 仅复用／完善设计、接口模拟和权限审计验证方案。 | Q01堡垒机路线、产品／许可／接口及审批规则；确认前不进入正式排期。 |

本线不代替A／M／N／B／R各会话核销其编号；跨线剩余由集成会话统一汇总。

### 未完成代码的安全保留与恢复

1. **P01诊断API／界面**：命名stash `production P01 diagnostics API UI WIP not live-verified at user stop 20261023`，固定对象 `b97f437af8875a51323bcfd26f0c07bd8b30fbd2`。包含 `ProductionReadinessController.java`、相应测试、`ProductionReadinessPanel.vue`、`productionReadiness.ts`、运行配置页接线、契约helper及生成物。曾通过直接控制器／客户端单元、相关前端44项和构建，但未运行真实HTTP／页面浏览器验证，不能当作可发布UI。
2. **P02附件隔离流程**：命名stash `production P02 WIP awaiting migration lock while implementing P01 guard`，固定对象 `88cf0e3f311181e590a6dc77714018bd3e2f9c27`。包含 `AttachmentSecurityRepository`、`PostgresAttachmentSecurityRepository`、配置／控制器、`FileController`授权前后复核、`ClamdAttachmentScanner`、`AttachmentScanOnce`及Worker入口、照片面板／队列接线、契约和测试。阶段性Java/Worker29项、前端8+41项及构建通过；新增PG测试未执行，**尚未创建或分配附件SQL迁移版本**，没有数据库结构验收。扫描器未启用，真实扫描0，隔离发布流程未完成。
3. 旧检查点 `4987965b69030ebe869ba6a10b0ae201ed757609`（P03集成前P02）及 `e150b9cec1ea71e5bab8e729f6ff60693bde1d8a`（581f同步前）仅作回退保留；优先使用上面两个最新对象，旧检查点包含已被主干替代的DEMO生成器修复，不能整体覆盖上游。

恢复须在本独立检出先核对干净工作树、origin和最新main，先执行 `git stash show --include-untracked --stat <上述固定对象>` 审阅，再 `git stash apply <固定对象>`；使用apply保留原备份。两份stash都涉及契约生成器／生成物，须正常合并并复用主干 `demo-api-contract.mjs`，再生成契约，不能覆盖最新DEMO门禁。新迁移必须重新取得migration.lock、核对最新已分配／已执行版本，不照搬猜测版本号。恢复仅是交接说明，本线不会自动继续。

**资源状态**：本线没有启动候选API／Web；18216／15216未由本线占用。只读验证的SSH连接／临时转发均在脚本finally关闭；单独启动的浏览器只停留空白页、未访问应用或登录，已显式关闭。未取得本轮environment.lock或migration.lock，P03使用的integration.lock已释放；停止时的其他锁属于集成／通知等会话，未改动。私有文件、密码、Cookie及原始日志不入Git。

## 缺口清单续作（基线9cdccdc8）

2026-10-23按最新授权继续实际开发，已核对独立绝对目录、规范origin及本线分支，工作树无他人修改后正常快进最新main。`check_implementation_gaps.py`通过（128条／25组／无遗漏映射，completion_verified=false）。下方旧P01–P07是历史切片序号；**本节P01–P03／G01以新版总清单ID为准**，不能用旧切片完成替代总项完成。

- **P01**：既有预检／直接TLS守卫／回退检查复用；生产配置与实际运行隔离装配、管理端接线仍缺，Q02数据库最终方案／生产DDL不自行决定。
- **P02（实施中）**：新增照片附件持久安全状态仓储、当前租户操作API、幂等/CAS/审计及原图授权前后复核，照片面板接入申请、取消、发布、隔离和未知请求恢复。客户端无扫描结果写入口。前端8项流程测试及构建已通过；Java初次编译修正测试包引用后重跑中，数据库SQL版本等待通知线migration.lock，不占用／删除他人锁。凭据授权审计／安全留存仍缺，未将P02标完成。
- **P03**：既有证据核对器复用，新增有界只读HTTP执行器，经指定服务器的固定主机密钥SSH发送真实3次live GET，3次200/remote_test/UP；9项单元通过。仅证明执行路径，小样本不外推规模，隔离恢复／故障切换／合同持续压测仍缺。[接口、证据与剩余边界](../gap-p03-readiness-execution.md)。
- **G01**：保留CUSTOMER_GATED；Q01路线未确认，不连接真实堡垒机或设备，不进入M7正式开发。

本轮只读核验指定服务器项目mop-lan-test／lan-test-20261008，PG17.9、正式测试库mop_test、50条成功迁移／V50，无现成mop_security_test命名空间；未写业务或DDL。新附件结构仅计划在同一服务器的独立安全测试schema核验，不改变当前服务V50谱系；未重跑DEMO初始化、恢复旧身份、外发或部署。

已正常同步main `581f4a6b`，复用集成线的 `tools/demo-api-contract.mjs`，删除本线重复修复；当前生成器只增附件安全契约，保留DEMO定义及0.84。P02补充Java/Worker27+2项、前端照片相关41项及新增8项流程通过，根契约199项通过；后续仍按新迁移与真实数据库验证结果核销，尚未标VERIFIED。

## 仓库与授权核对

2026-10-23，本线负责众安在线MOP生产安全与验收工具。独立工作目录 `E:\work\wxw9\zhonganzaixian\.parallel\launch-20261023\production`；规范origin `https://github.com/yangyour/zhonganzaixian.git`（`yangyour/zhonganzaixian`）；分支 `codex/launch-production-20261023`，起始上游 `origin/main`，fetch后HEAD及远端基线 `05ec77ba`。依据本检出AGENTS首节和 `docs/parallel-development-20261023.md`。原主目录只读，未编辑现行前端样式／导航／AssetsPage.vue／AppTable.vue。开始时18216／15216无监听，本功能没有启动进程。

## P01 只读生产启用前检查

- 代码：严格无凭据manifest、显式TLS／HTTPS健康／匿名Cookie／HTTP跳转探测、超时与响应上限、脱敏机器报告、独占证据文件。当前test runtime与生产批准分开报告，生产门禁保留。
- 验证：17项纯单元通过；真实公共入口证书校验失败、HTTP跳转未满足，HTTPS后续检查NOT_RUN。见 [接口和运行说明](../production-preflight.md)、[真实证据](../evidence/production-preflight-20261023/public-entry.json)。
- 未完成：正式生产profile/数据库隔离方案、实际TLS修复、生产批准、正式TC；未迁移、部署、写业务数据、安装／停止组件。
- 按本功能单独提交推送，再持integration.lock集成最新main；提交hash以Git历史为准，后续功能续记。

P01已在 `ab441b95` 独立提交推送，并持integration.lock正常快进合入main；锁已释放。

## P02 安全会话传输保护

- 代码：显式开关、启动设置守卫、直接TLS请求过滤、无凭据回环健康例外；防转发头绕过，退出／改密删除Cookie保留可信传输属性。
- 测试：API reactor成功，18项实际通过、13项历史fixture跳过，0失败；第一次Mock scheme断言失败及修正见[运行和API说明](../secure-session-transport.md)。生成契约一致性通过。
- 真实联调：未启用、未部署、未启动新服务；实际TLS资源／代理边界尚待核验；不声明production profile或正式TC完成。与本项无关的组件沿用P01清单，无新安装／配置变更。

P02独立提交 `b8e4556e`，持锁与最新通知线主干正常合并为 `2a26e340`；18项集成分支复验通过，main推送完成、锁已释放。

## P03 发布／回退快照保护

- 修复手动恢复遗漏原发布schema的检查；新切换、自动回退、显式restore都校验原成功schema和私有配置摘要，阻止跨迁移和旧凭据恢复。
- 历史事务缺摘要时明确拒绝，需单独兼容性审阅；不会伪造旧证据或自动修复。
- `test_lan_update.py` 30项纯单元通过（新增10）；[运行／接口／兼容性说明](../release-recovery-guard.md)。无SSH、实际回退、迁移、组件操作或正式TC。

P03独立提交 `9467ff78`，持锁保留备份线上游后集成为 `c1be5d6f`；30项复验通过，main及本线均已推送、锁已释放。

## P04 发布制品与合同证据矩阵

- 新增实际文件SHA256校验、合同用例完整映射、五层证据隔离、版本／环境一致性和输出防覆盖；保留失败历史、M7路线门禁，不自动签收。
- 新增15项测试通过，与旧合同索引回归共32项发现／31执行通过／1平台条件跳过。真实合同读取生成128行、正式证据缺失128，不制造验收记录。
- [接口与运行说明](../contract-acceptance-bundles.md)、[矩阵](../evidence/contract-acceptance-20261023/matrix.json)；公共总追踪未改。无组件变更、正式TC执行或客户确认。

P04独立提交 `103ea0a4`，持锁保留通知／监控上游后集成为 `bfd5fe8c`；31项实际复验通过／1跳过，main及本线推送完成、锁已释放。

## P05 附件扫描有界传输接口

- 实现ClamD候选的摘要校验、INSTREAM分帧、15秒总截止时间、回包上限与EOF校验、扫描前后引擎／签名库一致性、脱敏结果。默认关闭；不发布、移动、删除附件。
- 13项纯字节／socket测试通过；没有实际引擎、组件、附件发送或正式TC。真实扫描端点／策略尚未核验，登记到组件清单；不把候选列为获批组件。
- [接口说明](../attachment-scan-adapter.md)。业务照片／导入接线和隔离发布台账仍未完成，本项只完成可执行传输接口。

P05独立提交 `3fcf9323` 已推送本线。首次main集成在fetch阶段遇GitHub `SSL_ERROR_SYSCALL`，未修改main；本线P05锁已释放，不占用其他会话的集成。后续恢复连接后重试，保留此失败记录。

## P06 恢复演练证据验证器

- 实际流式校验备份归档SHA256；核对隔离命名空间、应用制品、schema、完整表集合／内容、对象清单、恢复水位和时间序列；目标未确认保留null。
- 14项纯单元／临时合成文件测试通过；不执行恢复，观察一致性不代表真实性或客户签收。[运行／接口与边界](../recovery-drill-verifier.md)。
- 未提供本线获核验的隔离恢复空间、源备份／水位和演练窗口；未恢复共享mop_test或实际租户，真实DR及正式TC为NOT_RUN。

P06独立提交 `f8d77606` 已推送本线；GitHub连接已恢复，但integration.lock由assets持有，本线不删除他人锁，P05／P06等待顺序集成。

## P07 凭据轮换协调接口

- 完成引用级状态机、scope排他锁、fsync追加意图日志、条件版本切换、未知结果只查证、独立撤销授权及旧版本保护。默认没有真实后端。
- 16项纯单元通过；不使用真实秘密或组件。[接口／日志／异常恢复说明](../credential-rotation-coordinator.md)。
- 实际KMS／受限凭据scope、API授权／审计及消费者重载尚未接线；COMPLETE只代表注入后端的协议观测，不代表生产批准。所有真实凭据均未修改。

本轮生产线已完成七个独立可验证的代码／工具切片；不等于完整生产运行模块、真实扫描／轮换／灾备或128项合同全部完成。生产profile及数据库隔离正式装配仍待批准，TLS资源未通过检查，M7不启用。

## 最终集成复验（2026-10-23 03:40 EDT）

先前P05–P07的网络／排队记录保留为历史。取得本线integration.lock后，以最新main `17541885` 为基础，正常合并本线全部待集成提交为 `9482f459`；公共组件清单自动合并，保留资产／监控／通知／备份线修改，原主目录前端未触碰。

功能提交：P01 `ab441b95`（后续严化 `089084c7`）、P02 `b8e4556e`、P03 `9467ff78`、P04 `103ea0a4`、P05 `3fcf9323`（严格布尔开关修正 `e39823de`）、P06 `f8d77606`、P07 `bd9c86bb`。均先独立验证、提交并推送本线。

集成复验：Python124项发现、123项执行通过、1项平台条件跳过；API reactor编译成功，Java18项执行通过，无失败；生成契约一致。此前13项历史fixture跳过不计为通过。本次没有扩大为全仓业务集成或正式TC。[汇总证据](../evidence/production-safety-20261023/validation.json)记录实际范围、提交及每组数量；组件版本未因本次测试重新盘点。

真实只读证据仅为P01公网入口TLS／HTTP检查：证书失败，HTTP跳转不符合要求，HTTPS后续项目NOT_RUN。七个功能切片未启用任何生产profile、外发、设备采集、备份删除、KMS轮换、扫描器或恢复操作；未执行新迁移。下一阶段必须保留组件批准、真实隔离空间／权限、代理TLS边界及业务接线门禁，不能将本次141项实际单元通过转换为128项正式验收完成率。
