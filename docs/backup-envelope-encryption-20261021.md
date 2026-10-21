# 备份加密信封基础

## 范围与实现

新增 BackupEnvelopeEncryption，作为将来加密归档链路使用的有界、内存内加解密基础。没有存储写入、版本登记、KMS账号/密钥创建、SSH、下载端点、Spring自动装配或Worker；不是备份模块整体完成。

使用 JDK JCE 明确的 AES/GCM/NoPadding，要求32字节（256位）数据密钥、12字节随机nonce及128位认证标签，不允许退回AES-128、无认证模式或provider隐式默认模式。JCE要求AAD先于密文处理、同一密钥的IV不可重复；本实现每次新建Cipher并使用SecureRandom产生nonce，真实DataKeyProvider还必须每次create生成新的DEK，不能缓存复用。[JDK Cipher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html)、[GCMParameterSpec](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html)、[SecureRandom](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html)。这不是FIPS认证声明。

AAD绑定格式/算法、tenantId、assetId、runId、attemptNumber、versionId、不可变密钥引用及wrapped-key字节。密文、nonce、范围或绑定被改动后认证失败，不会返回未认证的明文。解密仅在一次doFinal完整通过后返回字节，没有先输出部分内容的流式接口。返回前再次检查当前授权，撤权则丢弃结果。

Access须来源于可信服务端身份；Authorizer和DataKeyProvider均为必填依赖，没有默认允许实现。跨租户/范围在密钥调用前拒绝。真实Provider必须校验当前租户、范围、批准的不可变密钥版本和KMS binding，不信任信封字段去访问任意URL或使用别租户密钥；kms-key-ref:只是一种内部引用语法，不是密钥或授权证明。轮换后必须仍能按历史密钥版本正确unwrap，不能把旧引用偷偷改为当前alias。

## 编码、大小与敏感数据

规范信封为MOP_BACKUP_AES256_GCM_V1；ASCII头字段使用DataOutputStream.writeUTF的大端长度编码，后接wrapped-key长度/字节、固定12字节nonce和密文长度/字节（含16字节标签）。读取必须提供预期SHA256和预期Scope，拒绝错误格式、范围、长度、摘要、尾随字节及非规范UUID编码。SHA256只校验传输一致性，不代替GCM认证、身份或真实性。

每个实例必须显式给出明文技术上限，允许1至16MiB；wrapped-key上限8192字节。超限拒绝，不截断。空字节可认证加解密，但不意味着有效设备配置、备份成功或存储完成。是否接受空配置应由采集/备份策略另行判断。

数组使用防御性复制。内部临时明文、密钥和未返回结果尽力清零，KeyMaterial在成功/失败时关闭，关闭后不能再次读取密钥。调用者和Provider必须清理自己持有的副本；JCE/JVM内部副本无法由此承诺完全消除。KeyMaterial/Envelope的toString不包含内容；错误不携带原始异常原因或凭据文本。

直接把原始DEK当wrapped-key会被拒绝，但该格式检查不能证明任意第三方包装算法安全，真实KMS集成仍需独立验证。对象返回storageVerified=false、deviceExecutionAuthorized=false、evidenceAccessGranted=false；不能凭加密成功填运行成功、授予正文下载或释放设备租约。

## 配置缺口仍显式保留

backup-execution-dependencies.json新增批准加密profile、fresh-DEK Provider、加解密授权器、不可变KMS密钥版本及明文技术限制引用，全部null；原执行/生产批准保持false。它们是离线依赖形状，不是已消费的运行时配置。Q28真实产品/密钥绑定未确定，不创建生产密钥，不上传用户配置。

后续还必须实现并验证：KMS适配器、对象存储原子写入/读取核验、加密版本元数据事务、幂等与未知上传结果对账、规范化内容索引、保留及恢复访问控制。重复seal会得到新的密文，不能把再次加密当成原上传的幂等重放；对象写入必须绑定既定版本/信封摘要且不得覆盖未知结果。

## 验证

22项JCE单元覆盖独立向量、精确解密、defensive copies、篡改密文/nonce/AAD、跨范围、前后授权、错误不泄漏、256位密钥要求、大小/长度/规范编码、销毁材料拒绝复用、随机nonce、空数据及原始DEK误用拒绝。

独立Python cryptography 50.0.0 AESGCM生成351字节规范信封，SHA256为6016eb9a0228c9d11b81bd86e0238cb49cbc8d4c081cff7f2443bd7ca17845d7；JCE输出逐字节匹配并成功解密。fixture包含公开可重现的合成测试key/nonce和假的wrapped-key标记，绝非生产密钥或真实KMS输出。确定性随机源只在包内测试构造器使用，不是运行设置。

实际KMS、真实归档/恢复、生产授权、浏览器及合同TC未执行；无数据库迁移，V1–V47保持冻结。完整回归计数、命令及源码摘要见证据，不以加密测试冒充端到端备份。

复验命令：根工程 Maven verify（PDF字体测试启用）共3982项，2966执行通过、1016按环境跳过；Python unittest discover 124项、Node契约173项通过。python tools/backup_envelope_vector.py --check 使用可选验证依赖 cryptography==50.0.0，只核对已提交公开合成向量，不重写文件；Java运行时不依赖Python。离线依赖模板检查仍ready=false。
