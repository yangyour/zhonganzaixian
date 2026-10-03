# Q08版本化工作日历草稿与试算

当前：V12结构已执行冻结；业务仓储/API/页面尚待，客户确认及正式生效保持未执行。下文纯核心描述是该提交时的历史状态。

## 2026-10-03 纯核心切片

依据doc/development/04的Q08“现在可做：版本化日历、区间推进算法和模拟边界用例”，新增WorkingCalendarDraft纯值对象与后续仓储接口。当前没有日历表、API、页面或生效配置，未执行V12。已有WorkingTime只负责显式窗口数学，本切片不把数学结果改成客户批准或正式SLA承诺。

草稿要求明确名称、覆盖起止、已知工作窗口列表、显式coverage_complete及可空说明；拒绝缺失列表和缺失完整性声明，不把未知默认为空或false。完整性是维护人的输入声明，customerConfirmation仍UNVERIFIED，effective始终false。工作窗口可为空或包含重叠/相邻/乱序窗口；保留原始顺序与重复项，试算另求不可变并集，避免重复累计。所有窗口须在覆盖范围内，持久化准备按0001–9999年、毫秒精度约束；每份至多1000窗口是技术边界，不是客户业务配额。

时区标识遵从已确定的Asia/Shanghai；数学使用显式Instant，不默认周一至周五、不生成09:00—18:00、不推断节假日/补班，也不把P3“次日”补成24小时。试算必须携带明确草稿ID/version、显式起点和所需时长；起点/时长未知返回既有UNRESOLVED原因，覆盖不完整或工作时间不足不返回截止。显式0仅是数学输入，不用于替换未知值。

Preview仅提供scenarioDeadline，effectiveDeadline始终null，scope=UNPUBLISHED_DRAFT_SCENARIO。原始列表与归并结果不可变；后续草稿改动不改变既有试算的版本/消耗区间。此类型不是身份认证或业务配置发布机制；未来仓储必须验证当前配置维护权限、当前租户、CAS、幂等和审计，使用新独立迁移，不自动迁移。

10项纯规则测试通过：保留原始/归并快照、试算与生效截止分离、缺输入与次日不推断、不完整与已知空日历区别、显式0、跨闭合时段与版本不变、缺草稿字段、Unicode文本边界、窗口/精度/数量边界和试算身份/版本。完整根679=666执行（含188既有功能真实PG回归）+13历史fixture跳过，6MinIO排除，0失败错误。188PG不算日历持久化证据；API仍0.17，32契约不变，V1–V11保持冻结。

下一步为独立V12与真实PG草稿/修订历史，随后接只读试算与页面；任何发布、客户确认、基础档承诺和“次日”解释仍按Q08暂停，M5-D04/D08及完整合同未DONE。见[核心证据](evidence/niko-working-calendar-core-20261003.json)。


## 2026-10-03 V12工作日历草稿结构（已执行冻结）

新增独立草稿头与不可变修订表，原始窗口JSON随修订保存，复合租户/作者/前版FK、延迟当前修订FK及精确列级ACL限制写入。头表仅更新版本/更新时间，历史只追加；DRAFT、UNVERIFIED、effective=false、Asia/Shanghai与MANUAL_CALENDAR_DRAFT固定，不能借schema发布日历。输入纯SQL函数不读业务表、不以定义者权限运行、固定pg_catalog搜索路径，PUBLIC无执行权限；独立CLI校验其实际函数体SHA256，拒绝结构相同但函数体漂移。

V12已独立check0→apply1→重复0→check0；旧评论apply0。函数体指纹校验加入后又check0/重复apply0。V12 SHA256 e6e0412e8a96936a6f4e09dc9f96431cd6551dd9c15ebaf7d2ed414467aedb1a，V1–V12全部字节冻结，后续结构更正必须V13+；API/Worker无自动迁移。

10新增真实PG schema测试、13迁移策略、50 Python守卫通过；最终根690=677执行（含198真实PG）+13历史fixture跳过，6MinIO排除，0失败错误。schema测试全部回滚，测试后只读两新表均0行，Q08两项配置value仍null。首次执行前补强previous_version非空CHECK，执行后SQL未再改；函数指纹仅增强Java校验。API仍0.17，未新增日历HTTP、业务仓储、页面、正式SLA或客户批准，完整合同仍未DONE。继续下一步真实事务仓储，不因B01/B02/B03/B04暂停无关开发。

见[结构证据](evidence/niko-working-calendar-schema-20261003.json)。
