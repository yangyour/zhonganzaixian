# D04/D05/D07 UTC时间回读严格校验

## 实际缺陷与修复

审查现有前端发现三个读取入口仅依赖Date.parse或“正则+Date.parse”：报障登记operation、配置来源诊断observed_at、手工资产资料时间。JavaScript可能把2月30日/非闰年2月29日自动归一到3月，也可能接受裸数字、日期串；Date.parse还把纳秒截为毫秒，无法发现同一毫秒内updated_at早于created_at。

5项新增回归先失败（该轮101项中5失败/96通过），确认不是凭推测修改。新增公共utcInstant工具：仅0001—9999年的完整UTC/Z时间、可选1—9位小数；对整秒做ISO日历往返校验，拒绝不可能日期、24点/闰秒/非UTC偏移/日期-only/多余字符，再使用BigInt组合秒与9位小数。原字符串不截断、不重写。utcNotBefore比较精确纳秒，包含负Unix时间场景。

接入上述三个验证器；operation及资产快照还检查updated_at不早于created_at。配置诊断仅确认原observed_at格式，不把观察时间当批准、生效或监控采样；报障仍PENDING/CONFIG_PENDING，资产仍按原source/status/未采集约束。其他模块已有各自时间校验，本次未声称全面替换或所有时间入口均审计。

## 证据与界限

16新增测试（5缺陷回归、8工具边界、3原纳秒保留）纳入全792项Web通过；typecheck、生产构建、70契约/生成一致性通过。构建现有>500kB chunk建议仍在，未宣称浏览器性能通过。

构建实际三个前端验证器和ApiClient，在niko已有PG/OIDC/API0.33上只读43次GET：10配置诊断、5资产资料、20既有登记operation，共60个时间，其中10个亚毫秒时间原文保留；未新建业务样本、无POST/PATCH。该轮415条登记DTO在前后GET中摘要一致（这是本轮读取集合，不能冒称与更早375条快照相同）。脚本首次在初始化发现误用不存在的qa-viewer文件名，在任何HTTP前退出；修正为实际qa-viewerA后执行成功，保留失败与最终日志摘要。

真实HTTP只证明现有合法服务端返回被严格前端正常读取；畸形日期/反向纳秒输入为隔离单元，不称服务端已真实产生这些故障。没有DOM/390px证据，没有修改身份权限、API契约版本或V1–V19，未重启API；更早Java1348/1335执行属于前一备份差异回归，不重算成本次新执行。前备份差异19825889 CI37159495107 success已回读。

见[只读HTTP](evidence/niko-strict-utc-client-http-20261003.json)、[修复证据](evidence/niko-strict-utc-readback-20261003.json)。完整合同与真实监控/OSS/客户确认门禁不变。
