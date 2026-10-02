# 资产依赖草稿持久化实施边界

状态：V7草稿schema已在niko-local-test独立执行1次，重复apply与只读check均0；原V1–V6未改。事务仓储已通过9项真实PG检查；API已接入，页面尚未接入，不是完整依赖功能完成证据。

目标：把既有`AssetDependencyValidator`的结构防环规则接入真实PostgreSQL事务，支持逐租户保存人工提出的依赖草稿。Q20真实依赖资料/影响范围和监控发布仍未确认，不把人工草稿变为已生效抑制规则。

## 拟实施约束

- 上游parent→下游child，服务端从会话确定租户与动作权限；引用必须来自同租户真实资产。跨租户/未知资产统一404。
- 在同一个幂等事务内锁定租户图版本，回读完整图、验证自指/有向环、比较expected_version、写入新不可变修订和审计。并发不同请求不能各自用旧图穿透防环。
- 保留每次图修订和提出者/时间；仅最新修订指针可更新。运行账号不给历史UPDATE/DELETE/DDL。重复幂等键原样回读，不重复追加版本/审计。
- 类型、抑制范围、enabled只能作为明确标记的proposed字段，未提供保持null；effective规则始终null、decision_status=pending、lifecycle_status=DRAFT、monitoring_applied=false，不产生监控写入、通知或资产纳管副作用。
- 先新增独立V7迁移和对应schema/ACL验证；V1–V6逐字节冻结。单独迁移命令可显式执行，API/Worker不迁移；默认check只读，重复apply执行0。
- 迁移必须兼容现有V1–V6历史验证和测试入口，不通过跳过校验或修改历史checksum解决失败。

## 验证范围

先验证真实PG的A→B→C后拒绝C→A、两并发相反边、跨租户/撤权、CAS、同键重放/冲突、写入审计与回滚、历史不可变和重新连接读回。随后接入实际API契约、HTTP权限/CSRF及页面。浏览器受当前执行环境限制，未取得实际页面证据前保留NOT_RUN；OSS阻塞不影响本切片。

仓储验证已完成的具体范围见[独立证据](evidence/niko-asset-dependency-repository-20261002.json)。当前只追加提案、不提供删除/替换/发布动作，旧修订保留；HTTP动作与实际V0.9契约已接入，页面为下一切片，详见[API说明](asset-dependency-api.md)。
