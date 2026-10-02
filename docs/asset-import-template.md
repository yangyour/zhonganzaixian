# 合同15列 XLSX 空模板与授权下载

模板代码位于 `server/import-parser`，复用已锁定的 Apache POI 5.5.1 和领域 `AssetImportSchema`。它只有“资产导入”一个可见工作表，第1行为合同原名、原顺序的15列；没有虚构资产行、设备字典、采集方式、日期规则或IP冲突策略。

## 独立生成

使用项目JDK17/Maven3.9.x，先测试并打包现有解析器模块：

```sh
mvn -B -ntp -C -pl server/import-parser -am package
java -cp server/import-parser/target/mop-import-parser-0.1.0-SNAPSHOT.jar \
  cn.mop.importparser.AssetImportTemplate asset-import-standard15.xlsx
```

输出路径必须以 `.xlsx` 结尾且不存在。CLI使用 CREATE_NEW，不覆盖已有文件，不读取私有运行配置，不连接数据库、对象存储或其他服务器，也不更改默认 ParserMain 子进程协议。这是原独立CLI切片的历史范围；后续已接入实际V0.10授权下载，见下文。

## 使用及限制

- 在第2行开始填写真实待导入数据，备注必须填写；文件中不插入演示资产或“填写说明”数据行
- 默认列格式为文本，避免自动吞掉序列号前导零；粘贴覆盖格式或输入公式仍需用户检查，服务端继续拒绝实际公式
- 上传后仍须先读取工作表目录，再明确选择工作表位置0、表头行1，不自动创建解析请求或映射
- 未填写的模板可读取目录，但明确解析会返回 EMPTY_DATA；这不是解析成功或导入完成
- 模板属性记录 schema 版本及 CONFIG_PENDING。它不批准Q20/Q31、不执行业务预检、不预留配额、不写入资产，500行完整导入性能仍待真实验收

测试覆盖空模板列名/顺序、无样例/宏/外部链接、文本列格式、真实解析器目录和空数据拒绝、填入字符串后的原文保留，以及CLI拒绝覆盖。它们是文件/输入隔离测试，不是指定PG/MinIO/API/手机UI验收。

当前代码验证：新增4项包含于解析器29项全量，全部通过；模块打包及真实CLI生成/目录/空数据拒绝通过。见[独立记录](evidence/dot-import-template-20261002.json)。未执行真实UI或指定组件导入验收。

## 实际V0.10授权下载

`GET /api/v1/assets/import-template`只允许当前有效会话具有assets:write的padmin/msp/tadmin，拒绝所有查询参数。返回经过固定SHA256验证的4573 bytes原始XLSX，Content-Disposition固定安全文件名，明确schema及CONFIG_PENDING头；不接受POST，不创建来源/job/operation/资产，不依赖OSS。身份失败仍是JSON错误，不能当XLSX下载。共享安全过滤器设置单一no-store头。

资源位于API包的templates目录，由已验证生成器独立生成一次；API只读固定资源及校验摘要，不在持有运行凭据的API进程引入POI解析或启动解析器。资源与公开非敏感manifest的长度/hash一致，替换/缺失/损坏返回503，不自动用未知文件兜底。真实解析器验证该包内模板仍只含15列表头，无样例行/宏/外部链接，空文件实际解析仍EMPTY_DATA。

导入抽屉新增独立下载按钮；即使OSS未连接也可下载。浏览器侧先校验类型、状态、长度、schema、固定文件名、完整bytes SHA256，再创建临时下载URL；流超长、短读、摘要变化、206/JSON错误、会话/权限上下文失效均不生成下载文件。下载不替换已经选择的原文件，不自动上传、解析或导入，临时URL及时释放。系统/浏览器保存对话框和视觉验收仍NOT_RUN。

当前新增验证：4项下载授权/资源完整性、1项实际包内文件在POI/独立解析器中的结构和EMPTY_DATA检查；前端7项，Web总207通过。真实11项HTTP通过，包含3角色/重复字节验证、只读/匿名拒绝、查询覆盖拒绝和POST 405；实际前端校验模块接受原始bytes。第一次运行暴露重复Cache-Control头，已修复后完整重跑，见[独立记录](evidence/dot-import-template-download-20261002.json)。
