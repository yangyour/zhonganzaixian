# 本地工具检查的平台边界（2026-10-07）

Windows本机执行全部167个Python工具用例时，120个通过、1个跳过、5个失败、41个错误。失败日志保留在`.local/validation-20261007/python.log`，不能记成全量通过。其原因分为POSIX UID/权限位、Linux进程目录/套接字和Bash执行环境，以及本机没有符号链接创建权限；没有为通过测试修改Windows安全策略或削弱生产守卫。

源码归档的符号链接拒绝测试原先在准备测试文件时抛WinError1314，尚未调用被测代码。本次仅对Windows的这一确切前置错误标记SKIP，其他创建错误继续失败；Linux及具有权限的Windows仍完整执行原拒绝断言。对应21项本机结果为20通过、1明确跳过。新增Cloud validation的Linux步骤执行全部21项，避免Windows前置跳过造成此保护项失去覆盖；Linux实际执行结果另随CI回读留证，提交前不预写通过。

提交`786f4e1`的[Cloud validation](https://github.com/yangyour/zhonganzaixian/actions/runs/37559630445)已回读为success，其中原有Linux Python工具检查141项通过，覆盖UID/权限位、进程拥有权、OSS私有配置及冻结迁移等。本机另完成5项部署保护、139项迁移模式保护、29项进程归属保护、12项导出启动保护。上述数字不能叠加重复的用例，也不代表实际进程强杀、存储认证或数据库故障已验证。

指定服务器仅有Python3.6.8且没有Paramiko；一次只读前置探测后未在服务器安装新Python、修改全局环境或创建临时组件。Linux工具检查使用仓库现有CI。真实组件回归仍由Windows经固定主机键SSH连接指定PG/MinIO，另行留证。
