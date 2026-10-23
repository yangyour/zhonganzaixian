# P03 有界只读运行探测

本项补充真实请求执行能力，不把已有验证器或空数据当成实际压测。`tools/readiness_load_probe.py` 复用原主目录受控私有配置，通过已固定主机密钥及项目归属核验的SSH连接，只向指定服务器回环10010端口发送GET健康探测。默认NOT_RUN；`--execute`才连接，不新建本机数据库／缓存／监控、隧道监听或替代服务。

```powershell
py -3.11 tools/readiness_load_probe.py --execute --requests 3 --rate 1 --seconds 10 --endpoint live --output .local/live-probe.json
```

只接受live／ready两个固定端点。最大300请求、10请求/秒、60秒HTTP执行预算、并发固定1；每次请求还受最多5秒剩余总期限和主动关闭通道的watchdog约束，慢速持续响应不能无限延长。慢请求后不补发突发流量；不跟随重定向、不带登录Cookie／令牌、不调用业务写接口。输入边界在读取私有配置前校验，输出文件排他创建；原始响应、异常和连接资料不进入报告。

逐样本记录状态码、客户端经SSH观察的耗时和明确结果。要求HTTP200及实际remote_test模式；live还须UP，ready还须布尔ready=true。失败、超时、非预期响应及预算不足保留FAILED_OR_INCOMPLETE；不会用成功子集替代请求总数。目标延迟未确认保持null，报告固定latency_acceptance=NOT_ASSESSED、formal_scale_acceptance=NOT_RUN、production_ready=false。live不证明数据库／存储就绪，客户端耗时也不是事件到通知渠道的合同延迟。

2026-10-23 10:55 EDT，在指定真实测试服务器执行3次live GET，1请求/秒、并发1，3次均为HTTP200／remote_test／UP；客户端观察63/79/79ms，未设置或签收延迟目标。业务请求0、数据库写入0、组件生命周期变更0。见[有总期限保护的真实执行证据](evidence/gap-p03-readiness-20261023/bounded-live-probe.json)，包含生成器SHA256；[首轮执行](evidence/gap-p03-readiness-20261023/live-probe.json)保留为补强前的历史证据。9项纯单元通过，覆盖边界、空／伪造成功、错误脱敏、慢响应、取消通道和不覆盖输出。

P03仍缺实际隔离恢复、故障切换与合同规定规模／持续时长的压测。当前只读小样本仅验证执行器与真实路径可用，不能外推12500 NVPS、2倍峰值、事件至渠道P95、RPO/RTO或客户签收。更大负载及故障演练需要确认资源／隔离范围和窗口；本工具不启动相关操作，也不改总清单或128项正式TC。
