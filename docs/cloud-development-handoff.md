# 云端开发交接（2026-10-01）

用户最新要求“换到云端继续剩余开发”。这覆盖此前本地/Dot执行安排；仍按128项合同实施，每个独立功能验证并更新证据后立即提交推送。目标未完成，不缩减成已实现切片。实际任务设置继续为`gpt-6.1-sol`、`ultra`，启动后须核验有效配置，无法核验则明确报告。

## 历史本地已验证并推送的基线

- `84eb35a`：指定PostgreSQL/Keycloak的真实持久化与OIDC底座。
- `0722dfe`：租户配置修订与双主体内部审核，证据UNVERIFIED、不形成正式配置生效。
- `7a782e0`：客户报障耐久登记/本人及授权租户查询、同报障人已知设备分类30秒去重、原请求重试。24项纯Java、19项真实PG、21项前端、3项契约、47次HTTP契约、8次成员角色HTTP与1440/390px页面留证。正式状态、优先级、SLA仍null。
- `d2f33d09e7789804afbcf739efd3ac8528b28b61`：现有MinIO的项目图片桶/受限账号与真实存储权限验证，只有组件准备通过，API照片业务尚未验证。

用户已选一期本平台工单为权威；无需等待外部ITSM产品才登记。用户另要求照片“先支持常用的”，本轮开发方向为JPEG/PNG、每张5_000_000字节、最多3张。Q07其他正式状态/处理规则、Q08/Q09/Q14/Q16和Q28扫描/隔离、Q10保留/清理仍未确认；不能把开发方向填成客户正式批准。

## 未完成快照

分支`handoff/cloud-20261001-photos`的交接HEAD为`caff027d7e1fb2e65f0babf8b6de0369b46fe48e`，已从GitHub fetch并核验。该HEAD是中途快照，不是已完成功能。本轮云端已接续照片代码及实际API契约V0.5，但真实集成未完成，不可据代码或旧本地证据写DONE。

交接时只预留照片领域/存储接口、MinIO Java 9.0.3、策略、附件输入及客户端工具。本轮新增文件Controller、PG耐久照片任务/租约fencing、仅照片Worker、附件绑定/CAS/语义去重追加、平台原图grant、前端队列及页面连接，统一history=ADD_ATTACHMENTS；纯测试与契约见[云端验证](verification.md)。这些代码尚未真实PG/MinIO/OIDC HTTP/UI联调。V5仍未执行草稿，V1–V4保持不变；API和Worker启动均不自动Flyway迁移。

剩余照片后端源码按WIP保护快照保存，完整Maven类型检查、33项隔离API/3项MinIO、7项Worker及16项PG照片测试仍未执行。独立通过的纯核心、契约及前端功能有各自提交；WIP保存不完成照片验收，也不合并main。后续先恢复指定组件及依赖通道，复核V5执行方案和权限，再进行真实回归及HTTP/UI验证。

当前云端`develop.txt`存在，私有`runtime.json`及`known_hosts`缺失；指定SSH路径经平台代理受到目标域名策略拒绝，Maven Central返回429、Google镜像返回403。工具链/helper语法及预检不等于依赖下载、完整构建、启动或组件联调通过。继续不依赖缺项的代码；受控私有配置和网络恢复后再验证，不旋转既有账号或本机另建替代组件。

主聊天本轮只读界面核验显示GPT-6.1 Sol已选中、任务菜单为GPT-6.1 Sol Ultra，模型/强度设置为UI_CONFIGURED；代理spawn参数也按gpt-6.1-sol/ultra显式指定。当前云会话最新原生/status界面显示used 143,050/total 258K，上下文记为UI_REPORTED 258K，1M为NOT_APPLIED。底层精确窗口与自动压缩阈值仍UNKNOWN，不能据界面显示推定272000、95%或256k tokens；模型/强度服务端回显仍SERVER_NOT_ECHOED。当前Onboarding更新schema无context字段，也没有cloud-coordinator或host config工具；云端菜单没有手动窗口入口，未改无效CLI或提示词覆盖、未重启。本结论仅描述当前会话，不以原生模型最大窗口作此云会话证据，不泛断所有Cloud是否支持1M。

按以下约定继续，并先审核当前文件而非假设草稿已经正确：

1. `GET /files/policy`如实返回允许类型、5_000_000字节和3张上限、USER_DEVELOPMENT_DIRECTION、生产UNVERIFIED、扫描UNSCANNED、保留null。未配置白名单拒绝上传，不默认假成功。
2. `POST /files`仅multipart的file与purpose=customer_report_photo，CSRF/幂等/授权与类型、原图内容、大小验证；202前把真实受理内容/file/job/operation/审计/重放结果持久化。GET operation和file回读真实状态，不用临时内存充当队列。
3. 实际文件Worker基于PG耐久任务租约/fencing、重启恢复和当前授权复核，调用现有MinIO。对象key为`photos/<tenantUUID>/<fileUUID>`；条件创建，已有对象只在原图hash/size一致时认领，禁止覆写。运行账号没有删除权限，不擅自清理或启用生命周期。RabbitMQ业务仍未实现，不因PG照片消费者宣称已接入消息服务。
4. 报障可以先无图提交，图片失败不取消原登记；前端保留当前页面的File/原幂等键并允许手动同请求重试。附件追加只绑定同租户/同上传人READY图片，原记录3张总上限/CAS/事务历史和审计。30秒同报障人语义去重带新照片可追加原登记，不覆盖旧正文/联系人，不静默丢图。
5. 元数据不公开对象key、桶、秘密或原始暂存bytes。原图下载用15分钟平台grant绑定actor/tenant/permission_version，每次访问重新授权，无S3裸presigned直链；跨租户/同租户其他报障人404。客户仅本人，内部按当前授权租户读取；只有报障人追加。
6. 全部新增内容先做必要纯测试、指定PG/MinIO真实集成、真实OIDC下HTTP/契约、390/1440px页面和恢复/失败/并发验证，再更新实施状态并按功能提交。没有真手机/企微内嵌证据不能宣称拍照/H5完整验收；M5-5/M8-6仍含正式建单/SLA/推送等未完成要求。

## 环境与秘密

云端及历史本地联调均复用指定服务器组件，不在云端新建替代数据库/缓存/队列/监控。只能使用mop_test、mop-test身份域、mop-test-photos及本项目账号，不改其他项目服务/端口/全局配置。

已真实使用：PostgreSQL17.9、Keycloak26.2.4、MinIO RELEASE.2025-09-07T16-13-09Z。Redis/RabbitMQ待业务接入，Zabbix/堡垒机/通知资料等缺口见组件清单。参考本地SSH转发端口15432、18110、19000；云端须建立自己的受控隧道及核验，不能访问这些本机端口就声称联调成功。

原始连接资料、runtime环境、S3受限账号及拥有者恢复状态只在Git忽略的`deploy/private/`，此次Git交接不含它们。本地API/Web已停止；本机SSH隧道不能当成云端已有连接。云端缺少私有凭据时使用受控秘密存储/私有挂载交付；不把密码写入任务提示、Git、公共日志或截图，不打印SDK/SSH完整异常。不得旋转现有数据库/身份/S3账号密码来覆盖其他执行环境。

本轮已完整读取AGENTS及doc/development的01–07原文并核对工作区；现有工具链通过激活脚本使用，网络阻塞已重新核验并登记。全部128项追踪不缩减；当前128个TC仍NOT_RUN。完整照片范围及剩余门禁见[照片代码切片](report-photos.md)，历史MinIO组件验证不替代新增照片业务或云端复现。

后续独立纯功能已逐功能提交推送：配置筛选/建议差异、显式作用域解析/不可变快照、工单详情operation回读及资产依赖结构校验。记录见[配置比较](configuration-comparison.md)、[配置核心](configuration-resolution.md)、[状态回读](report-operation-reading.md)、[依赖校验](asset-dependency-validation.md)。这些不完成整模块；继续xlsx/csv资产导入、依赖持久保存及128项剩余实现，原客户与组件门禁保留。

导入纯预检随后完成：15列已解析行、5ERROR/3WARNING、事实缺失保持CONFIG_PENDING，14组1,479个JDK17断言通过，见[导入核心](asset-import-precheck.md)。文件解析、耐久202、preview_hash、配额、实际导入/审计及60秒验收尚未实现或执行；继续按M3-D08/D09补齐，不将TC-M3-6标DONE。
