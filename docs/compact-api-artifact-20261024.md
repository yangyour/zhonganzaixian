# 生成OpenAPI的等价紧凑序列化

为降低大型生成物的传输体积，公共contracts/local-api.openapi.json改为紧凑JSON加结尾换行。生成器模块、接口、字段、引用、数组顺序、字符串、类型与业务规则均不改变；禁止手工删接口或以旧生成物覆盖新接口。

本次基于main 6ca0569a，修改前后经完整JSON解析深比较相等：236个路径、455个模型。UTF-8体积从2,592,862字节降到1,265,021字节。235项Node契约检查、18项离线DEMO计划检查与生成一致性检查通过；离线catalog/golden只同步源摘要，没有初始化账号或数据。

生成命令仍为node tools/build-api-contract.mjs，检查仍为node tools/build-api-contract.mjs --check。需要阅读缩进版时，可运行python -m json.tool contracts/local-api.openapi.json，将格式化输出用于本地审阅，不手改已生成文件。可读的协议定义仍在各生成器模块中。

远端紧凑blob已返回与本地git hash-object一致的SHA，上传成功但耗时较长；这仅解决一种生成物传输路径，不等于所有积压功能已推送或合并。后续集成仍逐功能保留提交、检查最新main和重新生成最终契约，真实组件与正式验收门禁不变。

本次不改Java/页面实现，不运行迁移、设备/供应商操作或部署。仓库当前只有限定分支/手动触发的source-recovery工作流，本次不触发；本地契约检查不冒充远端CI执行。
