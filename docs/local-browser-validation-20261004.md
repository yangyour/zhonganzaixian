# 2026-10-04 本地真实页面验收

API0.34，前端基线 `9aad338`，原指定PG/Keycloak/MinIO和Windows三个专用Worker。Edge154实际运行于1440×900、1366×768及390×844；没有用Vue内存renderer替代本次浏览器。范围与结果见[本轮记录](evidence/local-browser-20261004.json)，927项Web、类型与构建另已通过。

|范围|实际完成|保留边界|
|---|---|---|
|客户联系人|新建v1、修改v2、归档v3、手机恢复v4与四版历史；恢复保持停用，未保存导航取消保留输入|联系人角色不授予账号权限，渠道未核验/未通知|
|联系人批量作业|无效JSON/重复行门禁；明确确认后真实202丢响应，原body字节/key恢复同一作业；Worker完成2个不同联系人，手机同创建者回读|原受理仍PENDING，当前结果另为SUCCEEDED；通知、实体手机/企微不在范围|
|容量与增长|明确600主机/每台30项/60秒等参数，NVPS300、单节点8221824000 bytes；600/1200两点对照；不可变草稿保存及手机重新查询|基准引用null、未实测、未批准、无采购或PoC结论|
|阈值诊断|明确一致候选，再改为恢复GT15得到CONFLICT；原阈值/比较符未被改写；修改清旧结果、离开取消和明确清除、手机阅读|effective_rule=null、publishable=false，未保存/发布规则|
|授权租户|12列、两条原授权租户、详情复用已读快照不新增GET、390px布局；35真实GET另外留证|登记数量不等于采集/在线数；监控、配额、健康仍未知；真实has_more=true未覆盖|
|采样时间|原空采样和响应Date分开；只读刷新前后资产事实完全一致，sampled_at仍null；切换资产未造样本、手机时间面板|无真实非空采样，不证明实际bfcache/可见性失效或采集/SLA|
|最新导入只读状态|API0.34重新实际上传XLSX到原MinIO，经独立解析器发现目录/隐藏工作表/第2行表头；明确0列及忽略15/16，来源v7/映射v1回读|预检NOT_RUN、can_import=false；不是正式资产入库|

## 修复的实际显示问题

增加菜单后，1366×768侧栏内容高849px而视口只有768px，原overflow为visible，品牌被压缩、底部说明被截断。现侧栏独立纵向滚动、直接子项不收缩；实际滚动后全部菜单和底部都可访问，主内容不跟随侧栏滚动。1440×900和手机原布局保留。

最终观察到的页面运行错误和Vue警告为0，所测页面无文档级水平溢出；长台账和原始单元格在自身表格内滚动。工具操作先等待控件可用/真实异步响应，未强制点击遮盖输入。早期脚本误在政策/目录仍加载时设置文件或点灰色选择框，仅调整验收等待条件后完成正常控件路径，不记为产品故障。

## 证据

- [联系人四版历史](evidence/local-ui-20261004/mobile-contact-history.png)
- [批量作业实际完成](evidence/local-ui-20261004/mobile-contact-import-complete.png)、[15项可复跑检查](evidence/local-contact-import-dom-final-20261004.json)
- [容量增长](evidence/local-ui-20261004/desktop-capacity-growth.png)、[手机已保存草稿](evidence/local-ui-20261004/mobile-capacity-saved.png)
- [手机阈值冲突](evidence/local-ui-20261004/mobile-threshold-conflict.png)、[手机租户未知字段](evidence/local-ui-20261004/mobile-tenant-inventory.png)
- [手机采样与读取时间](evidence/local-ui-20261004/mobile-sample-time.png)、[最新XLSX映射](evidence/local-ui-20261004/mobile-xlsx-readonly-state-regression.png)
- [短屏修复前](evidence/local-ui-20261004/desktop-short-sidebar-before.png)、[修复后](evidence/local-ui-20261004/desktop-short-sidebar-fixed.png)

独立浏览器脚本的首轮等待错误与最后成功分别留证；中途REPL重置后先只读核查已受理作业，没有重复提交该批次。`local-contact-import-dom-final-20261004.json` 对应最终脚本，早一轮成功不能与其重复累加。脚本的原请求body/key只在忽略目录保存，不进入上述证据或截图。

剩余：物理手机/企微、真实非空样本下的页面恢复行为，日历/工时/评论/SLA等全部控件流，以及外部监控/通知、客户规则和全128项正式验收。浏览器通过不解除这些边界。
