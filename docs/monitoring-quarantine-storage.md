# 未核验监控候选的耐久隔离存储

2026-10-07，M4-D05 / M4-14 的内部存储基础。V30 与 QuarantinedMonitoringEvent / PostgresMonitoringQuarantineRepository 保存可解析但未核验的信封，不开放 HTTP、查询列表、人工放行、自动补偿、通知或派发。V29 冲突分支尚未自动接入本仓储，不能把本切片写成未知来源已经自动入队。

## 内容与幂等

claimed_tenant_id / claimed_asset_id 可独立为空；随机未知 UUID、仅资产声明、跨租户声明都只是来源自述，不是对象归属或授权。不使用业务资产外键，不修改正常事件。原因 UNKNOWN_MAPPING / CROSS_TENANT_CLAIM / SOURCE_KEY_CONFLICT 由未来内部判断提交，当前构造行为不验证该判断。状态固定 HELD_UNVERIFIED，source_authorized 与 dispatch_allowed 恒 false。

候选指纹包括版本域、来源键、声明字段、原因、源时间秒/纳秒和原字节 SHA-256，排除接收时间。空声明编码为长度 -1，与空串/零 UUID 分离。数据库生成指纹与 Java 一致，9 项纯领域测试含独立 Python struct/hashlib 固定向量。同源键的不同内容/时间/声明/原因分别保留；相同候选并发重试只保留一条，返回原 ID 与首次接收时间。收据只证明该隔离记录已提交，不证明来源被认证或已通知。

两个存储仓储的写事务均显式设置 LOCAL synchronous_commit=on，覆盖连接池会话的 off；提交后恢复原会话值。只有 commit 返回后才给成功结果，提交前失败回滚、提交后响应未知可以重试核对，关闭失败的 suppressed exception 也不泄露驱动/载荷详情。schema verifier 要求 fsync/full_page_writes=on；这仍不是主机掉电或存储硬件可靠性验收。

## 冻结与安全验证

V30 SQL SHA256：2c13cd68b1d7aff016644266c723c72330868fd6e368f6f19b9fceb17025827f。先在显式事务内预检 DDL 并回滚，核对完整 CHECK、默认值和生成式 catalog 指纹后才独立 CLI 应用一次。重复/只读检查均 0；V1–V30 全部保持已应用字节不变，API/Worker 不自动迁移。

V29/V30 verifier 均拒绝未审核的默认值、CHECK/生成式、继承父子表、重写规则、分区/RLS、非持久表，以及超出 SELECT/INSERT 的运行角色表/列权限（含 PG17 MAINTAIN、列 REFERENCES）和表/列/函数转授权。原始仓储与隔离仓储分别通过 26 项真实 PG 测试；隔离测试前后 67 张既有业务表完整行指纹相同，包含 V29 原始记录。原始 63 条与隔离 91 条合成记录在同一 PostgreSQL 正常重启后数量/完整行摘要保持一致。重复调试运行产生的合成记录不重复累计测试用例。

本轮原始仓储 4 项、隔离仓储 3 项隔离安全测试均通过；全部 Java、Python 与契约检查以[独立证据](evidence/niko-monitoring-quarantine-storage-20261007.json)为准。原指定服务器未操作；最新本地服务器/浏览器证据另由其原提交保留，不能与本独立库结果混用。真实签名/池与对象映射、非法不可解析输入处理、自动隔离接线、人工核验/放行、24 小时补扫及完整 M4-14 继续 NOT_RUN。
