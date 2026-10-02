# 审计链只读验证

对应M2-12、通用M7-5，当前阶段为算法、授权只读仓储与真实PG验证；公开区间校验/检索接口及页面仍待实现，不能标完整合同验收DONE。

## 保持已存编码不变

V1既有规则是UTF-8 SHA-256(previous_hash + 顶层key排序后的JSON)，JSON排除entry_hash，保留Unicode、null、转义和数值。没有重新签名历史、修改写入器、迁移或重建链。每批最多1000条，输入按sequence升序；非起始区间带上一条本地记录作前驱校验。核对namespace/tenant/id/sequence/hash列与payload一致、缺失/乱序/重复ID、前后hash链接、完整字段和实际摘要。

返回只含范围、已验证数、固定失败枚举/序号、起止摘要和externallyAnchored=false，不附原始内容。VALID只表示所给区间与本地前驱在该次快照中一致：没有外部可信时间戳、签名或WORM锚，无法仅靠本库hash链证明具有全部重写权限的人未重算整链，也不证明未来记录未变化。

## 已执行验证

- 8项隔离计算测试通过：完整/部分区间、原写入器编码兼容、Unicode/null/转义、改内容/链接、缺失/乱序/多余、跨范围/列不一致/重复ID、无效前驱、严格类型和防御拷贝
- 2项真实PG只读测试通过：两租户既存5162条记录分7个有界区间，在各自REPEATABLE READ快照验证；另对真实payload的内存副本改一字段，hash校验拒绝，回读证明数据库原记录未改
- 不是向真实表注入篡改，不改变审计记录、角色、权限、schema或秘密；总记录数是此次快照数，后续正常审计追加会增加
- 根495=482执行通过（含104真实PG）+13历史fixture跳过，6MinIO排除，0失败错误

真实OSS、原服务器、浏览器UI和客户留存策略仍按现有阻塞保留。下一步接当前padmin授权的只读区间工具与有界检索，不能让客户端选择其他租户或平台namespace。

## 2026-10-02 审计只读仓储与稳定分页

AuditReader/PostgresAuditReader在REPEATABLE READ内重新核验当前启用padmin成员与permission_version；仅会话当前租户，不推定平台级或其他租户权限。按sequence倒序最多100条，第一页固定snapshot_sequence，后续before_sequence只读取该上界以前；并发正常追加不挤入旧页。actor/action/result/resource_type/resource_id/trace_id均参数化精确匹配，发生时间为[开始,结束)；字段和精度有技术边界，非任意SQL查询。

每条列表记录核对真实行/JSON envelope与本地hash；损坏/不兼容或断库503，不返回伪造正常。区间验证最多1000条、明确EMPTY/VALID/INVALID，读取本地前驱并保留失败枚举；EMPTY的valid=null，未来区间404。只查询原V1表，不提供修改/删除/修复，也不把本库链声明为外部可信锚。

7项新增真实PG回归通过：12条精确筛选记录的10+2分页、并发追加后旧快照稳定、新首页可见第13条、全筛选/半开时间、SQL注入文本无效、角色/权限版本/租户范围、区间及无写证明、默认有界/未来区间拒绝、参数边界；合成设置记录通过既有audit writer追加，读方法本身不写。根502=489执行通过（含111真实PG）+13fixture跳过，6MinIO排除，0失败错误。断库列表/校验均503守卫通过。

公开API/页面仍待接线；不宣称M2-12/M7-5完成，V1–V8和私有配置不改。上一2a58fbc的CI37035240717已回读success。见[仓储证据](evidence/niko-audit-reader-20261002.json)。
