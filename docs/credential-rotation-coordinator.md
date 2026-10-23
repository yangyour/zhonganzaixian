# 凭据轮换协调接口

`tools/credential_rotation.py` 实现引用级轮换状态机和只追加本地意图日志。它没有配置任何KMS、数据库或账号后端，没有CLI自动执行入口，不携带密码／token。业务调用需明确注入经授权的后端；未注入返回AUTHORIZED_BACKEND_NOT_CONFIGURED，不能视为轮换完成。

`RotationCoordinator(private_journal_root).advance(request, backend=None, retirement_approval_ref=None)` 的request只接受rotation_id、scope、active_version、candidate_version、expected_generation、authorization_ref。版本与授权值都是不含秘密的引用；原始凭据不能通过这个接口传递。每个scope只有一个排他创建的锁，未解决轮换阻止另一轮进入；重放相同rotation_id但不同参数拒绝。日志目录必须由调用者放入Git忽略且权限受限的私有位置，Windows ACL由现有受控目录保障。

后端接口要求（必须绑定实际组件后再验证，不能用本地替身作为联调）：

| 方法 | 责任 |
| --- | --- |
| `authorize(request)` | 校验调用者／租户范围、版本引用和切换授权，只有布尔true才允许继续 |
| `validate_candidate(request)` | 使用候选版本验证既定身份和受限能力，不修改全局配置，只有布尔true通过 |
| `activate_compare_and_set(request)` | 按scope、旧版本和expected_generation原子切换；rotation_id作为幂等操作标识 |
| `observe(request)` | 返回active_version、generation、activation_operation、candidate_usable、previous_revoked；不能返回秘密 |
| `authorize_retirement(request, reference)` | 独立核验旧版本撤销授权，原切换授权不能替代 |
| `retire_compare_and_set(request, reference)` | 原子核对新版本、expected_generation+1及rotation_id仍有效后撤销旧版本 |

状态顺序PREPARED→STAGED→ACTIVATING→ACTIVE→RETIRING→COMPLETE。ACTIVATING／RETIRING在外部调用前通过独占新文件写入并fsync；超时或进程中断之后只observe，不自动重发切换／撤销。确认激活需同时匹配新版本、下一代数、当前rotation_id及实际可用；确认撤销还需previous_revoked=true。未给独立撤销授权时停在ACTIVE并保留旧版本；并发代数变化、他人操作、不明结果一律不能继续撤销。协调器不自动回退旧凭据。

日志只包含请求引用、时间、状态、前条摘要及独立撤销授权引用；严格核对顺序、请求不变性和前条摘要。损坏／部分写入日志必须人工调查，不自动repair或删除；已有他人锁不删除，进程崩溃留下的锁也不按时间清理。日志链用于发现损坏和错误顺序，不替代外部可信审计、数字签名或抵抗有目录写权限者的重写。COMPLETE仅代表后端观测达到本次轮换协议，不代表生产批准。

2026-10-23：16项纯单元通过，包括先记意图、两段授权、候选失败零写、激活／撤销超时查证、中断恢复、代数冲突、旧版本保留、重放幂等、日志篡改／缺损和锁拥有权。测试后端只有合成引用和内存状态，没有真实凭据／数据库／KMS或文件配置切换。真实后端产品、受限scope、授权和代数语义尚待核验，业务API、CSRF、真实审计与运行消费者重载接线未完成，不宣称完整凭据轮换已上线。
