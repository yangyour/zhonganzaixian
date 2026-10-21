# 本地验证与分支整合记录 — 2026-10-21

## 执行方式

用户报告 GitHub Actions 额度耗尽，04:14 UTC 要求停止依赖 GitHub 运行。04:16 UTC 最新要求恢复将现有及后续开发及时合并到 main。采用本地验证、提交消息 [skip ci]；不删除工作流、不把跳过视为通过。额度原因仅由用户报告，未访问账单验证。

## 本次安全接入

以事件读取分支 5acc5ddbb35e12662233c2a089f8652a91d27bf4 为父提交，完整保留此前 main b9ee49d 及事件读取代码。既有本地证据：2306 项后端测试执行通过、897 项跳过；54 项 Python 检查通过。未执行真实数据库、Zabbix 或通知验收。

从客户资料提交 4b126e98d4c4237193a058d68bc4d3cbcc9ef92a 复制两份原始文件，引用原 Git blob，字节不变：
- doc/customer/2026-10-21/客户角色与联系方式.png：888344705595035e6f6dd52c31a65db1026281b3
- doc/customer/2026-10-21/运维托管平台_CMDB_CI类型与属性字典_V1.0.xlsx：e603591f8621136da5662cf2f57f7ba518fc7328

联系人资料仅作为客户输入，不自动创建账号、授权角色或发送通知。

## 尚未合并的历史开发

完整分支核对显示 feat/d07-ticket-lifecycle 包含 dot/d09、d10、d11、parallel-integration 的历史提交，但与 main 从 ee34e6b 分叉，领先 39、落后 24 个提交。与事件读取候选进行树级三方比较：573 个单边变更、22 个双边变更冲突。V33–V36 使用相同版本号但不同迁移语义，不能直接拼接或修改已应用冻结迁移。

本次仅保存两份客户资料，不宣称 feat/d07-ticket-lifecycle 全部分支合并。后续逐项整合代码、数据结构与契约并做本地回归。128 项正式验收不能据此改为完成。

## 并发 main 更新保护

合并前 main 更新到 642afcaaad000b86d9cdfbfd2245897b1fea3cdb，删除 Cloud validation 及其专用 Maven 安装检查。本次保留该删除及 ci-validation.md 更新，不恢复云端工作流。原 54 项 Python 证据对应删除前；当前本地重新执行剩余检查。

## 后续整合

历史分叉的逐项解决方案和验证边界见 [分支整合记录](branch-integration-20261021.md)。新候选不代表数据库已迁移或正式验收完成。

## 最新状态

历史开发已通过 b5c1bd0b2039871bfcec109ec01a43d97ff196ae 合入 main，核对时其余 28 个分支头均已包含在 main 历史中。随后 CMDB 来源目录、只读 API/UI 及 V1–V45 冻结证据继续提交 main。真实 PostgreSQL/HTTP 本轮结果见 [验证记录](real-business-validation-20261021.md)；未恢复 Cloud validation，后续保持本地验证后及时提交 main。
