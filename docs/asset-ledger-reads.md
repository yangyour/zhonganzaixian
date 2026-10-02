# 主台账有界授权读取与Unicode查询

GET /assets与GET /assets/{id}改为实际PostgreSQL授权读路径；仍使用原响应字段、created_at/id升序及绑定租户/操作者/权限版本/筛选的签名cursor。原先在应用层先加载整租户再筛选分页，现由PG执行筛选/排序/创建时间keyset和LIMIT(page_size+1)，每次最多返回101行到应用，不等于数据库最多扫描101行；不声称已达到5000台性能目标或冻结导出快照。

读取授权与数据处于同一只读REPEATABLE_READ快照，核验启用用户/成员、当前权限版本及assets:read。四种既有角色仍只读当前租户，角色范围不扩大；不存在/跨租户详情404，过期权限401。主控制器额外拒绝无效租户会话类型/空值、inactive成员。查询错误或断库503，不回退全量内存、其他数据库或fixture。历史FixtureRepository只增单独隔离MockMvc适配，不被真实PG调用或回退使用；原13测试单独作为隔离回归通过，不算真实组件证据。

## Unicode问题与修复

第一轮实际PG测试发现locale=C/C下默认lower不处理Ä等Unicode：对应7项新增PG中1项失败。只读目录探测确认UTF8编码、既有pg_catalog.und-x-icu可用；没有修改数据库locale、权限、网络或安装新组件。查询表达式明确使用既有ICU根locale小写后作字面strpos；IP仍按原样、区分大小写子串匹配。百分号/下划线/引号不是SQL或通配，存储原文不改，不做Unicode归一、音译或维保状态推断。

同样修正内部资料字段筛选和站点/负责人选择器的文本小写表达式。真实PG覆盖Ä/ΟΣ/İ/K和组合/分解字符不归一，确认数据库全局locale前后仍C/C。就绪检查实际查询该依赖，缺失/不符合返回503，不自行建collation。原指定服务器的ICU可用性因B01未验证，不能沿用niko结果。

另一次同时启动两个Maven命令时出现共享target测试类缺失，后续改为串行运行并完整重跑；失败日志保留，不将失败执行计为通过。不得在共享工作树并发跑Maven构建。

## 验证与边界

新增3查询核心、5控制器、1就绪、8主台账真实PG及1资料Unicode真实PG；根616=603执行（含163真实PG）+13历史fixture跳过，6MinIO排除，0失败错误。13历史MockMvc另行显式隔离通过，不替代PG。29契约、289Web及类型/构建通过；54真实HTTP响应契约验证包含6身份当前租户、12条10+2升序分页、签名cursor绑定/篡改、Unicode、大小写IP、字面输入、跨租户404、非法NUL400、资料Unicode与就绪、成功读不追加审计。API V0.16。

V1–V10与原私有配置不改。浏览器DOM、真实生产规模/查询计划、Q09/Q31及完整合同验收仍待，不标M3-1/M3-2/M3-5 DONE。见[HTTP证据](evidence/niko-asset-ledger-read-http-20261002.json)和[汇总](evidence/niko-asset-ledger-read-20261002.json)。
