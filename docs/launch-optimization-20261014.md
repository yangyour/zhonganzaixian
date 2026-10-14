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


前端最终固定Vitest/mocker 4.1.11，保持Vite7.3.6、Vue3.5.43等主业务依赖；锁定source-map-js1.2.2，旧Tinypool随测试框架迁移移除。1899项回归与类型/构建通过，根工程和前端完整官方审计均为0项（本次公告数据库范围内，不是无漏洞证明）。修复依据：[Vitest公告](https://github.com/vitest-dev/vitest/security/advisories/GHSA-82fw-gwwq-j7x9)、[Tinypool调用选项](https://github.com/advisories/GHSA-85c8-ppgw-ccpr)、[source-map-js公告](https://github.com/advisories/GHSA-68fv-2mgg-jv7q)。

安装曾被npm10.9.4的[optional peer解析缺陷](https://github.com/npm/cli/issues/9787)阻塞，使用临时`npx npm@11.19.0`在隔离目录更新锁文件，未修改全局npm、未使用force或legacy-peer-deps。npm11.19.0官方Node范围支持本机22.22.1。生成锁后，npm10.9.4普通`npm ci --ignore-scripts`在干净目录和工作区均成功；17个变更包的SRI逐项与npm官方精确版本元数据匹配。镜像仅用于获取依赖，审计与摘要比对使用官方注册表。后续锁文件更新可使用同一临时npm11命令，普通CI继续按锁安装。

新增CI高危/严重已知公告门禁：审计不可用时失败，不能把网络/镜像错误当作0项；本次中危也已消除。[脱敏修复证据](evidence/npm-security-remediation-20261014.json)与[依赖清单](evidence/npm-components.json)已同步，客户许可审批状态未变更。


## 已验证：安全响应头与组件兼容

缓存候选片段新增CSP、nosniff、no-referrer、DENY和Permissions-Policy。同源脚本且不允许内联脚本/eval；禁用object/base改写/被iframe嵌入。Element Plus需要动态内联样式，因此仅style-src保留unsafe-inline，不把该例外扩展到脚本。保留data/blob图片与本站camera能力，符合现有报障拍照/预览路径；不声称已验证真实手机相机。

本机生产构建实际响应头启用该策略、API经SSH连接原指定服务器：28个业务页面无非预期CSP违例，表单/弹层可用；受控内联脚本被拦截，blob图片可解码。页面也增加no-referrer元信息。候选在原Nginx1.31.6中nginx -t通过，未修改运行配置或reload。使用时应替换同名安全头和gzip配置，不能与旧配置重复叠加。新增5项安全策略拒绝测试及1项压缩策略测试，检查脚本通配/unsafe-eval、丢失防嵌入头、图片预览破坏和头继承等回归。

## 已验证：首屏与静态传输

登录入口的背景图现在由小型启动入口提前发现，以high优先级预加载；业务页面入口不会请求该背景。真实浏览器观察到单次图片请求、initiator=link，图片请求早于主脚本结束；仅是加载顺序证据，不是LCP达标证明。原PNG约1.9MB，本轮未改变图片内容或编码。

构建门禁新增必需CSS gzip预算96KiB，与现有JS256KiB一样属于工程回归预算。最终工作区构建JS170440字节、CSS54694字节，83个文件与发布清单/ZIP摘要核对通过。完整回归1899项、契约130项、发布/安全策略16项、类型及构建均通过。

当前服务器的主JS请求Accept-Encoding:gzip，实际传输471101字节且无Content-Encoding/Vary，已确认尚未压缩。按[Nginx官方gzip说明](https://nginx.org/en/docs/http/ngx_http_gzip_module.html)，候选增加静态HTML/JS/CSS压缩、Vary和最小长度；没有将认证JSON或已压缩照片加入类型列表。候选语法验证通过，压缩/安全/缓存策略均待部署后核验真实响应，不能把本地gzip统计写成已减少线上流量。

## 最终检查与仍需完成的上线门禁

- [本轮脱敏总证据](evidence/launch-optimization-validation-20261014.json)包含测试、依赖、28页CSP、后退缓存、构建和当前站观测。最终只读盘点含新增导出Worker，共13服务运行、schema V31、ready=true、production_ready=false。环境由其他并行工作更新，本轮没有迁移、部署或服务重启；前期V30证据仍保留原时间边界。
- 下一次Web发布须按[第二轮原子切换/回退流程](frontend-release-readiness-20261014.md)应用已审查片段，并核对HTML缓存、缺资源404、重复安全头、CSP、gzip和真实登录/下载。本轮未将候选配置写入服务器。
- 生产TLS/域名/证书链尚未由本轮核验。API测试默认配置仍为Secure Cookie=false、HttpOnly=true、SameSite=Strict；生产应在HTTPS入口显式开启Secure并核验实际Set-Cookie。SSO回调、证书和HSTS需要对应环境验证，不能沿用内网HTTP测试结果。
- Core Web Vitals的良好参考值为LCP≤2.5s、INP≤200ms、CLS≤0.1，需按手机/桌面真实用户p75评价。本轮没有真实用户样本或物理手机结果，没有接入外部遥测服务，也没有据模拟视口认定性能达标。
- 监控指标、正式工单/SLA、通知、备份恢复及客户Q编号仍按既有合同追踪；本轮不代填正式验收状态，不把局部技术检查当作完整ASVS/WCAG认证。
