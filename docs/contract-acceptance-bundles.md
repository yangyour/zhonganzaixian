# 按发布制品核对合同验收证据

`tools/contract_acceptance.py` 重新读取原合同提取结果和逐项追踪，生成完整128行矩阵，并验证本次提供的证据文件摘要。它不运行测试、不改追踪表、不签收；单元通过、HTTP切片和正式合同TC保持独立。已有 `contract_progress.py` 的声明索引继续保留。

输入bundle为JSON，所有键严格校验：

| 位置 | 字段 |
| --- | --- |
| bundle | `format=1`、`source_commit`（完整40位）、`artifact_sha256`（64位或null）、`environment`、`records`数组 |
| record | `run_id`、`case_id`（例如TC-M2-5）、`layer`、`outcome`、`source_commit`、`artifact_sha256`、`environment`、`executed_at`（含时区）、`evidence`数组 |
| evidence | `path`（仓库docs/evidence下的公开脱敏文件）、`sha256` |

layer只允许UNIT／COMPONENT／HTTP／BROWSER／FORMAL_TC；outcome只允许PASS／FAIL／BLOCKED／NOT_RUN。每条记录的提交、制品、环境必须与bundle完全一致，真实层次记录必须提供制品摘要。PASS和FAIL必须带实际存在、摘要一致的证据文件；BLOCKED／NOT_RUN可以没有文件。运行／用例／层次组合不得重复；失败后重跑须新run_id，原FAIL记录继续展示，不覆盖历史。单一文件最大16MiB、bundle最大2MiB、最多4096条记录，都是读取工具的技术限制。

只读取规范相对路径；拒绝私有配置、外部URL、路径穿越、符号链接／目录连接、摘要不符、重复引用及混合版本。证据文件只计算摘要和字节数，不复制原文至矩阵。声明的执行结果保持 `declared_outcome`，文件摘要通过不证明内容真实性、执行者身份或业务断言完备。

```powershell
py -3.11 tools/contract_acceptance.py --bundle .local/acceptance-bundle.json --output .local/acceptance-matrix.json
```

退出0表示输入结构、合同映射和提供的文件摘要核对成功；不表示合同通过。退出1表示校验失败；stdout不带私有路径或输入原文。输出排他创建、不覆盖旧证据。每行保留追踪表声明状态、五层执行记录、正式证据是否存在；`formal_acceptance=UNVERIFIED`、`completion_verified=false`、`production_ready=false`始终保留。即便FORMAL_TC声明PASS，仍需审阅执行内容及客户签收。M7不删行、不进入本轮正式实施，标为CUSTOMER_ROUTE_PENDING。矩阵保存合同源文件及生成工具SHA256，便于核对后续变更。

2026-10-23实跑：[128项矩阵](evidence/contract-acceptance-20261023/matrix.json)，基线 `c1be5d6f`，只读解析真实合同和追踪。未提供本轮正式执行bundle，故records=0、正式证据缺失128，不能把旧切片自动填成正式PASS。该矩阵不回写公共总状态。

验证：新增15项测试通过；连同已有contract_progress回归共32项发现、31项执行通过、1项原路径测试因平台条件跳过。覆盖篡改／缺失文件、版本混用、层次隔离、失败保留、私有路径／链接拒绝、重复记录、无时区时间和输出防覆盖。无组件连接、合成租户写入、迁移或外部操作；无新增组件需求。正式测试执行资源、窗口和签收仍按客户依赖清单保留。
