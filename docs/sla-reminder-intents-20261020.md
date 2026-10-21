# SLA提醒去重、通知意图与独立Worker（2026-10-20）

## 本步骤

V36候选新增持久扫描队列、提醒节点表和不可变通知意图表。开单或后续生命周期事件在同一事务内创建/刷新扫描任务；新版本撤销旧租约，Worker不能用旧fencing token写入。V1–V35 SQL字节保持不变，V36未实际应用。

独立入口：`java -jar mop-worker-0.1.0-SNAPSHOT.jar --sla-reminders`。仅接受remote_test、已批准回环mop_test及受限运行角色；不注册照片消费者、API或自动迁移，不使用@Scheduled。配置示例为`deploy/examples/sla-reminder-worker.env.example`，开关默认false，连接和运行参数留空。

业务扫描间隔与节点从数据库固定合同登记读取并核对：`sla.tick_interval_sec`和`sla.reminder_progress`（30秒及0.5/0.8/1）。没有配置不使用硬编码回退值。运行租期、失败次数与空闲轮询为另行显式配置的技术参数。

### 事务与去重

- 使用SKIP LOCKED领取有界租约；同工单扫描与人工流转共用工单锁。
- 根据完整不可变历史和冻结/绑定口径计算当前时钟，只对RUNNING时钟规划节点，暂停及已停止时钟不新增提醒。
- `(tenant_id,ticket_id,clock,progress)`唯一键约束节点。重开不清空既有节点，重复冲突不创建孤立意图。
- 提醒记录先写，通知意图后写，使用延迟外键在提交时要求配对；两者与扫描推进、系统审计处于同一事务。异常回滚，过期租约只能由新token恢复。
- 意图只保存工单、节点、口径及观察时刻等最小证据，不保存电话号码、邮箱、密钥、接收人或解决方案原文。100%标记需要升级，不执行升级链。
- 成功扫描重置技术失败次数；达到显式重试上限进入BLOCKED_ATTEMPTS，新生命周期事件可重新排队。真实跨进程并发和崩溃恢复仍待数据库验证。

### 只读展示

`GET /api/v1/tickets/{ticketId}/sla/reminders`复用当前租户、权限和报障人边界，读后再次核对成员。最多六个节点；显示当前版本的队列状态、最近扫描结果与提醒证据，不暴露Worker标识或内部身份。

详情页明确展示“通知意图已记录，未路由”。没有记录不等于没过阈值，已领取租约也不证明Worker在线。字段NOT_ROUTED与delivered=false不是供应商受理或送达凭证。没有手动发送入口。

## 缺失依赖与后续范围

`deploy/examples/notification-routing-dependencies.json`保留接收对象、升级链、供应商、凭据引用、回执验签和渠道预算为空，仅说明依赖形状，不自动发布、配置账号或授权付费发送。

当前Worker只生成意图，不进行真实路由、外部HTTP发送、电话、短信、企业微信或送达回调。对应适配、额度预留、回执/死信和升级链是后续步骤。

P3日期目标若没有时长进度口径，保持CONFIG_PENDING，不凭空推断百分比；历史Q27缺失可用V35显式绑定后恢复扫描。不会用配置缺失伪造0耗时或成功。

## 验证

完整Maven verify通过：2193项执行、897项环境跳过，0失败/错误。新增2项迁移、8项仓储Mock边界/回滚调用、6项Worker配置和3项API/权限测试；Mock不等于真实PG并发证据。

2265项前端测试、141项契约、49项Python检查通过；类型、构建、发布包和安全策略检查通过。V36的19条SQL及4个PL/pgSQL函数仅通过语法解析。

实际启动独立Worker时因缺显式SPRING_PROFILES_ACTIVE配置以CONFIG_PENDING退出（exit=1），未运行工作循环。V36真实数据库、供应商发送及登录浏览器验收均NOT_RUN，128项正式验收状态不变。

## 提交阻塞（2026-10-21）

分支 dot/sla-reminder-intents-20261020 已从 eb38e8776db77b98f591f629c92c3688798fdd96 创建。创建 OpenAPI Git blob 的授权请求被中断，随后按内容 SHA 查询返回404；本批尚未创建提交、启动CI或合并。代码与验证结果保留本地，待用户确认恢复 GitHub 写入，不绕过授权改用其他写入途径。

2026-10-21 01:38 UTC，用户明确回复“允许”，恢复本批 GitHub 写入、提交及通过 CI 后合并；提交结果以远端核验为准。

## 远端提交与合并

40个文件已提交为 [0d2b7b1c877597d76a87d8793a3149ea9f58e829](https://github.com/yangyour/zhonganzaixian/commit/0d2b7b1c877597d76a87d8793a3149ea9f58e829)。[分支CI 39528980253](https://github.com/yangyour/zhonganzaixian/actions/runs/39528980253)全部通过；2026-10-21 01:47 UTC以expected_sha保护快进主分支，并读取main验证为该提交。主分支CI另行跟进。本次仍未执行V36真实数据库迁移或供应商发送。

[主分支CI 39529373511](https://github.com/yangyour/zhonganzaixian/actions/runs/39529373511)也已全部通过，合并后的构建检查闭环。
