# 异步台账导出：独立V20结构

## 当前实现边界

本批只增加真实PG结构及其约束，不接通导出API、Repository、Worker、OSS、下载链接或通知。表内SUCCEEDED和object_id是受约束的回执描述，不是对象已存在/已授权/可下载的证明；真实存储适配与重新授权仍待。

V20__durable_asset_export_jobs.sql已在niko-local-test显式独立执行一次，SHA256为5f8114dcdd2bd1cdf0c60fb75a5b521175462f583786602cd2b4a1835f960a83。当前V1–V20均字节冻结，后续结构修正新增V21或更高。原指定服务器未执行V20，API/Worker启动仍不自动迁移。

## 表、边界与恢复事实

- mop_asset_export_job：固定租户/创建人/受理角色和版本/规范命令bytes/trace，数据库生成输入SHA；命令bytes仍须未来仓储调用decodeBound并查询当前成员，FK和摘要不代替业务授权
- queued→running领取递增fence/version，最长10分钟技术租期、最多1000次历史；不是选择默认租期或授权1000次自动重试
- mop_asset_export_attempt：领取触发器同事务追加历史，连续fence及原租约范围必须与当前job吻合，不能改写或删除历史、延长旧租约或抢占未到期租约
- 预编码文件bytes最多16MiB，0–1000数据行，数据库生成文件SHA和大小；筛选SHA/行数/生成时间与文件只允许首次准备，后续完全保留。技术边界不是客户配额
- 已准备文件的租约接管强制RECONCILING_STORAGE，即使此前还显示RUNNING。存储不确定不能直接标成无关编码/来源失败；只有明确授权撤销或明确存储拒绝可结束该状态
- 存储不存在/存在回执仍须未来真实适配器验证；SQL无法识别一次HEAD究竟是404、403还是超时。数据库接受状态声明不等于该外部事实已核实
- 已结束状态不可重开；授权撤销可结束排队或过期租约任务，保留已准备bytes及历史，不能删掉可能孤立对象的调查依据
- 仅原项目migrator持有表；runtime保留SELECT/INSERT及指定状态列UPDATE，没有命令/受理身份/历史改写、DELETE、TRUNCATE或PUBLIC权限。函数SECURITY INVOKER、固定pg_catalog search_path并核验函数体hash

## 独立执行

构建资源后显式运行tools/niko-test/run-migration.py --apply-asset-exports，仅接受既有niko私有配置和完整V1–V19历史。--check只读，重复apply不重复执行。代码/编译资源/冻结manifest不符时，连接前即拒绝。既有迁移不得修改或用repair绕过。

首次候选在transition guard的CASE表达式缺少括号，FlywayMigrateException导致整项事务回滚。随后只读检查确认仍V19、两个新表和三个新函数均不存在；原失败候选SHA为abcfa591b0d4f3d2343de686ee344bf5128249444b43212d5fb113597b1badfb。保留失败日志与原候选后修正尚未应用的资源，再成功执行上述固定SHA。没有重写已应用迁移。

成功独立执行1次后重复apply为0、只读check为0；28张既有业务表排序行数/等值摘要前后一致。该比较不包括Flyway历史和新增结构，不是备份恢复或真实性签名证明。

## 验证与未完成项

16新增真实PG结构/租约/回执描述检查都在回滚事务中运行，合成文件为明确的测试字节，没有OSS请求、实际XLSX存储或外部通知。覆盖生成hash、权限、非法初始化、原文不可变、旧租约/版本拒绝、连续历史、准备后过期接管、未知存储、终态和授权失败保留。独立迁移CLI新增2纯策略测试，CLI本类合计23项。

本批实际作业受理、应用幂等/审计事务、仓储持久化恢复、实际Worker/崩溃恢复、上传/下载再授权及900秒过期仍未接通或未验。真实结构测试不将M3-7标DONE。

根Maven1522报告、1509执行通过，含434真实PG、13历史fixture跳过、6MinIO排除；73契约与生成一致性通过。见[本批脱敏证据](evidence/niko-asset-export-schema-20261004.json)。

既有项目venv的111项Python检查通过；首次误用系统解释器缺paramiko导致2个模块导入失败，保留日志后改用已安装依赖的venv重跑，没有安装新组件或执行SSH连接。

## 2026-10-04 V20跨平台冻结清单遗漏修正

c40bf37的CI37175873116中Java/Web/Windows进程检查通过，但PowerShell迁移策略仍只列V1–V19，拒绝新V20源码集合。补齐V20清单、独立asset-exports选择及导出核验器新旧检查；保留只读默认与单功能执行，V1–V20 SQL字节不动。新增跨入口清单回归对原提交先红，113Python/73契约通过；niko无pwsh，PowerShell原生执行留给既有CI，不冒充本地通过。见docs/evidence/niko-export-migration-policy-20261004.json。

后续9b95a8b的CI37176283202四个job全部通过；原生PowerShell迁移策略67项通过、数据库操作0（job111359441377）。该结果不表示原服务器已执行V20，niko本地pwsh仍未安装。

## 2026-10-04 事务化受理与自己的任务读取

`PostgresAssetExportRepository` 仅在平台受保护的幂等事务中接受明确筛选。受理与原响应、审计原子提交；原key/原body回放相同任务，改body为409。`authorizeAcceptance` 也在回放前从实际PG检查成员活跃、版本及padmin/msp角色并加共享锁，不能用缓存回执绕过已变更权限。受理只写QUEUED，不宣称文件生成或发布。

读取仅允许当前内部角色查看同租户、同创建人的任务。合法内部身份读其他租户/创建人的ID为404，失效版本401；客户角色403。列表仅展示与当前受理角色/版本匹配的原任务，旧请求/原回执不删除，按created_at/id成对游标降序读取，技术limit为1–101，无自动轮询。

get/list是独立只读REPEATABLE READ快照，拒绝嵌入写事务。规范命令bytes必须通过decodeBound与不可变受理列/数据库生成SHA一致；错误、非规范或其他租户命令正文作为存储不一致503拒绝，不反射原文字段。公共仓储DTO不包含命令bytes、文件bytes、object_id、trace、下载地址或授权token。

本阶段没有HTTP导出端点、202收据契约、CSRF入口、实际Worker、对象存储或下载；仓储的幂等保护不能被算作HTTP安全验收。新Java对象读回同一PG任务只是仓储持久化回读，不称为JVM/机器故障重启。

本批20新增真实PG检查通过，含同key并发只提交一次、原回执回放、跨租户/创建人404、当前成员检查、审计失败/错误命令/其他租户正文故障注入后三表原子回滚。成功受理测试持久化明确合成的QUEUED任务；不能称整批零写。根1542报告1529执行含454PG、13历史跳过、6MinIO排除，73契约通过。[仓储证据](evidence/niko-export-repository-20261004.json)。

## 2026-10-04 真实正常停启后的队列与原回执

受理仓储a38c84a的CI37177043110通过后，使用既有runner正常退出，仅停止它拥有的PG/Keycloak/API，再从同一项目数据目录正常启动。PG实际启动时间从01:42:40变为04:30:33 UTC；本批不是SIGKILL、断电、处理中Worker回收或灾备恢复。

26条合成QUEUED任务、0条领取历史，以及全部原幂等/审计表的行数和等值摘要在停启前后完全一致。之后新JVM经当前成员检查读取26条任务、回放26份原回执，接受新任务的回调一次也未进入；再比较四表仍无变化。未启动Worker，文件准备/对象回执均0，未调用OSS、下载或通知。

首次快照工具错误地探测Flyway历史，runtime账号按既有ACL拒绝；移除非必要的历史读取后继续，只使用同一runtime身份，未提权或改权限，也不宣称此工具验证了Flyway历史。26条原回执验证完成后才刷新6个真实OIDC会话，避免将登录新增审计误算成重启丢失/重复。

新增9项纯脚本门禁测试，全Python122项和73契约通过；证据路径独占写，不覆盖已完成的before/after。新增脚本纳入既有CI。见[正常停启与新JVM回读证据](evidence/niko-export-queue-restart-20261004.json)。
