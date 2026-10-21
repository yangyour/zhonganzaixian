# 服务档位/SLA 实库联调与冻结快照命名修复

候选基于 main 296cac7。仅临时、隔离、loopback PostgreSQL/API和合成账号；无客户费用或外部供应商操作。

## 实际缺陷

首轮两项服务档位PG测试通过，服务档位HTTP读写、零/未知费用、重放与拒绝检查正常，但工单SLA查询返回503。原因是 PostgresTicketWorkflowRepository 继承了Spring HTTP ObjectMapper的SNAKE_CASE策略；V33数据库冻结快照及SQL守卫明确要求openedAt、sla.startedAt等camelCase字段。普通PG测试使用默认mapper，未覆盖API配置。

修复仓储使用独立copy并固定LOWER_CAMEL_CASE及ISO时间。不会改变共享HTTP mapper，不改变响应字段命名，不修复/重写已存快照或摘要，不修改任何迁移。服务档位PG测试改用与API一致的SNAKE_CASE注入，真实验证存储独立性。

新增4个纯Java测试：HTTP策略不能改变冻结键名、worker/API往返字节一致、未知字段拒绝、严格重复键检测保留。V33本来就拒绝缺少camelCase时间来源的记录，因此不是通过兼容任意损坏JSON掩盖数据问题。

## 实测记录

- attempt-1：2PG通过；24HTTP中的SLA查询503，整体FAILED。失败与清理摘要保留。
- attempt-2：修复后重建API工件并更新测试运行类路径；新数据库执行V1–V45，2PG、24HTTP、2类真实档位回执+1个真实SLA查询的生产TS工作区回放全部PASS。
- HTTP涵盖匿名/客户/msp越权、明确0分与null费用、0设备与null设备、两次同键重放、旧版本409、小数/科学计数字符串400、拒绝后档位不变及旧工单冻结SLA读取。
- PG验证3种在途状态的冻结SLA截止时间不随档位变更而改变，并验证变更历史不可修改/删除及同步约束。
- 读取器回放使用真实HTTP纯合成捕获，不是实时前端网络、真实浏览器或客户验收。新增3个Web与1个契约回归，夹具 fixtures/service-tier-http-synthetic-20261021.json 不含登录凭据。
- 两轮所有已启动API/PG进程及临时数据库、凭据已清理；邮件/短信/企业微信未启用。

测试驱动初次esbuild编译有函数类型断言括号错误，已在HTTP执行前修正；没有把该编译失败算作产品测试通过。

## 当前边界

后端根验证3783项=2787执行通过+996环境门控跳过；具体Web/契约/构建结果见 evidence/service-tier-real-api-20261021/validation.json。V1–V45保持原字节；重复/只读检查执行0迁移，105表指纹等值。

商务审批UNVERIFIED、正式赔付/通道送达/真实浏览器及合同128项TC仍未验，不以本次修复替代完整验收。
