# Zabbix事件分页读取基础（2026-10-21）

## 范围

新增ZabbixEventPuller，在既有受限loopback读取客户端中仅增加只读event.get。固定查询触发器来源及触发器对象，绑定显式资源池；另一个资源池的查询在网络调用前拒绝。未启动补偿Worker，未配置或使用真实凭据，不提供公共HTTP入口。

协议依据：[Zabbix 7.0 event.get](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/event/get)、[Event对象](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/event/object)。事件ID和时间上下界均按接口的包含边界处理；事件按ID升序读取，clock/ns保留来源创建时间，不解释为可信探测时刻。

## 读取与页边界

- 每次显式给定pool、起止事件ID、起止秒及1～1000条页大小，无隐藏缺省查询窗口。源ID使用uint64范围的规范十进制字符串，不经过浮点或long截断。
- 请求多取1条作为后续页探测，完整校验所有返回项，包括探测行，再返回本页与下一起始ID。下一起点为最后已返回ID+1，探测行不会被当作已存储跳过。
- hasMore=false仅表示该响应未携带额外探测行，不证明源数据保留、权限可见性、采集时间窗或监控完整性；completeWindowClaimed始终false。
- 拒绝重复/倒序/越界ID，错误来源/对象，超时窗、非规范数字、未知字段、缺字段和超限页。整页失败不产生部分成功结果。
- 保留PROBLEM/OK值、严重级0～5、recoveryEventId及原始纳秒。严重级是来源值，不擅自映射为客户P级。
- 所有host候选保留；空或多个host不挑第一个、不自动绑定资产。最多16个为技术预算，重复host或非请求字段拒绝。
- 返回不可变列表，错误与toString不输出来源正文或令牌。

## 依赖与未完成项

event-pull-dependencies.json列出资源池、已批准来源连接、既有只读凭据引用、对象映射、水位仓储与失败载荷存储引用，均为null，开关关闭。固定补偿间隔/回看窗口/失败载荷保留键只列登记键名，后续从受验证登记读取，不把示例当运行配置。新增2项模板检查，并与通知模板检查一起接入CI。

本层只返回解码字段，不保留或验证原始HTTP字节，不证明来源与资产映射授权。durablyStored、mappingVerified始终false。后续必须补来源认证、受保护原文留存、V29存储/冲突隔离及租约水位事务：只有整页耐久写入后才允许提交水位。不能把nextEventId直接当作已提交水位，也不能使用“空页”证明健康。

严重级及关联恢复字段可能在来源后续变化，重读时应按既有原始键冲突规则处理，不覆盖历史。尚无真实Zabbix兼容性、权限可见性、跨页变化、补扫24小时恢复、数据库并发、自动建单或通知验收。

## 验证

23项隔离测试通过：固定参数、跨池预先拒绝、探测行/下一页、uint64边界与数值排序、恢复及纳秒、空/多host不推断映射、重复host、非法ID/字段/数值/范围、非法探测行整页拒绝、上游错误脱敏与不可变结果。使用伪读取回调，不进行网络请求。

完整Maven verify结果：2306项执行、897项环境跳过，0失败/错误。54项Python检查通过；本批未改前端/API契约。128项正式验收仍NOT_RUN。

## CI阻塞

当前主分支上一提交b9ee49d的合并后CI39533390995在任务启动前失败，重试一次仍失败；具体错误等待用户提供。此批会保存到独立分支，但远端检查未恢复前不合并。没有改变CI门槛或跳过检查来伪造通过。

候选已提交为[5aa8e179c4d4917ab0dc717fb42624155293320a](https://github.com/yangyour/zhonganzaixian/commit/5aa8e179c4d4917ab0dc717fb42624155293320a)，分支dot/zabbix-event-puller-20261021；[运行39534254538](https://github.com/yangyour/zhonganzaixian/actions/runs/39534254538)四个任务也全部在启动测试前失败（steps=0，持续2～4秒）。未合并，不重复重试；等待用户提供Actions错误说明后再恢复验证和合并。
