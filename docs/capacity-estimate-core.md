# M10-7 显式参数容量估算核心

`CapacityEstimate` 是D13中的纯数学估算器，按开发文档02的8输入/9输出模型实现。初始纯核心时点未接入页面、HTTP、配置仓储、真实采样或采购流程；后续API见下节。结果始终标记 `estimateOnly=true`。

## 输入

8个输入为hosts、itemsPerHost、intervalSeconds、historyDays、bytesPerValue、trendRatio、trendDays、headroomRatio。数量/天数非负，采样间隔和每值字节数正数；比例用0至1（不是0至100百分数）。

另需显式模型版本、每条趋势记录字节数、单池NVPS上限。这些属于版本化模型系数，不能把原型80/128字节、5000 NVPS等示例变成正式默认值。任一必需值未提供则UNRESOLVED、outputs=null并列出缺项；即使主机数为0，也不绕过缺失模型参数。

## 九个输出及单位

令 I=hosts×itemsPerHost，s=intervalSeconds，b=bytesPerValue，r=trendRatio，t=bytesPerTrendRow，h=headroomRatio，p=poolNvpsLimit：

1. NVPS = I/s（值/秒）
2. 日数据量 = I×86400×b/s（bytes）
3. 历史量 = 日数据量×historyDays（bytes）
4. 趋势量 = I×r×24×trendDays×t（bytes）
5. 单节点估算 = (历史量+趋势量)×(1+h)（bytes）
6. 双节点估算 = 单节点估算×2（bytes）
7. 峰值估算 = NVPS×2（值/秒）
8. Item总数 = I（整数）
9. 池数估算 = ceil(I/(s×p))（整数）

中间分子保持精确，十进制输出按明确的DECIMAL128技术精度舍入一次；不用二进制浮点数。池数基于未舍入输入向上取整，防止商略大于整数时因显示舍入少算一个池。数值使用BigInteger/BigDecimal，数量不依赖32/64位整数溢出行为。bytes不冒充GiB，界面换算应另标单位。

## 证据与未完成范围

10项隔离测试含9项手算结果、缺值不补默认、分母/比例校验、明确0数量、循环小数精度、整数临界取整及超固定整数范围。测试中的80/128等仅是标记明确的合成输入。

Q13实际负载/硬件、Q21资源和Q25/Q30正式系数仍未落实；模型值不代表实测NVPS、监控健康、采购建议或合同容量达标，双节点数学估算也不证明HA或零丢失。API V0.8、私有配置、V1–V6不变，真实组件测试NOT_RUN。运行结果见[独立证据](evidence/dot-capacity-core-20261002.json)。


## 2026-10-03 Proxy缓冲显式乘数核心

ProxyBufferEstimate补齐M4-15/M10-7的纯公式：站点NVPS×最长断网秒数×每值空间bytes×明确余量倍率。前两项非负、每值空间和倍率正数，任一缺失输出null并列缺项；即使NVPS为0也不掩盖其他缺值。不提供80字节/1.4等默认，不把输入每值空间冒充已实测。

倍率是直接乘数，不是自动1+比例。例如明确0.4就是乘0.4，不自动解释成1.4；这是数学语义，不表示低于基础需求的值足够或得到采购批准。高精度十进制乘法中间保持精确、最后一次DECIMAL128舍入，输出单位bytes。测量/接管验证始终false，单Proxy缓冲数学不证明双活或无丢失。

8新纯测试涵盖手算50,400,000bytes、空输入/不补默认、0值不掩盖缺项、负数/分母、倍率语义、小数精度和超固定整数范围。完整根970（957执行含295既有真实PG）/13历史跳过/6MinIO排除、51契约通过；这批PG是回归，不是新Proxy组件联调。首轮新测试var复合声明编译失败，改为明确BigDecimal后完整根通过。公开估算API、页面、保存场景/基准关联、实际Proxy和12,500NVPS×24h压测继续，M10-7/128项非DONE。见[纯核心证据](evidence/niko-proxy-buffer-core-20261003.json)。

提交前同步e6eb115及其本地父提交ee2f77b，保留原指定库时区用例修复和双方验收记录；随后根970及51契约再次通过。本功能不重算或改写已发布维保证据。


## 2026-10-03 容量与缓冲只读API0.27

POST `/capacity/estimates` 当前只开放padmin的capacity:estimate，必须真实启用会话/当前租户/CSRF；不需要幂等键，不保存场景或成功业务审计。三个组inputs/model/proxy_buffer都必须出现，可明确null；非空组字段必须完整，缺失与null不混用。数量/小数用十进制字符串（最多40整数位+40小数位，无指数/前导零/空白），天数是非负32位JSON整数。此为技术有界解析，不是合同容量上限；8个输入和模型版本/趋势字节/池上限全部由用户明确提供。

九个输出为不含指数的十进制字符串，避免JavaScript安全整数边界及小数二次舍入；单位明确bytes/values_per_second/整数。未知返回UNRESOLVED及null输出/缺项列表，主模型与Proxy独立。Proxy倍率直接相乘，低于1时显式margin_factor_below_one=true，不能当成额外40%或已满足基础存储。输入系数和版本仅为自述，approved_pool_nvps_limit、measured_capacity始终null，scenario_saved=false、生产容量未核验、PoC NOT_RUN；主备数学不证明HA/接管。

12控制器、完整根982（969执行含295既有PG）/13历史跳过/6MinIO排除、53契约/574Web/类型构建与72真实HTTP通过。手算九输出+Proxy、40位精度临界池数ceil、超JS安全整数、循环小数、0不掩盖缺项、完整字段/边界、平台管理员与其他角色403、会话切租户和成功只读审计尾不变均有证；无场景或业务数据写入。实际角色绑定未改；代码能力目录从20增至21，真实GET生成的表和摘要见[能力目录](capability-catalog.md)。

契约生成首轮错用helper名、通用写入测试未列只读例外，已修并完整53通过；HTTP脚本语法在请求前修复，实际72请求首轮通过。API0.26拥有栈正常退出，新固定API0.27就绪且6OIDC重新认证；V1–V15未改，不自动迁移。估算页面、持久化场景/真实基准依据、实际Proxy与12,500NVPS持续24h/2倍峰值30分钟未验，M10-7/128项非DONE。见[API回归](evidence/niko-capacity-estimate-api-20261003.json)、[真实HTTP](evidence/niko-capacity-estimate-api-http-20261003.json)。


## 2026-10-03 容量与缓冲页面

平台管理员新增独立估算页及桌面/移动导航，8个输入初始全部空白，模型标签/趋势字节/池限明确输入；Proxy组独立启用，不自动继承主模型。未知值不当0，比例0..1与缓冲直接倍率分别说明；倍率<1醒目标记低于基础需求。9输出原样十进制字符串/bytes单位展示，不转换JS Number或GiB四舍五入，不冒充采购建议。

只在点击“只读估算”后调用真实接口。修改输入清空旧结果，重复点击抑制；身份/租户/权限切换和卸载丢弃迟到回应。前端用BigInt有理数独立核验服务端九输出/Proxy结果，最后一次34位HALF_EVEN舍入；本地校验值从不代替API结果展示。页面说明场景未保存、模型/测量未核验、PoC未执行，增长曲线/真实基准关联仍待。

18新Web共592、53契约/类型构建通过；半偶舍入中点/进位、超安全整数、精确ceil和未知边界有纯证据。实际capacityEstimateWorkspace/ApiClient对真实PG/OIDC/API18HTTP（15只读POST/0业务写入）通过，覆盖40位输入、循环小数、1e-160极小普通十进制、丢弃1成功响应、延迟4真实响应、输入/权限/卸载失效、重复请求抑制、伪造本地管理员仍被真实msp403以及只读审计尾不变。这里只是受控传输响应注入，不是物理断网/进程崩溃。

页面控件、手机视口和真实导航仍未DOM实测；API0.27/V1–V15未改。容量场景持久化、增长曲线、实际基准/Proxy/12,500NVPS持续24h与峰值PoC、完整M10-7/128项非DONE。见[页面证据](evidence/niko-capacity-estimate-ui-20261003.json)、[真实模块HTTP](evidence/niko-capacity-estimate-client-http-20261003.json)。

提交前同步5da15e5及其父链，保留Windows启停、资产筛选组件注册、手机导入步骤样式修复和原服务器浏览器/恢复记录；它们不覆盖本容量页。13张远端截图引用未改，本工作区受二进制读取限制未做像素复核；未生成占位或覆盖原图。合并后再次执行完整前端/契约检查。

继续同步28fa9fe的原指定环境API0.27定向/72HTTP与分环境脚本收尾；两个环境结果不相加冒充单次总数，也不把原接口复验算作本容量页DOM验收。


## 2026-10-03 容量场景草稿纯核心

CapacityCalculation保存有界的显式容量/模型/Proxy输入，并生成带两项固定公式版本的纯快照。数量最多40整数位、小数最多40整数/40小数位；负值/0分母/无效比例和空模型标签失败关闭。未知组/字段保留null，不因准备保存草稿而补原型系数。

CapacityScenarioDraft要求明确名称（100码点）和说明（2000码点，保留多行原文）；可选基准引用仅接受document-ref标识、标题与64位SHA256。URL、磁盘路径与明显凭据标记拒绝；提供引用或摘要不证明文件存在、真实压测或已验真。无引用NOT_PROVIDED，有引用REFERENCE_ONLY_UNVERIFIED，始终DRAFT/effective=false/PoC未执行。纯对象不是持久化成功收据。

9纯规则与完整根991（978执行含295既有PG）/13历史跳过/6MinIO排除、53契约通过；API0.27/已执行V1–V15不变。下一步独立结构、真实保存仓储/原回执/当前租户权限与场景页面，现阶段未写场景表，M10-7/128项非DONE。见[纯草稿证据](evidence/niko-capacity-scenario-core-20261003.json)。


## 2026-10-03 V16不可变容量场景草稿结构

V16只在niko独立项目库显式执行1次；重复、只读、旧V15目标以及测试后只读均0次。V1–V16现在字节冻结，后续修正新增V17+。mop_capacity_scenario_draft有22列/21CHECK，运行身份仅SELECT/INSERT，不能修改、删除、清空或给PUBLIC授权；租户与操作者复合FK、有界JSONB及摘要、明确公式版本、引用完整组和DRAFT/未批准/未测量/PoC未执行/不生效固定约束已验。

JSONB摘要证明存储值等同，不证明真实文档或压测。结构校验不等于算术校验、当前padmin授权、幂等和审计事务；后续仓储必须重新计算已记录公式版本并比较结果，重放前重验当前成员与资源，不能只信JSON形状。旧业务20张白名单表全部现有列指纹迁移前后相同，未排除任何已有列；不是整库/外部真实性证明。

12新真实PG结构测试全部回滚、最终场景表0行；18Java迁移策略、63Python保护、根1004（991执行含307真实PG）/13历史跳过/6MinIO排除、53契约通过。首轮3个断言把布尔文字t/f或Jackson数字节点类型当值本身，已改JDBC布尔和精确JSONB等值比较并完整重跑；已执行SQL未改。

Linux显式模式为--apply-capacity-scenarios；Windows包装层新增-ApplyFeature capacity-scenarios并复用16份冻结资源，默认仍只读。PowerShell49项保护用例已接CI，本niko工作区无pwsh，发布时不声称本机已执行。原指定服务器仍只掌握V15证据，本次没有修改或迁移它；应按其已有拥有权/停应用/构建/私有连接流程另行显式运行，API/Worker不能自动补迁移。

API0.27未改；真实场景保存仓储/API/UI、基准文档验真和现场PoC继续，M10-7/128项非DONE。见[结构证据](evidence/niko-capacity-scenario-schema-20261003.json)。


## 2026-10-03 容量场景真实仓储

真实V16仓储已实现受保护事务创建、当前租户padmin回读及按名称字面筛选/创建时间+ID倒序分页。创建必须expected_version=0，生成新的不可变v1对象；这是仅创建语义，不冒充集合覆盖CAS。不同明确请求可保存同名独立方案，原请求幂等键相同则只产生1个对象/1条成功审计；重放前仍重新检查当前用户、成员、权限版本，拒绝客户/工程师和跨租户对象404。

calculation以严格十进制字符串编码保留原始有效小数位和大整数；数据库JSONB只对计算结果数值作语义规范化，回读使用精确BigInteger/BigDecimal比较，不经double也不拿Jackson数字节点类型当业务值。每次读按已记录公式版本重新计算并核验快照/两个JSONB摘要；形状合法或摘要匹配不能掩盖错误数学。压测基准仍只是可空未验真引用，不变成已测量/批准/生效。

15新真实PG和5新纯codec/JSON规则、完整根1024（1011执行含322真实PG）/13历史跳过/6MinIO排除、53契约通过。包含独立连接同键竞态仅1写入、错误后草稿/审计/回执全回滚、旧回执保持、2/2/1分页、只读审计尾不变。伪造算术/数值输入类型的负例用真实PG回滚事务中的生产行解码器验证，不声称跑了公开HTTP损坏场景。就绪还检查真实owner、22列、21验证CHECK、有序索引与无更新/删除权限，不回退fixture。

V16后续CI37120360437已通过，日志明确49项PowerShell迁移保护/0数据库操作；本机niko没有执行PowerShell，原指定服务器V16仍未验。见[CI补充](evidence/niko-capacity-scenario-schema-ci-20261003.json)。

场景表现在已有本仓储测试提交的明显合成草稿，先前schema时点0行仅保留当时含义。公开场景API/CSRF/完整DTO与页面尚未接线，API0.27/V1–V16不变；真实基准/PoC、完整M10-7与128项非DONE。见[仓储证据](evidence/niko-capacity-scenario-repository-20261003.json)。


## 2026-10-03 容量方案API0.28

POST /api/v1/capacity/scenarios 明确创建不可变DRAFT（expected_version=0→1），GET列表/详情真实PG回读、每次验证输入摘要和固定公式重算结果。仅当前租户启用padmin，CSRF、幂等和审计；原收据返回前重新校验当前授权。严格五字段 label/note/calculation/benchmark_reference/expected_version，引用显式null或未核验资料标识；全部数量/小数输入输出为十进制字符串。嵌套字段顺序不改请求摘要，原文和数值字符串保持有意义。新键可建同标签独立方案，不提供覆盖、删除或生效操作。

列表标签ICU小写字面匹配，不搜索说明；10/15/25/50/100分页，paired before_recorded_at/id；每页一致快照，不是冻结导出。跨租户详情404；unknown/duplicate query拒绝。就绪纳入V16真实schema/ACL检查，API/Worker仍不自动迁移。模型/基准/实测/PoC均未核验，保存不改变这些门禁。原容量估算接口语义未变。

11新增控制器+1就绪测试，根1036=1023执行（322真实PG）+13历史fixture跳过，6MinIO排除；55契约、592Web、类型/构建通过。停止旧自有API0.27并从新构建启动0.28，六个新真实OIDC会话；71真实HTTP覆盖原键/嵌套顺序重放、改体409、并发同键、11个独立合成方案、10+1游标、权限/CSRF/跨租户404、数值精度/未知/引用门禁、只读审计尾不变。23项代码声明真实GET摘要验证，不改人员绑定。源码接口测试不是浏览器DOM或客户验收。

[完整回归](evidence/niko-capacity-scenario-api-20261003.json)；[真实HTTP](evidence/niko-capacity-scenario-api-http-20261003.json)。下一步保存页面/原请求不确定结果恢复与真实前端模块；原指定服务器V16未验、OSS、实际基准、增长曲线和长期PoC仍待补，128项不标DONE。


### 2026-10-03 容量方案前端状态机（页面尚未接入）

实际reader严格核对租户/原文/版本/未核验门禁，复用BigInt有理数校验固定公式输出，输入无浮点转换。JSONB摘要仅检查格式，由服务端真实仓储校验一致性，不作为签名或客户端独立摘要证明。列表15条keyset顺序/重复ID/cursor边界验证，读取失败清旧显示而保留已确认原收据。

首次提交生成固定原文/幂等键；响应丢失保留uncertain，只读刷新不解决原请求，不自动重发。原请求重试即使收到真实403，仍不能证明先前未提交，必须继续保留原不确定状态/阻止新建。新成功收据核对当前租户/创建人和全部原始输入；上下文强制失效或卸载清理并拒绝迟到回包；页面尚未注册，因此路由/离开guard还不能当已完成。

13新增Web共605、类型构建、55契约通过。最终19实际前端模块HTTP/9次POST尝试/5条合成方案；含真实成功响应丢弃/迟到、原键恢复、实际MSP403重试保护、重复点击抑制、只读审计尾不变。此前16HTTP/4条为独立早期批次，不叠加作新覆盖。API d247b91 CI37122927576 success已回读。

证据：[前端状态机](evidence/niko-capacity-scenario-client-20261003.json)、[最终真实HTTP](evidence/niko-capacity-scenario-client-http-20261003-retry-guard.json)。下一步直接接入页面和离开保护；无浏览器DOM/生产批准/PoC结论，V1–V16不变。
