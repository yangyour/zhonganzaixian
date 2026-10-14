# 设备 API 临时证书模式

`SYSTEM` 使用系统信任，`CA_FILE` 使用显式 CA 并校验名称，`TEST_INSECURE` 在单个客户端内跳过证书链和名称校验。测试模式只允许用户确认的 SCP `192.168.1.179:443`、AF `192.169.11.1:443`；不接受相邻地址、其他端口或跳转地址。SCP 配置的测试模式仅允许 SCP 地址。

## 指定测试服务器上的实际检查

先构建 API JAR，然后使用下列入口。工具默认 `SYSTEM`；只有显式传入 `--tls-mode TEST_INSECURE` 才使用临时例外，并要求对应私有凭据记录已有授权。

```text
python tools/check-device-api.py --device scp --tls-mode TEST_INSECURE --verify-tls-isolation --output <新的结果JSON路径>
python tools/check-device-api.py --device firewall --tls-mode TEST_INSECURE --verify-tls-isolation --output <新的结果JSON路径>
```

工具读取 Git 忽略的 `deploy/private/lan-test-connection.json`、`scp-connection.json` 和对应 `scp-password-credentials.json` / `firewall-credentials.json`。原始凭据只经固定 SSH 信任的标准输入传给 Java，不放入进程参数、环境清单、公开报告或服务器凭据文件。

它复用测试服务器已存在的 Java 镜像，将构建 JAR 按 SHA256 发布到独立不可变目录，运行一个自动删除的受限检查容器。没有 API/Web 重启、组件安装或数据库迁移。`--verify-tls-isolation` 在同一 JVM 内依次执行免凭据严格检查、所选模式的实际客户端读取、免凭据严格检查。严格检查失败不会自动切换模式。

Java 主入口为 `cn.mop.adapters.monitoring.DeviceConnectionCheck --stdin [--verify-tls-isolation]`。结果含所选 TLS 模式、是否认证、受保护读取结果及固定错误状态；设备读取被拒绝时退出 2，执行或配置错误退出 1，明确读取成功退出 0。`integration_ready` 仍为 false：一次读取没有证明租户映射、持久采样、历史或正式监控已完成。

## 应用内使用

- SCP 的 `ScpSettings.tls_mode` 新增 `TEST_INSECURE`，字段结构和 `schema_version=1` 保持兼容。`ScpClient.configured` 的所有现有版本/资源池/主机/集群/VM 读取均使用这个模式；完整资源读取仍要求 MOP 与 SCP 显式范围。`configuredForConnectionCheck` 只能检查版本，不填造资源池/项目 ID，也不能读取资源页。
- AF 的 `AfClient.configured` 接收明确 namespace、秘密引用解析后的账号、`DeviceHttpsTransport.Mode` 和可选 CA 路径；提供 `version()`、`cpuAndMemory()`，使用结束后 `close()` 注销。CPU 保留文档十进制字符串，内存按文档整数百分比读取；缺失/越界值失败，源采样时间没有提供时不伪造。
- `TLS_NOT_VERIFIED`、`PASSWORD_EXPIRED`、`ACCESS_DENIED`、`AUTHENTICATION_REJECTED`、`HTTPS_TIMEOUT` 等状态不会转为正常指标或空数据成功。测试模式始终 `tls_trust_verified=false`。
- 请求限于固定 origin，禁用代理、跳转和自动重放；唯一 POST 为认证/注销，单次总时限 10 秒、响应上限 2 MiB。HTTP 503 的立即重试提示也不会导致登录重发。

当前只读测试身份仍受密码过期/读取权限限制，因此未启用厂商指标后台轮询或替换四台设备现有 ICMP/TCP 采集。具体实测与边界见[交付记录](../../docs/device-test-tls-mode.md)。
