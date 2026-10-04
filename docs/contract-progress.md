# 合同追踪一致性索引

本索引读取原始提取清单 `doc/development/evidence/contract_requirements.json` 与权威追踪表 `doc/development/07_合同逐项开发追踪.md`，不替代逐项实施及验收。

## 当前读数与边界

截至本次生成：128项全部保留，30项表格行有进度说明，98项没有行内进度说明；128项正式执行状态仍为 `NOT_RUN`。有说明不表示需求完成，没有说明也不证明没有代码。测试数量、HTTP响应数量、切片数量均不能换算成合同完成百分比。

工具检查身份、标题、范围、源定位、TC目标、唯一状态与本地链接文件存在性。它不核验链接证据内容、真实性、外部URL、浏览器表现、客户批准或完整验收。即使手工将所有状态声明为PASS/DONE，生成器仍输出 `completion_verified=false`，等待独立证据审查。

## 修改和校验

先修改权威追踪表及真实证据，不直接编辑生成JSON；随后执行：

```sh
python3 -m unittest discover -s tools -p test_contract_progress.py -v
python3 tools/contract_progress.py --write
python3 tools/contract_progress.py --check
```

将追踪表、证据和 `docs/contract-progress.json` 同一功能提交。CI只检查索引一致性，绝不将该绿色检查标记为项目验收通过。索引含两个源文件SHA256，无动态时间或自引用提交SHA；相同来源生成相同字节。只读check遇到陈旧文件失败，不自动覆盖。

私有运行目录、跨仓库路径、文件URL与含用户名/密码的链接拒绝索引；不读取链接内容、不访问外部URL。源码输入上限2MiB是工具资源限制，不是合同业务限制。

## 本次验证

17项新增纯文本/文件路径测试及全部111项Python测试通过。没有启动新组件、创建账户、修改业务API/迁移，未新增真实组件或浏览器验收。详细记录见 [验证记录](evidence/niko-contract-progress-20261004.json)。
