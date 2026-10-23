# 附件扫描候选适配器

`tools/attachment_scan.py` 实现可执行的ClamD INSTREAM传输和结构化结果接口，默认不连接、不读取输入文件。只有显式 `--scan` 才会提交指定文件字节。本线未启用或安装任何扫描服务；ClamAV为待评审候选，不是已批准组件。

协议依据：[ClamD官方协议](https://docs.clamav.net/manual/Usage/ClamdProtocol.html)。使用NUL终止命令、网络字节序长度帧和零长度结束帧；只发送VERSION及INSTREAM，禁止文件路径扫描、重载、停止等管理命令。官方明确TCP没有原生加密／认证，因此本接口只允许127.0.0.1固定字面地址及显式端口，未来需指向指定环境的获批SSH隧道；不会自动开隧道、安装本机扫描器或暴露公网端口。[传输限制](https://docs.clamav.net/manual/Usage/Scanning.html)

接口 `scan_bytes(content, expected_sha256, port=None, enabled=False)`：先校验明确字节内容、SHA256及端口；最多20MiB，64KiB分帧，三次连接共用15秒总截止时间，响应最多4096字节。命令结束必须收到EOF且恰好一个NUL终止记录；拒绝截断、多条冲突、超限和非规范响应。扫描前后读取VERSION并逐字节比较，签名库变化时结论UNKNOWN；连接／超时／供应商异常不自动重试，不输出检测名称或异常原文。

CLI：`py -3.11 tools/attachment_scan.py --file <明确文件> --sha256 <已核对摘要> --output <新证据文件>`，默认输出SCAN_DISABLED。获授权且已有受控扫描端点后，才追加 `--port <隧道端口> --scan`；本轮没有执行这一模式。输出排他创建，stdout不包含文件路径／文件名／内容；退出2表示得到观测结果但无发布许可，1表示输入或输出错误。

结论只有UNKNOWN、NO_THREAT_REPORTED、THREAT_REPORTED。NO_THREAT_REPORTED仅表示扫描器对这些字节返回OK，**不等于附件安全或可发布**：签名新鲜度、扫描器文件类型／压缩包／上限策略仍未独立核验，`release_allowed=false`、`attachment_state_changed=false`始终保持。接口不移动／删除／解隔离文件，不改API/Worker现行照片或导入流程。数据库扫描台账、租户授权接线、隔离存储和批准后的发布转换仍是后续工作，不能宣称扫描闭环完成。

2026-10-23验证：13项合成socket／字节单元通过，包括分帧还原、共享截止时间、摘要不符零发送、恶意错误脱敏、关闭默认、重复输出、版本变化和多记录拒绝。未启动任何替代扫描引擎，未发送真实附件，真实扫描组件版本／权限／策略未知，正式安全TC为NOT_RUN。
