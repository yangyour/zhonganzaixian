# 导出文件安全响应头重复修复

2026-10-14 逐功能真实验收发现：导出文件返回 HTTP200，字节数和 SHA256 均正确，但后端与 Nginx 同时添加安全响应头，浏览器实际收到 `X-Content-Type-Options: nosniff, nosniff`。首次严格断言失败已保留，不将成功下载掩盖为全部检查通过。

项目网关现在先抑制上游同名的五项安全策略头，再按现有值各输出一次。CSP、Referrer-Policy、X-Frame-Options、Permissions-Policy 和 nosniff 的策略内容不变；文件正文、授权、过期时间、存储与后台处理不变。策略校验新增上游去重门禁，7项策略回归通过。

修复已在指定测试站应用。新不可变 Nginx 配置先在原镜像内 `nginx -t`，随后只切换 `WEB_CONFIG_FILE` 并重建本项目 Web；Web 静态源码目录保持原版本。API、采集器及其他13个服务的实例、镜像、启动时间、重启计数均未改变，`ready=true`。原配置和私有回退环境保留。

复验通过：新导出实际准备、MinIO发布、16列XLSX内容、SHA256、安全头唯一值、签名篡改拒绝、非创建者和跨租户下载拒绝、本人消息与首次已读、同请求审计不重复。自然15分钟到期与性能规模不在本次复验范围。

证据：[响应头回归](evidence/feature-acceptance-20261014/security-headers-regression.json)、[部署与13服务保护](evidence/feature-acceptance-20261014/security-headers-deployment.json)。仅修复测试环境项目网关，不改变生产就绪或完整合同验收结论。
