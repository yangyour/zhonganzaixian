# 剩余业务接续（2026-10-24）

本次唯一新接续会话复用`E:\work\wxw9\zhonganzaixian\.worktrees\remaining-gaps-20261024`，分支`codex/remaining-gaps-20261024`，上游`origin/codex/remaining-gaps-20261024`；规范origin及组织仍为`https://github.com/yangyour/zhonganzaixian.git` / `yangyour/zhonganzaixian`。fetch后HEAD/main/上游均为`83432839`，无新增远端提交。原目录三处未提交前端修改仍保留。只读复测确认远端源码`b964701b`、活动V55、14服务运行、4台实际设备、11成功/9明确配置门禁；原始实测保存在仓库外`output/monitoring-intake-20261024/`。之后收到远端`52666007`/`22a7638b`只读设备诊断与27项真实采样证据，已正常快进吸收，不重复开发或重跑其设备流程。没有重跑迁移/发布，HTTPS和手机专项后置，M7/Q01保持。下文旧基线和先前执行顺序保留为历史。

用户随后明确要求“部署远程”。两项功能及当前已提交主干已按b964701b发布7应用，独立迁移至活动V55；462HTTP和7实际API浏览器读取通过，详见[远程发布](remote-release-20261024.md)。实际报告列表为空，非空修订/正式发布门禁仍独立保留。

报告修订已以a4b31f87推送功能分支并正常快进合入main。第二功能补齐SLA事件时长、未知来源、精确t2月度冻结回读，验证与范围见[时间来源](sla-monitoring-source-20261024.md)。本轮无部署、迁移或外发；HTTPS继续后置。

R02首个功能已完成代码与分层验证，具体范围、失败复测与剩余边界见[报告修订](report-revisions-20261024.md)。Java4166执行/1131跳过，Web5516全量及最后93定向、Node269、指定PG与3项Edge通过；Windows符号链接额外测试1项权限错误单列。下一步继续统一SLA/监控来源，当前未部署或启用外发。

用户最新要求“先忽略https问题，完善其他的”。本轮继续补齐确定的业务代码、实际调用链及验证；HTTPS暂不处理，不将其标为已解决，也不绕过证书验证或把生产就绪改为true。M7路线、手机后置、真实外发/设备操作和生产启用仍按既有边界。

项目为众安在线MOP；原目录 `E:\work\wxw9\zhonganzaixian\mop-platform`，独立开发目录 `E:\work\wxw9\zhonganzaixian\.worktrees\remaining-gaps-20261024`。规范origin为 `https://github.com/yangyour/zhonganzaixian.git`、组织路径 `yangyour/zhonganzaixian`；分支 `codex/remaining-gaps-20261024`，起始上游 `origin/main`，基线 `b4c40ab35d47802f7fb580915703cda7c3c384d1`。依据用户本轮指示、项目归属文档及两处实际Git配置核对。原目录三处前端未提交改动不带入本线。

按25组清单逐功能推进，先补R02退回/已发布报告的新修订持久化、API、原请求恢复及页面，再继续监控/SLA/通知缺口。现有实现先复用；128项正式TC不因局部功能通过而升级。V1–V55已执行SQL字节保持，任何新增迁移按共享锁协调。测试复用指定服务器，保留验证库与不可变记录，秘密仅引用原目录私有配置。每个功能验证、更新记录后独立提交推送，主干集成使用integration.lock。

本文件为本轮范围与基线，实际结果在完成后补记。

2026-10-24新增显式映射问题消费事务：raw、事件成员、业务事件和现有建单同事务，当前来源/版本重核、冲突隔离、原请求恢复及桌面入口已验证。6项指定PG和分层结果见[实施记录](mapped-problem-intake-20261024.md)。该功能不包含持续水位、自动raw调度或完整V42；运行开关保持原值，本轮未部署。
