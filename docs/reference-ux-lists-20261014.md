# 资产与报障列表查询交互

借鉴隐居乡里 ListSearch：输入草稿与已应用条件分开，按查询或 Enter 应用；中文选词 Enter 不查询。资产状态快捷按钮只改变状态，不顺带提交其他草稿。条件标签支持单项移除、全部清空；零匹配提供恢复列表入口。翻页、刷新和打开/关闭详情保留对应查询上下文，草稿提示与实际结果分开。

结果条数明确为本页记录；加载、失败、尚未读取分别显示，未完成读取不显示“0条成功”。保留现有 URL、页大小、游标、取消旧请求、租户/权限上下文和写入门禁。API路径、参数和DTO未修改，不新增接口和组件。

验证：5个相关测试文件41项通过，类型检查通过；真实指定API浏览器检查了草稿/快捷状态隔离、Enter查询、零匹配/单项清除/全部恢复、分页草稿保留、资产和报障详情返回、离线读取失败及重新联网刷新。首次清空断言早于路由异步更新，等待URL稳定后复验通过；没有把等待问题改成业务代码或忽略失败。输入法边界通过合成事件单元测试，不声称物理输入法验证。

见[机器记录](evidence/reference-ux-20261014/list-interactions.json)、[零匹配状态](evidence/reference-ux-20261014/asset-filter-empty.png)、[读取失败](evidence/reference-ux-20261014/ticket-read-failure.png)。运行：`apps/web` 中执行 `npm test -- tests/listSearchInteractions.test.ts tests/ticketsPage.test.ts tests/ticketsWorklogNavigation.test.ts` 和 `npm run build`。本轮本地前端使用指定 `lan-test-20261008` 真实API，未提交业务数据、未部署测试站，不变更正式合同验收状态。
