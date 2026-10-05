# HTTP IP 验收入口的浏览器兼容

2026-10-05部署预检发现：旧前端34个源文件直接依赖 `crypto.randomUUID()` 或 `crypto.subtle.digest()`。它们要求浏览器安全上下文，普通HTTP IP入口不同于可用的localhost开发入口，会影响创建草稿、原请求幂等键、附件及不可变来源校验。规则依据：[MDN randomUUID](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID)、[MDN subtle](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/subtle)。

新增统一 `browserCrypto`：有原生UUID时沿用；HTTP入口使用浏览器 `getRandomValues` 生成RFC4122 v4标识，没有安全随机源则明确失败，禁止Math.random替代。SHA-256优先原生digest；接口缺失时使用固定版本 `@noble/hashes` 2.4.0的SHA-256实现，输入复制及TypedArray/DataView偏移保持。原生digest发生错误仍失败，不隐藏错误或跳过完整性验证。参考：[库的官方用法](https://github.com/paulmillr/noble-hashes)，MIT许可、锁文件固定制品完整性；没有新增网络运行依赖。

11项新增测试包含UUID版本/variant、1000个随机标识、缺失安全随机源、空/ASCII/Unicode/100万字节与Node独立摘要对照、子视图偏移和原生错误。全量98文件1671项Web测试、类型与构建通过。业务字节编码、SHA-256期望、原请求保存/重试与API0.44契约不变；实际HTTP IP页面控件验证随后在服务器部署中单列。

合并复核补充：固定版本库的原MIT许可证复制至apps/web/public/third-party-notices/noble-hashes-2.4.0-LICENSE.txt，并逐字节确认进入静态构建产物；不据此代替全部组件许可/生产批准审查。
