# SCP接口配置准备

2026-10-14已新增可显式选择的TEST_INSECURE及指定服务器实际Java检查入口，见[运行说明](../device-api/README.md)和[验证结果](../../docs/device-test-tls-mode.md)。下文2026-10-12的未提供凭据/仅严格TLS属于原准备阶段；当前密码认证成功，但资源读取仍因权限不足被拒绝。

用户指定SCP管理地址`https://192.168.1.179`；原HCI设备`192.168.1.170`继续使用已有IP基础监控。2026-10-12用户确认专用只读凭据尚未申请，当前仅准备接口和配置，**默认关闭、不登录、不自动启用轮询或发布到运行站点**。

将本目录`connection.example.json`复制到Git忽略的`deploy/private/scp-connection.json`。本机已保存一份disabled配置，沿用既有HCI测试资产的MOP租户/资产引用；SCP资源池/项目ID、认证方式及版本策略均保持null，没有把设备资料图片里的登录信息当作SCP专用只读凭据。

|配置项|含义|
|---|---|
|enabled|默认false；配置齐全并完成真实联调前不启用|
|base_url|明确的HTTPS源站，仅origin，不接受用户名、密码、查询参数或额外路径|
|tenant_id / asset_id|MOP当前授权租户及已有超融合资产的规范UUID；填值不等于完成数据库授权验证|
|resource_pool_id / scp_project_id|SCP资源池及项目/租户标识，须由管理员提供或经授权查询确认，不按IP猜测|
|auth_mode|PASSWORD或EC2，未选择保留null；不自动退回另一种认证方式|
|username_ref / password_ref|专用只读账号的环境变量引用名；凭据原值不写入模板|
|access_key_ref / secret_key_ref|AK/SK的环境变量引用名；应归属于已授权的只读身份|
|region / service|EC2签名范围，须明确填写；文档不同示例有不同service名，不擅自选默认值|
|legacy_cookie_required|明确的版本兼容策略；文档6.3.70起无需Cookie，实际版本未知时保留null|
|public_key_api|密码模式选择V1或V2；V1适用文档6.3.0起，V2适用6.8.0起，真实包裹格式仍须现场核对|
|tls_mode / ca_file_ref|SYSTEM使用JVM信任；CA_FILE从环境变量所引用的绝对PEM证书文件建立独立信任库。两者仍验证主机名/IP；新增TEST_INSECURE为用户明确授权的测试模式，仅允许192.168.1.179:443，详见下文|

引用名只接受`MOP_SCP_`前缀的明确大写环境变量名。示例中的`MOP_SCP_USERNAME/PASSWORD/ACCESS_KEY/SECRET_KEY/CA_FILE`是引用，不是实际秘密。CA_FILE不满足证书主机名/IP要求时仍会失败，不能仅凭拿到证书就宣布TLS验证完成。

## 离线配置检查

根工程构建后的API JAR也包含独立检查入口。以下命令只检查JSON和私有引用是否齐全，不启动Spring、API、Worker、数据库连接或任何网络请求：

```powershell
java '-Dloader.main=cn.mop.adapters.scp.ScpPreparationCheck' `
  -cp server/api/target/mop-api-0.1.0-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  --check deploy/private/scp-connection.json
```

退出2为CONFIG_PENDING，1为配置无效，0为CONFIGURED_NOT_VERIFIED。**三个结果的integration_ready均为false**；0只说明本次离线配置项齐全。输出不含用户名、密码、AK/SK、Token或资源ID，不检查凭据有效性或CA文件真实性。

## 后续接入顺序

1. 确认实际SCP/HCI版本以及证书信任和主机名/IP；文档版本不是现场软件版本。
2. 申请资源池、集群、物理主机和云主机的所需只读权限，并确认SCP项目/资源池范围。版本查询要求的系统读取权限不足时，请管理员提供版本，不因此升级为超级管理员。
3. 在受控私有环境设置一种认证方式的引用值，核验签名profile、Cookie策略和分页/响应样本。
4. 使用内部`ScpClient`显式读取，核对源对象、单位与采样时间；当前DTO仅有接收时间，sampledAt保持null。
5. 再把实际源对象映射接入MOP当前租户授权、持久采样和页面，逐功能真实验证后部署。此次未提供公开HTTP透传、写资源API、自动轮询或无权限降级。

更多范围、证据和已知限制见[实现记录](../../docs/scp-connector-preparation.md)。
