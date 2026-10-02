# OSS原生存储适配核心（尚未接入真实云验证）

用户于2026-10-02指定阿里云OSS北京区域。新增`OssStorageSettings`与`OssProjectStore`，复用官方Java SDK 3.18.5；只提供现有照片和原始导入源接口的实现，不创建桶、不改桶权限/版本控制、不删对象、不产生公共或预签名下载地址。最初核心切片未接入工厂；后续接线见文末。真实认证、权限及对象操作未执行，不能据隔离测试宣称OSS成功。

## 配置与隔离

要求显式`OBJECT_STORAGE_ENABLED=true`、`OBJECT_STORAGE_PROVIDER=oss`、HTTPS北京endpoint及相同public endpoint、region、bucket、access/secret、可选security token、`OBJECT_STORAGE_PRESIGN_EXPIRES`。当前仅支持用户指定北京endpoint，拒绝HTTP、任意主机/CNAME或跨区地址；不猜默认凭据、桶或前缀。所有凭据只允许受控私有配置注入，配置对象toString不输出凭据。

额外要求明确`MOP_OBJECT_STORAGE_PREFIX=mop-niko-test/<独立部署UUID>/`。数据库中的`photos/<tenant UUID>/<file UUID>`和`asset-import-sources/<tenant UUID>/<source UUID>`逻辑键保持不变，实际对象键追加独立前缀，禁止路径跳转或跨类型键。前缀一经使用必须随持久记录保留，重启不能随机更换。

用户提供的3600秒只是潜在存储预签名配置；本核心没有预签名能力，不把它改写成平台15分钟授权grant，也不绕过会话/租户授权。当前有效范围1..3600秒，缺值或超限拒绝。

## 不可覆盖与真实前置条件

使用V4签名、TLS校验、关闭重定向、限定连接/请求超时，保持CRC校验。简单PUT最大20MiB，不使用multipart；设置Content-MD5、私有对象ACL、`x-oss-forbid-overwrite=true`，完成后重新读取并校验原始长度/SHA256。照片仍只接受已核验的JPEG/PNG媒体声明且≤5,000,000 bytes，导入≤20MiB，内容格式验证仍在上游领域/解析器。

已存在对象只读校验，匹配时幂等成功、不重复上传；不同内容返回409；条件竞争FileAlreadyExists也必须读回匹配才成功；权限错误不当作对象不存在；请求结果不确定返回503，不能显示存储成功。错误只记录异常类，不输出SDK响应、授权头或私有正文。

重要：[OSS PutObject官方说明](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject)明确指出，版本控制Enabled和Suspended都会使禁止覆盖头失效。因此只接受“从未启用版本控制”的返回状态：SDK返回对象中的Status缺省或Off；缺少整个响应、其他状态、权限/读取错误均拒绝。每次写入前重新核验，并要求桶ACL为Private。不会为了通过测试关闭或暂停桶版本控制，也不会修改既有桶策略。

这些只读前提不证明账号对象读写权限、前缀IAM、匿名拒绝或桶策略安全；必须通过独立真实验证。运营上也必须防止管理员在写入期间改变版本控制语义。该检查不是对既有桶完成安全审计的声明。

## 已验证与边界

17项SDK交互/纯字节测试覆盖配置缺项/敏感信息隐藏、TLS/V4/重定向、版本和ACL门禁、元数据异常、单次条件写、输入副本、存在匹配/冲突、并发条件冲突、AccessDenied、超时、读长短/摘要、20MiB及非法键。测试使用隔离SDK替身，不提供运行时内存存储，不替代真实OSS验收。

根Maven403项中执行315、跳过88，0失败/错误；契约18和生成一致性通过。这里未运行新环境PG，因为本批仅适配器单元验证；前一批69真实PG/44HTTP结果仍按原证据保留，不能相加为本批。API V0.8、V1–V6、原私有配置不变。证据见[适配核心记录](evidence/dot-oss-adapter-20261002.json)。

## 后续：API/Worker接线与缺失配置保护

统一`StorageProviderSelection`：只有完全不存在OBJECT_STORAGE及部署前缀配置时才走既有MinIO路径；显式false关闭、不完整或未知provider拒绝，不能用旧MinIO凭据兜底。API保留其他模块可用并报告存储未就绪；两个Worker仍须各自显式启用、受限mop_test_runtime、批准的回环DB，导入Worker仍校验独立解析器路径。有效OSS返回可关闭的原生存储视图，SDK资源只释放一次；关闭后不再发起请求。

API和Worker关闭OSS SDK可能含私有细节的日志，仅保留自有脱敏异常类别。解析子进程继续清空父进程环境，测试明确涵盖OBJECT_STORAGE访问键、密钥和STS token，不向不可信CSV/XLSX解析器传凭据。配置草稿/内部修订拒绝已知OSS/S3/DB/OIDC运行凭据字段，允许非敏感secret_ref；不会把运行凭据当客户业务提案入库。

云端凭据仍待受控私有文件注入；本次未用聊天中粘贴的长期密钥执行认证或写入。真实niko HTTP验证只证明：不完整OSS时live200、ready503且明确照片存储未就绪，OIDC会话和配置读取仍200，fixture入口403。恢复无OSS配置后，45项真实配置审批/契约检查通过（新增运行密钥字段拒绝）。这些不是OSS云端/IAM/文件链路通过。

API公开字段仍V0.8，只补充后端选择与输入禁密说明；平台授权grant未修改，OBJECT_STORAGE_PRESIGN_EXPIRES仍不启用直接下载。原始15列映射、can_import=false与规则/配额null等业务门禁不变。后续通过独立真实OSS核验后再启动对应Worker、验证照片/源文件全链。

接线验证汇总见[独立记录](evidence/dot-oss-wiring-20261002.json)。当前统一OSS配置不代表已经取得旧MinIO两套账号的同等IAM隔离证据；照片/导入逻辑键仍各自校验，云端账号/前缀实际权限须另验。

## 私有配置文件只读校验入口

`tools/niko-test/oss_config.py`可读取受控的JSON字符串对象或简单dotenv文件；仅按数据解析，不source、不执行、不插值。文件必须为当前用户所有、无组/其他用户权限的普通文件，拒绝叶子符号链接、目录、FIFO及超过64KiB的内容。严格拒绝重复键、未知字段、非字符串JSON值、缺少配置、其他端点/桶/前缀、非法凭据字符及过期时间越界；错误输出不回显路径、参数或配置值。

调用者必须显式提供`--file`、`--expected-bucket`及`--expected-prefix`，文件也必须包含同一个`MOP_OBJECT_STORAGE_PREFIX`，不自动生成/更换部署前缀。不接受命令行凭据参数。CLI只输出`CONFIG_VALID_NOT_ACTIVE_NOT_VERIFIED`或`CONFIG_REJECTED`；即使格式正确，也不会写入runtime、创建账号、建立持久访问、启动SDK或请求OSS。接收替换凭据、批准加载和实际云验证仍是独立步骤。当前用户粘贴的长期密钥未用于此工具。

14项合成数据单元测试覆盖上述解析、范围、权限/文件类型、CLI脱敏及读取不改文件；加入CI。API契约V0.8和迁移未改变，真实OSS仍未执行。这是私有文件处理守卫，不是存储验收证据。
