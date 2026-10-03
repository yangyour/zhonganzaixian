# 代码能力声明核对目录

## 边界：不是有效权限矩阵

本页按M2-6合同的四类角色和五个资源维度整理当前Capabilities静态代码声明。它不是客户签字矩阵、实际人员/租户/设备授权、配置驱动角色或具体资源访问决定。代码中已声明某能力，仍须通过当前成员、租户、对象关系及各接口独立守卫；本目录不枚举所有额外守卫。当前没有声明条目的维度不等于业务已完成，也不等于业务不存在。

API0.25新增GET /api/v1/authorization/capability-catalog，仅当前启用padmin、有效权限版本及configurations:read；拒绝全部query。只返回代码角色/维度/能力声明，不读取或返回人员绑定，不能创建角色、修改权限或发布配置。effective_customer_matrix、resource_access_decision及客户/供应商签字均null；customer_approval_status=UNVERIFIED、configuration_status=CONFIG_PENDING、configuration_driven_authorization=false、新增角色配置驱动运行NOT_IMPLEMENTED。auth.role_matrix_and_assignments仍不可当运行配置。

目录覆盖26项当前声明；search:read同时归属资产与指标、告警与工单，但总目录只一行。声明映射摘要算法为UTF-8 SHA256：前缀mop-capability-declarations-v1换行，viewer|tadmin|msp|padmin换行，随后按能力ID排序的“ID|逗号连接维度ID|四角色1/0向量”逐行。摘要不覆盖说明标签，也不是签名、客户确认或授权令牌。

## 来自真实授权API的只读快照

以下表更新自联系人API0.30的136次实测中的1次niko-local-test真实授权GET，重新计算摘要一致；没有人员或凭据。它方便在GitHub核对，不能作为生效配置或签字验收。

声明映射SHA256：`a348d208a3398caefab6c03632ec962f8223ab66b1488cf61da27ce13bb313f8`

|能力|说明|资源维度|客户观察者|客户管理员|MSP值班工程师|平台管理员|
|---|---|---|---|---|---|---|
|asset_dates:read|读取记录日期对照|资产与指标|未声明|未声明|已声明|已声明|
|assets:read|读取资产记录|资产与指标|已声明|已声明|已声明|已声明|
|assets:write|资产相关写入口能力声明|资产与指标|未声明|已声明|已声明|已声明|
|audit:read|读取当前租户审计|平台管理|未声明|未声明|未声明|已声明|
|capacity:estimate|显式容量与缓冲估算|平台管理|未声明|未声明|未声明|已声明|
|capacity_scenarios:read|读取容量方案草稿|平台管理|未声明|未声明|未声明|已声明|
|capacity_scenarios:write|保存不可变容量方案草稿|平台管理|未声明|未声明|未声明|已声明|
|configurations:read|读取设计配置与提案|平台管理|未声明|未声明|未声明|已声明|
|configurations:write|维护设计草稿与内部提案|平台管理|未声明|未声明|未声明|已声明|
|contacts:read|读取当前租户联系人|成员管理|未声明|已声明|未声明|已声明|
|contacts:write|维护当前租户联系人与归档|成员管理|未声明|已声明|未声明|已声明|
|manual_incidents:read|读取手工来源登记|告警与工单|未声明|未声明|已声明|已声明|
|manual_incidents:write|创建手工来源登记|告警与工单|未声明|未声明|已声明|已声明|
|manual_recovery:confirm|提交独立人工恢复确认|告警与工单|未声明|未声明|已声明|已声明|
|manual_recovery:read|读取独立人工恢复确认|告警与工单|未声明|未声明|已声明|已声明|
|operations:compare_explicit|显式经营样本与人力对照|平台管理|未声明|未声明|未声明|已声明|
|search:read|分组搜索|资产与指标、告警与工单|已声明|已声明|已声明|已声明|
|sla_scenarios:read|只读SLA显式对照|告警与工单|未声明|未声明|已声明|已声明|
|ticket_comments:read|读取客户可见评论|告警与工单|已声明|已声明|已声明|已声明|
|ticket_comments:write|追加客户可见评论|告警与工单|已声明|已声明|已声明|已声明|
|ticket_notes:read_internal|读取内部备注|告警与工单|未声明|未声明|已声明|已声明|
|ticket_notes:write_internal|追加内部备注|告警与工单|未声明|未声明|已声明|已声明|
|tickets:read|读取授权报障登记|告警与工单|已声明|已声明|已声明|已声明|
|tickets:report|提交客户报障登记|告警与工单|已声明|已声明|未声明|未声明|
|worklogs:read_own|读取本人工时|告警与工单|未声明|未声明|已声明|未声明|
|worklogs:write_own|维护本人工时|告警与工单|未声明|未声明|已声明|未声明|

模板与自动化维度保留在API/页面，当前声明条目数为0。成员管理维度现有2项联系人能力，但并未实现账号邀请/角色授予或身份源治理；不因此标完整成员管理完成。四类角色来源固定合同，服务经理/值班长/二线审批人/审计职责的真实映射、工程师资源范围、审批回避与替补、客户确认关系仍待Q09。

## 原API0.25页面与初始验证

新增管理员代码声明核对页、桌面/移动导航和打印样式。页面只显示“已声明/未声明”，明确非生效权限矩阵；读取失败清除可打印旧目录。实际前端reader重新计算SHA256，验证四角色、五资源维度、每行声明布尔向量、维度反向索引和null门禁；租户/权限变化及异步摘要完成后的迟到结果均拒绝。打印按钮只调用浏览器打印，不创建后台导出或签字记录；未运行真实打印。

8纯目录+8控制器、完整根916=903执行（273既有真实PG）+13历史fixture跳过/6MinIO排除；14新增Web共554、类型构建和49契约通过。27真实API HTTP核对20行与六个实际OIDC会话的能力集合、当前padmin/第二管理员、非管理员403、匿名401、query覆盖400、签字/生效null、前后能力与权限版本不变、成功只读审计尾不变。实际reader+ApiClient+摘要验证另12HTTP（reader6GET，全部0写入），包括丢弃成功GET清旧、两个迟到GET在权限/卸载后拒绝、重复刷新抑制、伪造本地padmin上下文仍被真实msp会话403。另1GET仅生成上述可核对文档快照，与27/12分开。

显式READ_ONLY_CHECK确认V1–V14结构/ACL/冻结校验，migrations_executed=0。控件/打印/移动页面为源码和单测，DOM/截图/打印均未验；没有新增角色、人员授权、配置发布或签字。完整配置驱动权限矩阵、双方签字和M2-6/M2-7/128项尚非DONE。

证据：[完整回归](evidence/niko-capability-catalog-20261003.json)、[API](evidence/niko-capability-catalog-http-20261003.json)、[实际前端模块](evidence/niko-capability-catalog-client-http-20261003.json)。


## API0.27容量能力声明增量

capacity:estimate仅平台管理员声明，目录21行；当前表及摘要已由72次容量HTTP中的1次真实GET更新，六个实际会话核对该能力及前后权限版本不变。只新增当前代码的显式数学入口，不改身份绑定、客户签字矩阵或配置发布。初始20行证据保留原时点含义，不能冒充当前21行。见[增量HTTP证据](evidence/niko-capacity-estimate-api-http-20261003.json)。


## API0.28容量草稿声明增量

新增capacity_scenarios:read/write，仅padmin静态代码能力；真实GET核对23项声明和摘要。旧20/21项证据保持原时点，不改写历史。没有修改人员绑定或客户签字状态。


## API0.29显式经营对照声明增量

operations:compare_explicit仅padmin声明，目录24行；本批64真实HTTP含六会话/真实目录和摘要核对，未改人员绑定。该能力仅显式样本算术，不读取团队经营数据或开放正式经营指标。原20/21/23行证据保持历史含义，见[HTTP证据](evidence/niko-explicit-operational-api-http-20261003.json)。


## API0.30联系人能力增量

contacts:read/write仅当前授权padmin/tadmin，目录26项；真实GET和六会话核对能力集合/摘要及前后权限版本未变。联系人角色是业务标签，不能授予账号权限或直接当客户确认资格。旧24项证据保持历史含义；136HTTP见[联系人API](evidence/niko-customer-contact-api-http-20261003.json)。
