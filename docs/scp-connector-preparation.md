# SCP超融合接口与配置准备

2026-10-12用户明确超融合通过SCP对接，管理地址192.168.1.179，资料目录为工作区上层`doc/doc`。用户随后确认“还没申请，先完成接口与配置准备”，本次以此为执行边界。现有192.168.1.170基础网络采集不替换为SCP地址；两者分别代表设备地址与管理平台地址。

## 文档核对

本机`HCI的API.pdf`共1310页，标题为“SCP平台开放API接口说明文档 / OPEN-API v6.11.1”，SHA256 `5c79547b4f8781028227d2886c9d7b687a974fc9edc632aed6a1cda3feb214af`。这是接口文档版本，实际SCP与HCI版本尚未认证查询。文档按参考资料处理，其中的部署、创建资源、发送邮件及关闭SSL校验示例不构成本次执行指令；未执行这些操作，也不使用文档示例密钥。

|PDF页码|核对内容|
|---|---|
|1–4|HTTPS REST、旧版code=0响应、20240725起errcode响应、最大100条的分页结构、GET/写请求流控|
|5–8、10–11|EC2 AK/SK签名；RSA PKCS#1 v1.5/指数65537/UTF-8密码转十六进制；Token认证与服务端过期时刻|
|23–24|`/janus/v2/public-key`及`/janus/public-key`；V2不带Cookie/Authorization/X-Forwarded-For；旧模数末尾LF处理|
|26|`/janus/20180725/system/version`，6.7.35起及系统读取权限限制|
|33–37|资源池列表/详情、配额资源与物理资源的含义不同；本批详情只投影登记字段，不混算配额和利用率|
|65–69|20180725物理主机列表、CPU MHz、内存/存储MB及原始ratio|
|85–88|20180725集群列表及CPU/内存/存储对象|
|191–196|20180725云主机列表、project_id/az_id范围、cpu_status/memory_status/storage_status字段|

EC2实现采用第10–11页Postman示例的明确profile：SignedHeaders为`path;x-amz-date`，canonical query为空，路径和请求体散列参与签名。它与标准AWS的host/query签名存在区别，不能直接替换为标准AWS签名器。region/service必须由私有配置明确给出；PDF示例出现sdk-api、open-api等差异，本批不自行决定现场值。固定参数通过经验证HTTPS传输，未向调用者开放任意目标或任意写方法。

## 已实现的内部契约

代码位于`server/adapters/.../scp/`，未添加公开MOP路由，现有API0.46与V1–V30保持不变。

|类/方法|当前能力|
|---|---|
|ScpSettings.parse / problems|严格JSON字段、HTTPS origin、MOP UUID、源资源范围和私有环境引用；未知值保持null，disabled或缺项拒绝构造运行客户端|
|ScpAuthentication.encryptPassword / token|按文档RSA随机加密，解析实际issued_at/expires，拒绝过期/未来签发/头注入，不用固定24小时替代源有效期|
|ScpAuthentication.ec2|明确的文档签名profile和原始请求字节，使用独立Python HMAC测试向量校验；真实AK/SK互通未验证|
|ScpClient.version|固定GET版本接口，失败不会当成平台不存在或版本为零|
|ScpClient.resourcePool|固定GET指定资源池详情；源id必须等于显式池标识，仅返回id/name/status/type/version|
|ScpClient.page|固定GET指定池内HOST/CLUSTER/SERVER分页，SERVER额外指定project_id；逐行复核pool/project/id，重复对象、错页或不前进游标拒绝，不接受URL型下一页|
|ScpHttpsTransport|独立CA或系统信任、主机名/IP验证、不重定向/不使用系统代理、10秒请求与2MiB流式响应上限；唯一POST为认证，其他资源写方法不存在|
|ScpPreparationCheck|离线配置检查CLI；只验证配置引用，不连接SCP或启动应用，始终integration_ready=false|

当前资源读适配固定为`JANUS_20180725`，严格识别旧code整数0及公共分页包裹格式；20240725的errcode响应明确拒绝，不凭返回内容猜API版本。各接口文档中省略外层包裹的示例不作为允许任意包裹回退的理由；尤其V2公钥接口须在现场取得真实响应后再次核验。

SCP端口可达、配置齐全、认证成功、源对象范围一致和MOP授权是不同层次。本批内部Scope只核对显式SCP资源池/项目，不替代当前MOP成员/租户授权；公开API、当前映射仓储、轮询Worker、历史持久化和SCP页面仍待后续联调接线，不能从DTO或配置字段推断这些已完成。

## 数据和失败语义

原始数量使用BigDecimal并输出十进制字符串，保留MHz、MB字段及ratio，不将0.45擅自改为45或推导单位/业务阈值。未知指标缺省不填0；sourceStatus保持原值，不把未知厂商状态映射成正常。VM使用cpu_status等文档字段，主机/集群使用cpu等字段。DTO只投影白名单，密码、密钥对、描述正文等额外源字段不向上游返回。

分页返回单个明确pageNumber/pageSize/reportedTotal/nextPage，不自动爬取所有页、不宣称跨页一致快照，completeInventoryVerified恒false。最大100条、页码技术上限10000；额外数据和重复ID不静默截断/合并。源接口未提供可靠采样时刻，因此sampledAt=null，receivedAt仅表示本次响应接收时间。

密码模式复用服务端有效期内Token，提前30秒视为到期并在下次显式读取时重新申请；认证失败、权限拒绝或429不隐式重试，不退回另一认证方式。60秒技术退避限制重复认证，不改变SCP账户锁定策略。认证请求结果未知时不自动重发。字段、HTTP、JSON、源错误均只输出固定错误类别，响应正文/凭据不进入异常、日志或报告。

## 配置与当前缺口

[模板与运行说明](../deploy/scp/README.md)已保存；本机已在Git忽略的`deploy/private/scp-connection.json`准备disabled配置，并关联既有HCI测试资产的MOP标识。SCP池/项目ID、认证方式、版本Cookie策略保持null，尚未填写或使用真实SCP只读凭据。

仍需：实际SCP/HCI版本、专用只读AK/SK或账号、SCP资源池/项目范围、可信CA及匹配主机名/IP的访问方式。用户目录中的设备登录资料未被当作SCP专用API授权使用；原始图片/PDF与OCR文本不提交Git。

## 验证边界

[网络预检](evidence/scp-network-preflight-20261012.json)：指定测试服务器及Zabbix网络命名空间均ICMP有回应、TCP80/443开放、22超时。它不证明该地址的软件版本或SCP API可用。

[TLS预检](evidence/scp-tls-preflight-20261012.json)：从指定服务器使用系统默认信任与IP主机名验证，结果为CERTIFICATE_TRUST_OR_NAME_NOT_VERIFIED；未发送HTTP请求或任何凭据，不关闭验证继续尝试。

隔离用例覆盖RSA往返/随机性、精确签名向量、实际Token过期、登录复用与退避、V1/V2头边界、源scope、原始精度、分页、脱敏和配置失败关闭。测试不启动本机数据库/监控或模拟SCP服务，不把合成响应称为设备联调。最终数字与独立配置检查结果见[验证证据](evidence/scp-connector-preparation-20261012.json)。早期用例的lambda编译问题和超长游标错误分类断言已修正；保留输入限制，没有放宽校验促成通过。

本次不部署新API/Worker或修改现有Zabbix采集、设备、数据库与全局组件配置。SCP认证/资源读取/持续采样仍NOT_RUN；已有三台IP基础监控和128项合同状态不被本批代码测试覆盖。

最终43项新增隔离用例、70项后端相关检查及127项既有契约检查通过，根工程相关模块打包成功。实际执行私有配置离线CLI得到CONFIG_PENDING/退出2/http_requests_sent=0。[运行环境复核](evidence/scp-preparation-runtime-20261012.json)确认12个容器的镜像、启动时间与重启次数均与上一部署终态相同，PG仍V30，ready=true；本批代码没有部署到服务器。
