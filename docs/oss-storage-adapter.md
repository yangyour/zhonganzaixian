# OSS原生存储适配核心（尚未接入真实云验证）

用户于2026-10-02指定阿里云OSS北京区域。新增`OssStorageSettings`与`OssProjectStore`，复用官方Java SDK 3.18.5；只提供现有照片和原始导入源接口的实现，不创建桶、不改桶权限/版本控制、不删对象、不产生公共或预签名下载地址。目前尚未接入API/Worker工厂，真实认证、权限及对象操作未执行，不能据隔离测试宣称OSS成功。

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
