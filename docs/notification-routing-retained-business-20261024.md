# N01/N02 指定保留库的路由业务回归

复用最新main已有通知路由、偏好、显式规划Worker及返回授权守卫，不重复实现功能。2026-10-24在指定LAN实例上本次保留的N01隔离库执行原`NotificationRoutingPostgresTest`六项业务测试：6通过、0失败/错误/跳过。此证据在此前schema/ACL验证之上补充真实持久化事务，不表示真实供应商发送、送达、认证回执或正式合同验收。

## 验证内容

1. 持久来源发现可重复执行；缺发布策略保持待配置，不返回空的发送成功。
2. 显式发布策略及明确SYNTHETIC的“已核验绑定”夹具产生一个受当前范围约束的耐久路由；不把该夹具认作实际供应商绑定。
3. 重复规划不重复建路由；偏好撤回使旧排队路由失效。
4. 并发策略写入只有一个CAS胜者，原命令仍可按幂等记录回读。
5. 撤销成员及旧权限版本不能读取或重放。
6. 运行账号不能改写或删除控制历史。

接口和生产业务代码无新增变更。本次只扩展既有测试的显式`PREAPPLIED_V51`入口：默认`FRESH_APPLY`仍保留原先apply=1/repeat=0的独立新库语义；保留库模式完整跳过Flyway，不执行迁移、repair或建库。

## 执行与证据

在独立工作目录执行：

```text
py -3 tools/verify-notification-routing-retained.py --source-summary .local/candidate-schema-chain-attempt-1/summary.json --output .local/notification-routing-retained-attempt-1 --session local-astra-ut --timeout 600
```

入口只接受本次已审阅schema链summary或对应公共证据，核对精确库名、owner、marker、51条完整迁移history、V51首次安装时间/checksum及冻结资源SHA。Python按原证据摘要逐项核对，再用PG端规范JSON摘要供Java前后比较，不截断时间或重写首应用证据。PG版本参考此前同实例schema链实测17.9；本次工具查询版本但未将该值序列化，不把版本引用描述为新的独立观测。

仅创建两个新SYNTHETIC租户和两个无本地登录/外部绑定的主体。运行后确认Java退出且目标库0连接，再停用两个主体及四条成员记录；记录和数据库保留。发生Java退出不明、连接未关闭或停用失败时保留环境锁，不能与仍运行的测试竞争清理。

活动`mop_test`保持V50，只读访问；history、能力字典及指定实际租户保护摘要不变。保留库此前业务数据和迁移记录不变；0迁移、外发、删除、组件安装或生命周期修改，环境锁已释放。实际结果、六个testcase、执行代码摘要与前后保护证据见[业务验证摘要](evidence/local-astra-ut-20261023/n01-retained-business/summary.json)。原schema链首次证据未改写。

N01/N02继续IN_PROGRESS。尚缺真实HTTP/浏览器路由与偏好交互、持续调度生命周期、供应商派发/认证回执、死信人工流程及其他门户需求；本次不改正式TC状态。
