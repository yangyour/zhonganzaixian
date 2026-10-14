# 上线优化第三轮：标准、实施与剩余门禁

范围：现有前端及其发布边界。基于1514842（包含前两轮优化和已提交导出准备功能），使用隔离快照验证；真实联调继续经固定主机信任SSH连接指定测试服务器。不会将前端测试当作128项合同、生产TLS、SLA或物理设备验收。

## 官方依据与整体检查矩阵

|维度|依据|本轮落实或门禁|
|---|---|---|
|会话与缓存|[web.dev bfcache](https://web.dev/articles/bfcache)、[OWASP Web Cache Security](https://cheatsheetseries.owasp.org/cheatsheets/Web_Cache_Security_Cheat_Sheet.html)|历史快照先隐藏，恢复时新读会话；失败保持隐藏；JSON/图片请求明确no-store|
|脚本与浏览器隔离|[OWASP CSP](https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html)、[HTTP Headers](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html)|同源脚本/CSP、安全响应头候选；需真实浏览器与部署配置验证|
|首屏性能|[web.dev LCP](https://web.dev/articles/optimize-lcp)、[Core Web Vitals](https://web.dev/articles/vitals)|登录图片提前发现且只在登录入口加载；构建性能预算补充CSS；实验室数据不作为真实用户p75|
|依赖|[OWASP ASVS](https://owasp.org/projects/asvs)|执行锁定依赖审计；缺审计服务不能记作零漏洞；不宣称完整ASVS认证|
|发布完整性|[Vite 7部署](https://v7.vite.dev/guide/build)|保留文件摘要、构建检查及原子版本切换/回退方案，禁止重写已生成摘要掩盖传输差异|
|无障碍与交互|[WCAG 2.2](https://www.w3.org/TR/WCAG22/)|沿用前两轮键盘、表单、状态提示与窄屏检查；新增恢复遮罩与错误按钮验证|
|HTTPS与会话Cookie|[OWASP TLS](https://cheatsheetseries.owasp.org/cheatsheets/Transport_Layer_Security_Cheat_Sheet.html)|当前内网HTTP测试不能代替生产HTTPS、证书链、Secure Cookie及HSTS核验；不在HTTP入口盲目启用HSTS|
|运行与灾备|既有合同Q编号、组件就绪与验收追踪|前端包不证明告警、全工单闭环、备份恢复、SLA或灾备已完成；沿用既有阻塞记录|

## 已验证：浏览器历史恢复

页面进入后退缓存前同步隐藏旧内容，涵盖主页面和Teleport弹层；恢复时先等待此前读取结束，再发起新的会话读取并重新核对可达路由。失败保持隐藏并提供手动重试；同一账号权限未变时保留输入和原有写请求恢复状态，不自动提交、不把旧HTTP缓存当成当前授权。

真实Edge后退产生`pageshow.persisted=true`。已验证：正常恢复；离线恢复时资产主页面及详情抽屉均不可见；恢复网络后重新核对，原筛选输入仍在；在中转页结束自己的服务器测试会话后，后退缓存触发401，旧内容清除并显示登录失效说明。未创建业务数据。自动化仅取消Playwright默认的禁用bfcache参数，以启用普通浏览器行为；没有关闭浏览器安全策略。

复核中发现后续匿名401会抹去最初的失效原因与返回页，已修正为保留最初说明/安全页面路径；明确退出仍清除。相应回归覆盖重复401、挂起/并发/延迟完成、恢复失败、租户切换未确认，以及必须新读会话。当前隔离全量126文件/1899测试通过，类型与生产构建通过；其中首屏预加载候选同时参与验证，其正式记录见后续章节。

## 依赖审计与修复进度

2026-10-14向npm官方注册表执行包含开发依赖的完整审计：根工程Ajv有1项中危；前端工具链合计4项（Vitest传递影响重复计入包级统计，不能解释为4个独立线上可利用漏洞），涉及Vitest/mocker、Tinypool和source-map-js。原npmmirror审计接口返回NOT_IMPLEMENTED，不能登记为零；改用官方审计服务后才取得有效结果。

根工程Ajv从8.17.1固定更新为8.20.0，130项契约与生成一致性检查通过，官方复审0项。对应[Ajv公告](https://github.com/advisories/GHSA-2g4f-4pwh-qvx6)，具体影响需要$data等前提，未宣称已观测到利用。前端依赖修复及全量验证继续记录于后续章节。
