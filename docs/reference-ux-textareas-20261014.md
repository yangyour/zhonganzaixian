# 报障与工时长文本交互

参考隐居乡里项目 AutoTextarea 与弹窗经验，MOP 使用既有 Element Plus 控件实现：报障描述 6–12 行、工时备注 4–10 行自动高度；输入、删除、程序赋值和宽度变化后重测，超过上限内部滚动。保持原字段、校验、输入保留与未确认请求机制。

真实手机视口检查发现报障提交按钮曾位于视口下方约 1363px。已将该弹窗限制在视口内，正文单独滚动，标题与原 footer 保持可见；390×844、320×740 下按钮可达，取消后重新打开仍保留原描述。MSP 工时抽屉在桌面和390px下验证展开、缩回及收起后保留。

验证：新增控件测试与原表单上下文回归共54项通过；补充弹窗CSS后相关31项、类型检查通过。本轮最终生产构建通过，构建标识 `fb13b009902b6e9f70d671df6b9a93bd83c4232a7718464da51b4357ad7c468e`。测量单元测试与真实浏览器分开记录，见[浏览器记录](evidence/reference-ux-20261014/textareas.json)、[报障手机](evidence/reference-ux-20261014/report-textarea-mobile.png)、[320px表单](evidence/reference-ux-20261014/report-form-320.png)、[工时手机](evidence/reference-ux-20261014/worklog-mobile.png)。

API契约、数据库和组件无变化。仅使用现有指定测试环境账号与真实API读取，没有提交报障或工时业务写入。运行与验证：在 `apps/web` 执行 `npm test -- tests/autoTextarea.test.ts tests/ticketReportFormContext.test.ts tests/ticketsWorklogNavigation.test.ts` 和 `npm run build`；完整相关测试名称见日志与总代码验证。源码提交不表示测试站发布或全部合同验收。
