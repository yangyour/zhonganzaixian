# Dot 接续开发交接（2026-10-02）

用户最新要求提交现有代码，然后由 Codex 产品中的 Dot（当前名 `niko`）自行完成本项目编程。请先阅读根 `AGENTS.md` 和 `doc/development/01–07`，按全部128项合同需求持续实施。旧 Codex 长期目标已由用户暂停；不得自行创建、启动、调用、委派 Codex Task、Codex Cloud Task、ChatGPT Work Task，也不得恢复旧目标。若确实只有其中一种任务能完成某步，先停该步，说明原因并等待用户明确许可。

Dot 直接使用自己的云电脑、终端、编辑器、浏览器和可用工具阅读、修改、创建、运行、安装项目依赖、启动、调试、测试和构建，无需再次确认常规项目开发操作。每完成一个独立功能，测试、更新实际接口及实施/验收记录后立即提交并推送。不能把部分验证或原型标为需求 DONE。

## 工程与来源

仓库 `https://github.com/yangyour/zhonganzaixian`；接续分支 `handoff/cloud-20261001-photos`，请拉取该分支最新提交，不能仅从旧 main 开始。本地工程根 `E:\work\wxw9\zhonganzaixian\mop-platform`。本交接不含凭据。此前已提交的照片和 D04 配置诊断证据分别见 `docs/report-photos.md`、`docs/verification.md`；2026-10-01 云端交回本地文档属于历史状态，不覆盖本文。

## 当前代码与真实验证

本次保存 D05 导入来源切片：CSV/XLSX 原文件耐久受理、指定 MinIO 不可覆写存储、独立无凭据解析进程、明确工作表/表头/编码/分隔符选择、15列映射及原始预览回读。实际 API 契约为 V0.8。业务仍 `CONFIG_PENDING`、`can_import=false`、有效规则及配额占用 null、预检 `NOT_RUN`；不是正式资产导入完成。

已运行记录：后端202项隔离测试通过；指定 PostgreSQL69项通过（新增导入18项、既有51项）；指定导入 MinIO3项及既有照片 MinIO3项通过。初始完整远端 verify 因大正文条件写验证的 SocketException 失败，随后加入 `Expect: 100-continue` 并定向复验；最终提交检查记录另见 `docs/evidence/dot-handoff-verification.json`，不能将初次失败日志改写为成功。前端最新172项、类型及构建通过；契约18项与生成一致性通过；进程归属12项隔离检查通过，实际进程操作为0。

真实 API 已完成两次独立运行：CSV70次响应/178项记录检查、CSV+XLSX124次响应/327项记录检查，见对应 `docs/evidence/local-asset-import-http-*.json`。重复轮询和两次重叠场景不能累加为互不重复的验收用例。包含原字节SHA、解析/预览hash独立重算、creator与租户404、viewer403、CSRF、幂等/CAS、JSON资源上限、隐藏工作表和1904日期系统原始元数据、公式拒绝。

桌面浏览器已验证正常 CSV 上传、真实上传202/解析202/映射200成功后丢响应，以及原 File/参数/body/幂等键重试与回读；最新页面控件注册、显式表头输入已修复。移动视口完成 XLSX 原文件、目录和隐藏工作表/表头选择至 PARSED；重复表头还需在真实控件中明确选位置0并忽略15/16，完整移动映射及最终视觉验收尚未完成。不能将本批页面部分检查当成全面UI验收。

## Dot 首先处理的已知问题

2026-10-02后续：第1项已完成代码修复及隔离回归，真实UI因当前云工作区缺私有配置仍未验证；原交接问题和当时证据保留如下。见[新回归记录](evidence/dot-import-refresh-20261002.json)。

1. `apps/web/src/utils/assetImportWorkspace.ts` 的普通 `refresh` 在旧请求 COMPLETE 后只读当前来源，`acceptSource` 仍可能保留旧 `operation` 显示。另一客户端已发起新 PARSE 时，页面会同时显示新来源 PENDING 和旧操作 SUCCEEDED。来源通过完整身份/hash/版本校验后，若操作ID不匹配当前 `store_operation_id`/`parse_operation_id` 或产物绑定不符，清空旧显示操作；保留历史请求与收据，不自动 GET 新操作或 POST。补“完成后被新解析覆盖”的只读刷新测试，再实际UI核验。此前仅修复 ACCEPTED/UNCERTAIN 被取代后的冲突分支，不能视为此项已修。
2. 完成移动视口重复表头手动映射/忽略列、最终回读和视觉检查。390px步骤条当前逐字换行，需要改善可读性；最终映射截图尚未归档。操作可见选框包装或标签，避免强制点击被覆盖的内部输入。仅报告真实执行结果。
3. 补导入实际 Worker/API 重启、运行中存储断链恢复和完整流水线资源边界验证；仓储18项采用明确的隔离存储/解析适配器，仅证明真实PG事务与fencing，不能代替整个 MinIO/独立解析子进程链路的故障验证。
4. 继续标准xlsx模板、客户规则允许的逐行真实预检、并发配额/去重、最终资产入库与错误回执、资产依赖PG保存/监控抑制、D04正式配置生效、D07平台权威工单闭环及D06–D14。Q20/Q31等未确认规则保持 null。128项合同追踪和完整TC仍未完成；文件解析500行的技术上限不等于完整导入500行≤60秒验收。

## 环境、迁移与运行

所有本地或Dot云端集成均复用指定服务器既有 PostgreSQL17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07 及项目命名空间。DB `mop_test`；运行账号 `mop_test_runtime`；照片桶 `mop-test-photos`；导入桶 `mop-test-asset-imports`、前缀 `asset-import-sources/`。已有Redis/RabbitMQ尚无业务联调；Zabbix/CMDB/堡垒机/通知接口缺口以 `docs/environment/component-inventory.md` 为准。不得另建本机或云端替代组件、fixture回退或安装/停止既有服务器服务。

私有文件引用 `deploy/private/develop.txt`、`runtime.json`、`known_hosts`、导入命名空间文件；都保持Git忽略，未随提交上传。通过受控私有配置/秘密存储提供，不向Dot消息、公共日志或Git粘贴原值。Dot若不能直接取得秘密，登记阻塞并先开发不依赖该项的代码；不得为了取本地秘密自行创建Codex/Work任务。

以上是首次交接的历史状态。2026-10-02用户最新授权把6个真实联调配置以原格式提交到其本人使用的private仓库，覆盖先前全部忽略安排；名单与限制以最新 `AGENTS.md` 和[配置接续说明](private-test-configuration.md)为准。临时QA会话、备份和测试产物仍忽略。Dot取得文件后须适配自身本地路径并实际验证组件，不因配置已提交就宣称云端联调通过；禁止把密值复制到聊天、日志或公共证据。

V1–V6已执行且不可改字节。V6 SHA256 `1F532822668274B7AEBD12EA78B57E6BE0AA9FD53CEB77023EE8067589CD2B4B`；独立ApplyAssetImports执行1、重复0、只读Check0，均结构/ACL核验。V6头部历史UNEXECUTED注释因checksum冻结保留，当前执行状态以独立证据为准。API/Worker启动不迁移。未来结构变化新增迁移，不编辑已执行SQL。

本地启动 `deploy/start-dev.ps1 -SkipBuild -WithPhotoWorker -WithAssetImportWorker`，独立导入Worker `*-imports.jar` 和指定 standalone parser JAR；只处理导入job，不消费照片或其他outbox。API `asset_imports.worker_activity=NOT_PROBED`；ready不证明Worker在消费。进程停止使用严格PID/启动时间/路径拥有权脚本，只停本工程。详情 `docs/asset-import-runtime.md`。

2026-10-02 页面验收期间项目SSH转发进程自然退出，接口未返回fixture替代结果；重新启动本工程隧道后 ready/photos/imports 均恢复200，无远程服务变更。长时间联调应核对实际SSH状态及就绪响应，不把监听端口或旧进程记录当作依赖健康。
