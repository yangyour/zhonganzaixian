# M6-1 每日备份计划草稿核心

## 来源与范围

M6-1/T00R078、02 M6-D01允许先开发计划模型。合同默认每日02:00，失败重试2次、间隔10分钟；config-baseline把三者标为configurable_default，不是不可调整的固定约束。business.timezone=Asia/Shanghai是平台固定约束。

BackupPlanDraft目前只表达每日计划提案：租户UUID、名称、显式目标资产UUID列表、严格ASCII HH:mm、可空credentialRef UUID、requestedEnabled和可空备注。没有持久化/API/页面/调度或SSH调用。此阶段不保存密码、主机连接串、私钥或厂商命令，也不创建、核验、配置凭据。

名称1—100 Unicode码点且非全空白/控制字符；备注可null/空/空白，保留原文与换行，上限4000码点，拒绝孤立代理字符及其他控制字符。目标必须明确非空、不重复、不含null、有序且防御复制；1000项是技术保护而非业务配额，超过拒绝、不截断。不把格式正确的目标UUID当作资产存在/当前租户授权证明，未来仓储必须在保护事务内核验全部对象，跨租户404。

时间不暗中补默认：CONTRACT_DEFAULT_TIME仅给将来表单提供显式建议，null/缺值拒绝。每日频率是本切片范围，不声称支持合同所有可配置频率。proposedRetryCount=2、proposedRetryIntervalMinutes=10仅合同默认提案，状态CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE；以后已确认的覆盖值不能被这两个方法静默覆盖。

## 永不激活的草稿

requestedEnabled保留使用者的true/false意向，但enabled=false、effective=false、state=DRAFT、deviceExecutionAllowed=false、nextExecutionAt=null。提供credentialRef也只得到NOT_VERIFIED_BY_THIS_MODEL，没有引用时NOT_SUPPLIED。目标验证同样未完成。没有“创建草稿即排队”的副作用；不能用这个纯对象作为设备执行授权。

实际计划发布/启用仍须明确设备访问授权、合法凭据、固定SSH主机信任、厂商只读命令、真实存储与密钥门禁，以及事务幂等、版本/CAS、审计、多实例租约和设备执行锁。创建正式计划的202+operation契约没有被本切片实现，不能返回假operation；新设备自动纳入/退出默认模板也未实现。

## 显式日期预览

previewDate要求调用者给出具体日期，不调用系统时钟、不推算下一次执行、不定义错过时点补跑策略。正常日期只返回当地日期时间对应UTC候选，标UNPUBLISHED_CALENDAR_CANDIDATE，createsRun/executionAuthorized始终false，missedRunPolicy=null。

使用JDK ZoneRules检查当地时间。历史上海夏令时缺口返回LOCAL_TIME_GAP，重叠返回LOCAL_TIME_OVERLAP，两者candidateInstant=null，不自行选择早/晚偏移或平移时间。合法日期和UTC输出均限定0001—9999年，UTC下溢拒绝。业务固定时区不依赖JVM默认时区。候选不是调度承诺，不证明设备在那个时点执行过或会执行。

## 测试与未完成边界

18纯测试覆盖意向与生效隔离、空值/精确时间、默认提案、目标边界/复制、凭据引用不授权、Unicode/错误不回显、午夜/年界/闰日、上海历史夏令时缺口与重叠、UTC下溢、重复只读预览，以及一天1440个分钟向量回转。实际构建/回归结果见[证据](evidence/niko-backup-plan-draft-core-20261004.json)。

本切片0新迁移、0计划业务写入、0HTTP、0SSH、0存储/凭据配置。V1—V23冻结、API0.38不变且固定在线包未重启。后续先补受保护的未生效草稿仓储和页面；设备/存储/生产批准阻塞不妨碍这些代码，但不以单元对象或引用伪造真实备份。M6-1与128项正式验收仍NOT_RUN。
