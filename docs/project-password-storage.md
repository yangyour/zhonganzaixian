# 项目本地口令编码基础

## 已实现与依据

本切片新增`ProjectPasswordEncoder`，实现既有Spring Security `PasswordEncoder`接口，使用成熟`Argon2PasswordEncoder`及`DelegatingPasswordEncoder`，不自制密码算法。编码标识`{mop-argon2id-v1}`；Argon2id v19、19MiB内存、2次迭代、并行度1、16字节随机盐、32字节输出。每次编码由框架创建新盐。显式依赖bcprov-jdk18on 1.84，复用当前依赖缓存版本，不采用未核验脚本。

选择依据：[Spring Security 6.5口令存储](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)说明使用自适应单向编码、可升级格式及Argon2所需BouncyCastle；[OWASP口令存储建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)列明Argon2id 19MiB/2次/1路基线。成本是本编码版本的技术参数，不是用户已批准的密码长度、历史或锁定规则；尚未完成目标服务器并发容量/登录耗时验证。

## 边界

- 不注册Spring Bean、不修改安全过滤链、登录路由、会话设置、数据库、已部署应用、共享Keycloak或实际账号
- 不创建/保存真实凭据，不把单元测试随机盐当作实际测试身份授权
- 不trim、不Unicode规范化、不在72字节截断；完整UTF-16代理对保留，孤立代理拒绝，避免UTF-8替换产生碰撞
- 输入必须非空，最多4096 UTF-16代码单元为防资源滥用技术上限；未配置最低长度、字符组合、历史、失败次数、锁定时间、额外闲置或强制改密规则。空格保留为实际字符，不暗中加组合限制
- 存储值只接受当前明确版本/算法/成本/长度与规范无padding Base64。未知版本、明文、noop、普通SHA、bcrypt或超大成本参数均拒绝，不自动降级或为损坏值做高成本计算
- matches失败返回false；encode/upgrade无效输入仅固定错误，不包含输入、存储hash或底层cause。当前支持版本无需升级，未来版本需显式增加有界读取映射和重新编码迁移，不称已迁移已有账号

## 后续集成要求

必须通过受保护的真实账号仓储加载带版本的编码值，登录统一失败响应与未知账号恒定成本策略，验证成功后再建立/轮换会话；停用/改密/管理员重置/撤销应使旧上下文失效。控制并发编码资源与防自动化保护仍需实现，不以单个19MiB成本声称抵抗登录洪泛。

口令不能进入审计正文、请求日志、幂等普通SHA摘要、错误、URL或证据；后续改密/重置幂等设计必须避免对低熵明文生成可离线猜测的普通摘要。Java/Spring内部使用不可变String，本类没有宣称JVM堆内副本可可靠清零；不记录或返回原口令。业务密码策略null不等于可绕过接口鉴权、CSRF、CAS和审计。

本轮只用明确合成字串做本地库级单元测试；没有真实登录、PG、HTTP、账号持久化或浏览器证据。V1–V26及API0.44.0不变；正式身份切换及128项合同验收仍NOT_RUN。

## 独立算法对照

除框架往返测试外，用现有PyNaCl1.6.2/libsodium Argon2id kdf独立计算公开合成字串、固定仅测试盐的32字节向量（opslimit=2、memlimit=19456*1024），由Spring/BouncyCastle核对成功，改一个输入字符失败。固定盐只用于该公开单元金样，实际encode仍由框架随机生成。未生成实际用户或凭据。
