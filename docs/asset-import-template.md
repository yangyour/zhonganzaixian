# 合同15列 XLSX 空模板生成器

模板代码位于 `server/import-parser`，复用已锁定的 Apache POI 5.5.1 和领域 `AssetImportSchema`。它只有“资产导入”一个可见工作表，第1行为合同原名、原顺序的15列；没有虚构资产行、设备字典、采集方式、日期规则或IP冲突策略。

## 独立生成

使用项目JDK17/Maven3.9.x，先测试并打包现有解析器模块：

```sh
mvn -B -ntp -C -pl server/import-parser -am package
java -cp server/import-parser/target/mop-import-parser-0.1.0-SNAPSHOT.jar \
  cn.mop.importparser.AssetImportTemplate asset-import-standard15.xlsx
```

输出路径必须以 `.xlsx` 结尾且不存在。CLI使用 CREATE_NEW，不覆盖已有文件，不读取私有运行配置，不连接数据库、对象存储或其他服务器，也不更改默认 ParserMain 子进程协议。没有新增HTTP接口，现有 V0.8 API 不变。

## 使用及限制

- 在第2行开始填写真实待导入数据，备注必须填写；文件中不插入演示资产或“填写说明”数据行
- 默认列格式为文本，避免自动吞掉序列号前导零；粘贴覆盖格式或输入公式仍需用户检查，服务端继续拒绝实际公式
- 上传后仍须先读取工作表目录，再明确选择工作表位置0、表头行1，不自动创建解析请求或映射
- 未填写的模板可读取目录，但明确解析会返回 EMPTY_DATA；这不是解析成功或导入完成
- 模板属性记录 schema 版本及 CONFIG_PENDING。它不批准Q20/Q31、不执行业务预检、不预留配额、不写入资产，500行完整导入性能仍待真实验收

测试覆盖空模板列名/顺序、无样例/宏/外部链接、文本列格式、真实解析器目录和空数据拒绝、填入字符串后的原文保留，以及CLI拒绝覆盖。它们是文件/输入隔离测试，不是指定PG/MinIO/API/手机UI验收。

当前代码验证：新增4项包含于解析器29项全量，全部通过；模块打包及真实CLI生成/目录/空数据拒绝通过。见[独立记录](evidence/dot-import-template-20261002.json)。未执行真实UI或指定组件导入验收。
