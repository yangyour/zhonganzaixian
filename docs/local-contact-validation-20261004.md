# 原指定服务器上的联系人与Windows运行验证

2026-10-04，原库V16–V19已逐功能执行，各1次、重复0次、最终只读0次。复用PG17.9、Keycloak26.2.4、原项目账号/命名空间；API0.34、照片/资产导入/联系人三个独立Worker实际在Windows启动。没有新建替代组件或改变远程服务生命周期。

## 真实运行与HTTP

完整根Maven verify一次通过：1406项报告、1393实际执行、13历史fixture跳过，失败/错误0；含399真实PG、6真实MinIO。73契约、927Web、类型及构建通过。58迁移保护和27进程归属隔离检查另外记录，不和根测试计数累加。六身份真实OIDC和授权租户库存35GET（17成功、13参数拒绝、1匿名、4角色拒绝）通过；当前只有2个授权租户，真实has_more=true仍未覆盖。状态/配额/监控字段保持null。

联系人页面实际执行了同一虚构联系人CREATE v1、UPDATE v2、ARCHIVE v3、390px RESTORE v4及历史[4,3,2,1]回读，恢复保留停用。未保存输入离开选择“保留输入”后，原表单仍在。未创建账号、改变成员权限或发送通知。

## 真实202丢失与原请求恢复

`node tools/verify-contact-import-browser.mjs` 是Windows本地真实浏览器检查：从Git忽略配置读原测试身份，经实际Keycloak登录；空输入/无效JSON、完全相同行未确认禁止受理。明确确认两行后，route.fetch先取得真实服务端202，再仅丢弃返回给页面的响应；没有伪造成功响应或业务数据。

原body/key在发送前保存到忽略的私有checkpoint。页面锁定未知原请求，普通GET不将其冒充已确认；原请求重试的body字节、幂等键和返回作业ID均一致。独立联系人Worker实际完成2行，产生不同联系人ID，结果固定v1；旧202回执仍是PENDING，当前作业另显SUCCEEDED。手机端同一创建者重新登录后可从作业列表回读，页面无水平溢出。最终脚本15项检查通过，见[最终DOM证据](evidence/local-contact-import-dom-final-20261004.json)。

证据文件存在时脚本在写入前拒绝重复执行。新的明确测试轮次可传受限标签，例如 `node tools/verify-contact-import-browser.mjs local-contact-import-dom-newround-20261004`；每轮使用新的明显虚构名称，不覆盖旧证据或自动重发旧业务。API/Worker须由项目启停脚本预先准备；该浏览器脚本不迁移或管理远程组件。

首轮交互REPL在故障注入时重置，已受理作业`4d8597f4-c152-46c4-a4af-acf8df104ec7`只读回查为完成2行，没有重发。独立脚本首轮又暴露等待条件过早：页面在POST进行中也显示未知标题，脚本误以为真实回包已到，关闭浏览器中断了回包；该失败记录[保留](evidence/local-contact-import-dom-attempt1-20261004.json)。修正为等待真实回包捕获/丢弃完成，并设30秒上限，原请求预先落私有checkpoint；成功轮次各自独立保存，不抹去首轮或将两轮15项累加为30项。故障是测试等待条件，不将它冒充业务修复。

## 恢复脚本保留联系人Worker

旧 `tools/asset-import-resilience-process.ps1 -Action RestartApps` 会停止全部已登记进程，但重启参数只包含照片和资产导入，遗漏已启用的联系人Worker。现在停止前核验项目及每个登记进程的归属，再依据原登记角色保留联系人选项；未明确启用时不会新增该Worker。

已实际执行五进程重启，联系人PID由27996变为30740，API/Web/照片/资产导入同时保持原角色。重新取得真实OIDC后，原202幂等键回放仍指向同一完成2行的作业；新受理的1行任务也由重启后的Worker完成。远端PG/Keycloak/MinIO及隧道没有重启。见[实际恢复证据](evidence/local-contact-worker-restart-20261004.json)。这不是Windows处理中强杀/租约fence2验收，不沿用niko在途故障结果。

客户通知策略/渠道验真、实际送达、物理手机/企微及完整M2-9/128项继续保留未完成状态。整体证据见[本地组件记录](evidence/local-component-validation-20261004.json)。
