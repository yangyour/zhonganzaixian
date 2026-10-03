# D06/Q06 显式阈值条件诊断

## 2026-10-03 纯计算核心

依据doc/development/02的M4-D12和04的Q06，现可实现规则候选的单位/方向/滞回校验；冲突规则生产启用及默认种子仍必须暂停。新增ThresholdConditionAnalysis只分析显式输入，不选合同70/85与80/90中的任何一组作默认，不擅自把丢包恢复>15改成<15，也不生成真实事件或监控配置。

输入明确direction、warning/critical/recovery三个条件、trigger_hold_seconds和recovery_hold_seconds。每个条件完整保留comparison/threshold/unit，允许null表达未定；比较符为GT/GE/LT/LE，方向HIGH_IS_BAD或LOW_IS_BAD。十进制以字符串解码为BigDecimal，最多40位整数/40位小数技术限制，拒绝浮点JSON数、指数、负零、未知字段或操作符，原值不舍入、不自动交换。负阈值可以用于温度等显式候选，系统不猜指标物理范围；持续时间负数明确冲突，缺失保持未知，明确0不等于缺失。

## 可证明和不可证明

- 比较在实数半直线模型上进行，严格区别>/<与>=/<=的等号边界
- 严重条件须是警告条件的真子集；同数值不同开闭边界可以数学上有区别，但不证明实际采样分辨率或业务合理性
- 恢复与警告、严重条件分别检查交集，包含同值闭区间相交；不因为另一项已经冲突而忽略恢复与严重的直接重叠
- 单位仅明确相等时可比较，不把百分比和比例、大小写或别名自动换算；不同单位为冲突，依赖的比较结果为null
- 不完整字段为UNRESOLVED；已知冲突与未知项同时存在时保留全部原因并返回CONFLICT；全部数学检查通过才CONSISTENT
- CONSISTENT仅表示显式候选谓词数学一致，publishable=false、effective_rule=null、customer_confirmation=UNVERIFIED、physical_range_check=NOT_RUN、monitoring_applied=false始终保持
- 未实现指标实际取值/采样质量、持续窗口时序、防抖计数、客户资格或模板发布，不把单点或静态谓词当作真实告警已触发/恢复

## 验证

20纯单元包含高值/低值、反向严重顺序、错误恢复方向、同值开闭边界、相同谓词、17位小数接近1不舍入、负指标/负持续时间、未知/单位冲突/严格字段与技术边界。一项独立分段采样检查枚举1728个比较符/阈值组合，每个覆盖全部边界、区间中点和两侧，以独立逐点谓词判断真子集及恢复交集；这是20项中的一项，不能累加成1748个测试。

首次编译发现record的unit访问器遮蔽同名helper，重命名为validateUnit后20项及完整根1262=1249执行（392既有真实PG回归）+13历史fixture跳过/6MinIO排除全部通过，67契约/生成一致性通过。392PG不包含新监控联调；本核心未接HTTP/仓储/页面/模板组件，API0.32/V1–V19均不改。前D07守卫4803492 CI37153448635 success已回读。

下一步只读诊断API与显式候选页面；客户Q06矩阵/指标物理范围/心跳/监控组件未确认仍独立阻塞。M4-5/M4-18、完整D06和128项均未DONE。见[纯核心证据](evidence/niko-threshold-predicate-core-20261003.json)。


## 2026-10-03 API0.33只读诊断与75真实HTTP

新增POST /monitoring/threshold-diagnostics，仅当前平台管理员且具已有configurations:read，不增权限目录/身份绑定。当前成员/版本/租户/用户先于解析核对，CSRF必需，无幂等写或自动作业。全部query及根租户/发布字段覆盖拒绝；正文16KiB、3层、256 token、严格重复键/尾随检查，单位Unicode与精确十进制字符串由纯核心核对。形状错误400，数学矛盾200/CONFLICT，明确未定200/UNRESOLVED；输入原方向、操作符和小数scale回显，不交换或补值。

响应包含各关系的true/false/null与全部问题码、固定版本threshold-half-lines-v1。始终source=EXPLICIT_INPUT、publishable=false/effective_rule=null、客户未核验/物理范围NOT_RUN、units_converted/monitoring_applied/time_series_evaluated/rule_saved/writes_performed=false，不查询监控、规则当前值或客户证据。CONSISTENT仅数学一致。

10新控制器、根1272=1259执行（392既有真实PG回归）+13历史fixture跳过/6MinIO排除、70契约、748Web/typecheck/build与生成一致性通过。正常关闭自有隔离栈并以本次固定API JAR重启、刷新六实际OIDC后75HTTP全部按0.33契约核对：高/低值、反向严重顺序、错误恢复>15不翻转、四组开闭边界、17位小数接近1/1.000原scale、负指标与中文单位、全null/部分条件、单位不换算、负持续/明确0、恢复直接重叠严重、严格字段/数值/JSON边界、四种无权限角色先403、CSRF、匿名、另一授权租户与恢复原上下文均验。

375条实际配置登记完整DTO摘要前后相同；三次单独只读重复诊断前后审计尾相同。HTTP证据中的readonly_candidate_posts=3专指这三次readonly_repeat审计探针，不是全部诊断POST数量；总75为全部实发请求。26项能力目录hash及六身份权限版本不变，除明确测试租户切换无业务写入、无新授权。纯核心f1bbbfce CI37153822693 success已回读。

当前尚无新前端诊断页面/真实DOM、监控组件或规则发布，API0.33/V1–V19无结构修改和自动迁移。客户Q06矩阵/物理范围/心跳与真实模板适配继续独立待定。见[API汇总](evidence/niko-threshold-diagnostic-api-20261003.json)、[75 HTTP](evidence/niko-threshold-diagnostic-http-20261003.json)。


## 2026-10-03 前端精确诊断状态模块

新增thresholdDiagnosticWorkspace与独立严格JSON扫描器，不改既有联系人解析器。原始输入在JSON.parse前检查重复解码键/深度/字节/token/字符串和非规范或非安全数字，随后要求完整6字段、显式比较符/方向/单位及十进制字符串。18新前端测试，全766Web/typecheck/build与70契约通过；Vite另有>500kB最小化chunk建议警告，构建exit0，不据此宣称实际浏览器性能达标。

用有界BigInt按小数位缩放比较，逐字段独立复核实际服务器响应的原输入/租户/版本、全部半直线关系、issues顺序与固定不可发布标记；17位接近1、40位整数/小数、负分数不经JS Number舍入。验证期望只用于核对真实响应，不提供本地成功替代。输入编辑立即清旧结果，重复诊断抑制，网络/403/坏成功响应不保留旧通过结论；强制上下文或卸载清空私有输入并丢弃迟到响应。

编译实际模块+ApiClient连接API0.33/现有真实PG/OIDC，34HTTP（29只读诊断POST）、21显式候选向量全通过，包含高/低、未知、方向/单位矛盾、等号四边界、BigDecimal与BigInt最大精度/负分数逐项一致。一次真实200在客户端丢弃、四次实际200被挂起后验证编辑/双击/上下文/卸载，均无本地假成功；不是物理断网或JVM故障。MSP真实403、375登记DTO摘要不变、只读成功审计尾不变，无规则保存/发布、监控读取或其他业务写入。

API提交a0cfe540 CI37154429680 success已回读。此切片未创建页面或DOM验收，Q06生产矩阵/组件仍独立待；下一步显式候选诊断页面。见[模块证据](evidence/niko-threshold-client-20261003.json)、[34真实HTTP](evidence/niko-threshold-client-http-20261003.json)。


## 2026-10-03 阈值候选诊断页面

新增/threshold-diagnostics及桌面/移动已有configurations:read权限导航，仅当前padmin展示。默认空输入，不自动读取登记规则、不填合同阈值，示例仅展示null结构且不会发送。显式只读诊断后显示“数学一致，仍不可发布”“资料未定”或“候选冲突，未自动修正”，分别列明15类问题、三条件原比较符/精确小数/单位和五关系true/false/null；不显示保存/发布/自动修正入口，不把监控状态或物理范围猜成正常。

输入编辑立即清旧结果；主动离开/切租户/退出/清空需确认，确认期间锁输入，旧租户确认不能擦除新租户输入。强制上下文/卸载清私有输入、取消旧请求并拒绝迟到结果，beforeunload提醒。只读POST无自动定时刷新或写入替代。

9 SFC处理器+1源接线测试通过，全776Web（完整复跑2并发）/typecheck/build、70契约/生成一致性通过。首轮全量执行未取得完成汇总、执行通道轮询取消，未计通过，原日志保留；随后完整2并发复跑53文件/776项均通过。构建>500kB chunk建议仍保留，不冒充实际性能验证。Vue内存renderer/stub控件不算真实浏览器DOM/390px视觉；B03待。状态模块1308d6a CI37154979716 success已回读，前34真实HTTP不重算为本页面新HTTP执行。

见[页面证据](evidence/niko-threshold-page-20261003.json)。实际监控采集/阈值发布、Q06客户矩阵与整体M4-5/M4-18仍未DONE。
