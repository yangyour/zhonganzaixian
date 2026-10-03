# SLA显式对照场景边界


## 2026-10-03 M5-19 SLA四时间点对照纯核心

新增SlaComplianceScenario，使用明确传入的冻结快照引用和四个实际/截止时间做合同固定关系比较：响应实际≤响应截止且恢复实际≤恢复截止才达标，等于边界可达标。缺失时间不填0、不填当前时间、不填4小时；缺快照不拿最新档位替代。某一已知维度确实超时，即使另一维度未知也已确定不达标，并保留缺失原因，不把未知伪装为已知。所有结果只属显式对照场景，effective=false/正式达成率null。

P1–P3且未获准排除为适用范围；P4不计入SLA适用分母。排除申请未批准或已拒绝仍在适用集合，不能靠申请排除隐藏超时；声明已批准还必须有显式引用，否则适用性未知。引用仅是本场景输入，不证明真实服务经理/客户已批准，未来接真实数据时须单独核验授权与证据。未知优先级/排除状态、未知完整样本或未定时间判定不会得到虚假比率；零适用分母不输出0%或100%。对照比率仅返回精确分子/分母，不选择未定财务舍入或Q26补偿公式，补偿金额始终null。

12新增纯规则及完整根818=805执行（含256既有真实PG回归）+13历史fixture跳过、6MinIO排除，39契约通过。10条合成输入/3超时得7/10；重复ID不能重复计数，原输入与结果不可变，新版本输入不改变旧结果。没有硬编码M5-20可配置时限或租户档位，没有调用当前人工恢复记录去填SLA实际时间。API0.20/V1–V14未改；尚无该模块公开接口/页面、真实SLA业务来源或正式达成率验收，不把既有PG回归当SLA联调。M5-19金额/正式月报与128项整体未DONE。

见[纯核心证据](evidence/niko-sla-comparison-core-20261003.json)。

## 2026-10-03 API0.21 只读显式SLA对照

新增POST /api/v1/sla/compliance-scenarios，仅msp/padmin当前启用成员，租户来自会话，CSRF必需，结构化只读输入不要求幂等键。最多200条、262144 bytes，严格拒绝重复JSON键、尾随值、深度超限、重复场景标签、未知字段及全部query。标签/引用最多160 Unicode code points，四时间点和总体完整性必须显式传值或null。

输出明确EXPLICIT_INPUT_NOT_PROJECT_DATA；scenario_ref和引用不会用于查真实工单/审批。声明APPROVED仅参与假设场景，approval_validation_status=UNVERIFIED/runtime_snapshot_validation=NOT_CHECKED。精确场景分子分母、未知适用性/时间分别计数；formal effective_achievement与compensation_amount永远null。没有业务写入、幂等收据或成功审计，安全拒绝仍沿用全局拒绝审计，不能把整个含403批次的审计尾不变当成要求。

11控制器+新增1Unicode纯规则（现13核心），完整根830=817执行（256既有真实PG）+13历史fixture跳过、6MinIO排除，41契约、474Web和类型/构建通过。首次编译Iterator.forEach错误已改forEachRemaining并完整重跑。拥有的API正常停止后以固定新工件启动，6新OIDC会话；49次真实HTTP均符合API0.21。10条3超时得7/10、待批/拒绝仍适用、空/未知总体、200/201条、160emoji/offset、CSRF/客户权限/覆盖字段均验证。HTTP脚本首次URN格式修正；匿名POST实际403由CSRF拦截；含拒绝请求审计尾会增加，改为明确成功只读序列前后比较后完整重跑通过。不改安全行为迎合测试。

真实联调只证明当前授权与HTTP数学服务，不证明真实SLA总体、审批或已生效快照。未提供本模块页面/DOM证据；业务工作流、Q08/Q26和128项整体未DONE。V1–V14保持冻结，无迁移。

见[API真实HTTP证据](evidence/niko-sla-scenarios-api-http-20261003.json)及[构建证据](evidence/niko-sla-scenarios-api-20261003.json)。
