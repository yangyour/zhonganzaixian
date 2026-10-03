# M6 有界、正文全隐藏的备份差异核心

## 依赖与范围

按模块设计复用java-diff-utils，不自写文本diff算法。本切片固定io.github.java-diff-utils:java-diff-utils:4.17，使用MyersDiffWithLinearSpace，在真实该JAR上运行合成字节单元。组件来自Maven Central，严格校验模式下载；[官方版本源](https://github.com/java-diff-utils/java-diff-utils/tree/java-diff-utils-parent-4.17)、[算法源](https://github.com/java-diff-utils/java-diff-utils/blob/java-diff-utils-parent-4.17/java-diff-utils/src/main/java/com/github/difflib/algorithm/myers/MyersDiffWithLinearSpace.java)、[Apache-2.0许可](https://github.com/java-diff-utils/java-diff-utils/blob/java-diff-utils-parent-4.17/LICENSE)已核。上游LICENSE与本依赖归属说明随mop-adapters的META-INF打包并逐字节回读核对；这不是全项目依赖合规审计结论。

RedactedBackupDiff只接受调用者已授权读取的两个版本字节及租户/设备/版本UUID，不访问网络、SSH、数据库或对象存储。两个版本须在指定同租户同设备范围，错对象404先于解码；同一不可变版本UUID却有不同字节返回409。调用前仍须当前会话权限、版本读取授权与受控解密，纯UUID检查不等于用户授权。

## 秘密、规范化和大小边界

正文严格UTF-8，拒绝错误编码、代理码点和NUL二进制；每份最大131072字节、1000行、每行8192 UTF-8字节（含原行终止符）。这是当前技术保护上限，不是客户配额或设备支持列表；未支持的超大配置不能默认为无变化。输入字节防御复制，不提供正文getter，VersionInput不使用会自动打印正文的record。当前无正文、差异文本、秘密值或逐行摘要返回，也不生成可渲染HTML。

比较保留空白、CR/LF/CRLF、最后换行和原Unicode，不删除“看起来像动态噪声”的行；noisePolicy固定NONE_APPLIED_UNCONFIRMED。先比较实际受控内存字节对应行，再只输出增删位置和行数，redaction=ALL_TEXT_WITHHELD。因此口令从aaa变bbb仍是CHANGED，不会因先把两边替换为同一***而漏报。尚未实施按厂商识别秘密行或允许普通正文显示，不能把全隐藏摘要当完整脱敏并排diff验收。

线性空间算法设置250000次行比较预算和2秒单调时钟协作检查，检查中断并保留线程标志；时间/比较/行大小超限返回LIMIT_EXCEEDED，changed=null且不保留半份hunk；中断为CANCELLED，库异常为UNAVAILABLE，不吞成IDENTICAL。2秒是协作检查预算，不是独立子进程硬杀或机器性能SLO。构造时总字节超限/无效编码为400，未比较成功的结果不声明行数或有无变化。hunk坐标/长度各自先限制到1000，再做加法，避免整数溢出绕过范围；hunk列表不可变。

## 保持的门禁

IDENTICAL/CHANGED仅为本次给定版本字节对照，normalized=false、changeApproved=false、automaticTicketAllowed=false。没有已批准报备匹配、P3建单、6小时调度、真实备份对象、客户下载、设备采集或恢复回写。Q22审批/计数/噪声、Q10留存、Q28秘密与存储门禁继续有效；客户只看概况的要求不能靠此纯核心绕过。

同步修正05成熟组件文档第10节旧“按run累计”的未确认断言：与当前04/Q22及02一致，attempt/run分开记录，正式连续失败口径仍null，不以旧选型说明冒充客户已确认。

## 验证

21隔离单元通过，其中1项包含固定种子的200对小输入向量；不是221项独立测试。覆盖真实4.17增删/不变、敏感和HTML文本不回显、秘密值变化、空白/行尾/噪声保留、租户/设备404、不可变版本冲突、编码和尺寸边界、确定性比较预算/时钟超时/线程中断、不可变结果。新增坐标溢出回归先出现21项中1失败，修正每项上限后21项全通过，保留失败与最终日志摘要。

完整根1348报告=1335执行，含392既有真实PG回归、13历史fixture跳过/6MinIO排除；70契约及生成一致性通过。许可证资源随后单独package并从mop-adapters JAR逐字节核对；未宣称整套安装包许可证审计。未修改API0.33或冻结V1–V19，未重启API。前run模型4079778f CI37158825549 success已核。见[证据](evidence/niko-backup-redacted-diff-20261003.json)。M6-2/M6-3/M4-17及128项不据此标DONE。
