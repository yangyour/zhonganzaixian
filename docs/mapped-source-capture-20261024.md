# M02 单条绑定来源读取与原始记录保存

接续持久对象映射，新增实际调用链：资产详情 → 严格原命令恢复 → POST /monitoring-object-mappings/{id}/captures → 当前映射/资产/复核人/时效 → 服务端来源归属重读 → 精确event.get → 原始存储或冲突隔离 → 审计/幂等回执。没有新增DDL或改写V29/V30的未认证状态约束。

## 行为

- 仅支持已发布且读取时有效的HOST映射；ITEM不能凭主机归属推断触发器与监控项关系，返回422，后续补显式关系适配。调用方只提供expected_version、source_event_id和expected_source_definition_sha256，不提供地址、token、租户、payload、时间、严重级别或恢复结论。
- 来源事件ID为规范Zabbix uint64正整数；复用已绑定池的现有只读客户端，精确eventid_from=eventid_till，最多一条。event.hosts必须唯一且正好等于映射主机，空、跨主机、多主机不选“第一个匹配项”，对外404，无原始正文泄露。
- 发布元数据与当前来源自身标签必须完全一致。两次有界来源读取后重新检查本地策略、定义摘要、资产、复核资格和证明时效；来源失败、超时、失效不写原始行、不假称成功。两次远端读取不是上游事务，也不证明事发时的历史归属。
- 只保存9个允许字段的规范JSON，包括原始秒/纳秒、严重级别、value、触发器和恢复关联ID。保留设备来源时刻，不用接收时刻改写；来源时钟正确性未验证。
- 原始key为pool/source_event_id/phase，绑定为本地当前授权租户/资产。完全重复保留最早接收时间；同key事实不同不覆盖原始行，候选进入V30隔离。供应商后续改变恢复关联等事实也可能产生冲突，不自动当相同事件覆盖。
- 接入复用借用连接的raw/quarantine存储方法，不在内部提交、回滚或关闭外层连接。持久记录、冲突候选、审计及幂等response_json由同一个已授权READ COMMITTED事务提交；不再使用旧协调器两次独立事务来承载此API命令。独立存储入口保持旧事务生命周期。
- V29继续source_authorization_status=UNVERIFIED、storage_status=STORED_ONLY，V30继续HELD_UNVERIFIED且dispatch_allowed=false。RECOVERED只保存原始phase，绝不调用事件中心、恢复观察器、工单、通知或SLA停钟。
- 成功只返回acknowledgement_only历史回执，不宣称当前映射仍有效。新读/写失败不以空列表或成功替代。单独关闭capture开关后，当前重新授权的原幂等请求仍可读取既有回执，但新命令在调用来源前拒绝；整套映射模块未启用时仍保持原模块门禁。

## 页面、配置与契约

资产映射详情中提供折叠的“保存一条真实来源事件”表单，只接收事件ID，无任意JSON输入。单独开关未配置不显示操作；无当前有效映射不允许提交。成功后重读当前映射，重读503时仍显示已确认保存回执；授权失败/上下文变化清除旧回执。原始恢复信号明确标为“未确认t2”。

MOP_MONITORING_MAPPING_CAPTURE_ENABLED默认false，和持久映射开关分开。来源endpoint/token_env/tags沿用既有明确私有配置，不自动安装组件、启用采集或修改设备。公开OpenAPI由最终生成器增加新端点/回执/开关字段，离线DEMO只同步摘要与golden，不初始化账号或业务数据。

原始/隔离PG测试入口新增建立连接前的显式隔离库与测试租户检查。原始记录测试还须MOP_MONITORING_TEST_OTHER_TENANT_ID，且两租户不同、均非受保护客户，只查询这两个已有测试命名空间的资产；不再从活动库任意选两个租户。实际测试未执行，依赖指定服务器既有组件、协调和预先核验的测试身份，不创建替代数据库或回退旧fixture。

## 验证边界及剩余

见docs/evidence/dot-continuation-20261023/m02-source-capture.json。单元/协议/MVC/JDBC双替身/jsdom不等于真实来源或PG原子性联调。初次借用事务测试误在Mockito thenReturn参数中创建另一组stub，已先构造ResultSet再设置返回值，原“不提交、不回滚、不关闭”断言保持。

没有设备读取实际执行、服务器写入、迁移、部署或通知外发。尚未完成持续拉取/水位、来源授权证明日志、原始记录到业务事件的耐久消费、完整UNKNOWN_MAPPING隔离管理、ITEM关系核验及更多提供者。不能把单次原始保存当成监控接入或M02全模块完成。
