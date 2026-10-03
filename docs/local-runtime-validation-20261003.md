# 2026-10-03 原指定组件的本地补齐

本次 Windows 接回 `2acf9f0`，随后合并远端维保页面 `dabd9d3`。所有外部组件联调复用原服务器 PG17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z，经固定主机键SSH；没有另建组件或改变服务器生命周期。API0.26与冻结V1–V15不变。用户允许本次使用实际 `gpt-6-astra / xhigh`。

## 启停与验证入口

V15完整就绪检查会跨隧道检查真实结构、权限和存储，实测超过旧启动脚本1秒限制，API本身返回ready但脚本拒绝。现在单次请求最多5秒、总等待90秒（最后一次请求至多再5秒）；仍检查mode/durable和所选照片/导入就绪，不将超时当成功。API与两个Worker已实际启动及重启通过。

Windows的系统 `conhost.exe` 是Java直接子进程，旧归属检查误当未知解析器而拒绝停止。现在只排除对应父进程、完整系统目录路径的console host，不收编、不主动终止它；其他未知/同名异路径/缺图像路径进程仍拒绝，解析器的PID、父进程、启动时间、确切JAR及UUID临时目录守卫保留。14项隔离归属检查及真实停止/重启通过。

`tools/capture-test-session.mjs --all` 使用原私有配置逐一完成六身份的真实浏览器OIDC登录，核验issuer、PKCE与角色，把会话写到忽略的 `.local/designated-test/private/sessions/`；单角色旧用法保留。重启后旧API会话401，重新登录同一真实身份再验证，不假称会话跨进程有效。

指定环境维保验证：先捕获六身份，再设置进程环境 `MOP_HTTP_ENVIRONMENT=designated-server` 运行 `node tests/niko-manual-warranty-http.mjs`。默认仍是niko环境；两套会话与证据路径严格区分，未知环境或已有证据在任何业务写入前拒绝，避免覆盖历史或误报环境。实际97HTTP通过，包括11修订/11成功审计、原键回放、旧接口兼容、跨租户404、CSRF、角色拒绝、200/409混合并发和10+1历史分页。之后浏览器对同一明显虚构资产追加两条维保修订，见独立浏览器记录；不改变97HTTP时点的11条结果。

## 重启与存储断链

`tools/asset-import-resilience-process.ps1 -Action StopIdleImportWorker` 只停止有完整归属证据、没有活动解析子进程的本工程导入Worker；未知候选保留记录并拒绝。API仍运行时受理明显虚构CSV，真实PG记录source PENDING、operation PENDING/QUEUED。随后 `-Action RestartApps` 通过项目既有安全启停入口重新启动API/Web/照片/导入Worker。重新OIDC登录后，同一operation已SUCCEEDED/STORED；原受理幂等键返回原收据，重启前已完成的XLSX及映射、维保v14快照完全相同。PG、Keycloak、MinIO及SSH隧道均未重启。

另以既有故障标记机制只断开本工程的MinIO回环转发，真实就绪503；新解析202受理后原operation进入PENDING/RETRY_WAIT。finally校验本次run_id并移除标记恢复链路；Worker自然重试，同一operation变为SUCCEEDED/PARSED，独立解析器输出1行、原文件SHA256一致、就绪恢复200。没有改服务端端口、停止MinIO、直改job状态或用替身返回解析成功。

这覆盖已受理任务重启与原服务器PG/MinIO/独立解析器全链路的存储断链重试。尚不覆盖解析进程运行中被杀、过期租约fencing全链路、PG/WAL故障/恢复演练或OSS认证。业务预检仍NOT_RUN、can_import=false，Q20/Q31不因此确认。

## 实际计数和证据

最终JUnit报告968项，实际执行955、历史fixture跳过13、失败/错误0；实际执行含295项指定PG、6项指定MinIO。首次完整verify因一处会话时区文本断言失败停在persistence；修复后该8项定向通过，再续跑剩余reactor模块并构建通过。计数按最终112个suite去重，不表示首次完整命令一次全绿。51契约、574Web、类型/构建通过；不重复累加云端结果或本地重跑。

证据：[组件总表](evidence/local-component-validation-20261003.json)、[维保HTTP](evidence/local-manual-warranty-api-http-20261003.json)、[待执行任务重启](evidence/local-import-restart-20261003.json)、[真实存储断链恢复](evidence/local-import-storage-recovery-20261003.json)。完整128项、正式客户规则/身份、监控/通知与OSS等未完成范围继续保留。
