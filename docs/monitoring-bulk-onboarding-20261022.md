# 多服务批量接入计划（后端/离线工具）

本切片借鉴模板复用和批量变更预览，减少逐台手写完整目标JSON。没有改动apps/web，前端由用户维护。目录仍为既有13种预配置，不虚增厂商支持数量。

## 已实现

- 一份资产清单可以选择Linux、Windows、数据库、容器、网络SNMP、HTTP/TCP/ICMP和Zabbix候选。
- 同一资产可配置多个相同类型服务，如22与5432端口，或`/`与`/health`。相同实际目标不能换ID或周期重复登记；默认HTTP端口/空路径、TCP前导零端口也会归一去重。
- 服务逻辑key决定稳定UUIDv5目标ID；清单排序、地址和端口变更不造成新ID。更改key表示新的逻辑服务。不要把这个ID当作已经核验的设备身份。
- 自动补齐预设技术端口/路径，明确的参数可覆盖；HTTP业务协议和TCP业务端口必须填写。采样周期未指定时保持null，所有输出目标保持disabled。
- 校验错误按资产/服务位置集中反馈，不返回部分可应用配置，不在诊断中回显地址、密码或原始输入。
- 可与上一份配置生成新增、修改、移除候选及字段差异；移除始终需要复核，工具不删除或应用任何配置。

## 使用

先从已有资产台账取得正确租户和资产UUID，复制并修改：

```text
deploy/examples/monitoring/inventory.disabled.json
```

该例子只有文档保留IP和合成UUID：3台资产、13种预配置、15个服务目标，不是真实环境配置。

```sh
python tools/monitoring_inventory.py --inventory /path/to/inventory.json --output-dir /path/to/new-bundle
python tools/monitoring_inventory.py --inventory /path/to/updated.json --previous-config /path/to/previous/targets.disabled.json --output-dir /path/to/another-new-bundle
```

不传output-dir只输出脱敏计划概要。输出目录必须不存在；已有目录、文件、符号链接均不覆盖。中断时缺少最后写入的plan.json不能视为完整包，应换新目录重跑。

生成文件：

- targets.disabled.json：兼容现有v1配置校验/渲染器的完整目标。
- plan.json：摘要、目录字节SHA256、依赖核对状态和可选差异，最后写入。
- requirements.json：所需预配置和维护者文档，状态NOT_CHECKED，不假装已安装。
- allowlist.review.json：待人工复核的地址集合，approved=false，不是有效授权。
- server.env.sh.example：POSIX shell示例，正确引用特殊字符；测试开关false，两个有效白名单仍为空。不要当成Docker env-file或自动部署脚本。

启用前仍需确认资产、安装/配置对应采集器及凭据引用、明确周期和独立批准目标，再按现有文档渲染和测试。生成计划本身不访问网络、不做DNS解析、不验证凭据、不注册资产、不创建采集任务。

## API兼容与前端交接

API契约0.79.0：

```text
GET /api/v1/monitoring-onboarding/registered-targets?view=expanded
```

- expanded显式请求同设备多服务完整列表，仍使用既有数据结构、权限、资产管理IP匹配和当前成员复核，不执行探测。
- 旧客户端不传view：原单服务配置行为不变；若配置存在同资产同预设的多个服务，返回409 CONFIGURATION_VIEW_REQUIRED，不偷偷截断，也不返回旧客户端无法解析的重复绑定列表。
- view只能是单个expanded值，未知/重复/额外参数均拒绝。POST测试接口不接受该查询参数，仍只接受目标ID+当前单目标配置SHA256。
- 用户维护前端时，需要在登记目标读取中显式选择expanded，并按target_id及具体目标处理列表，不再仅以asset_id+preset_id判重。后端未修改前端代码，当前旧页面尚未完成这个多服务交接。

## 明确边界

- 当前单实例仍最多64个目标；超限失败，不静默截断、分片或覆盖实例配置。资产清单最多64项，每项最多16个profile，最终展开后仍受64目标限制。
- 服务端当前仅支持明确IPv4；批量生成器一致拒绝IPv6和域名，避免产出服务器无法使用的配置。原单独Python工具的IPv6能力不等于API支持。
- 直接Exporter必须与资产管理IP匹配；集中式数据库Exporter的跨地址资产绑定、域名/TLS名称绑定、真实SNMP身份核验尚未接线。
- 无采样周期业务默认值，无默认SNMP community，无自动启用、自动下发、子网扫描、凭据创建或SQL迁移。
- 批量生成是可复核输入，不等于资产已存在、真实数据已进入历史库或持续采集可用。

测试证据见 evidence/monitoring-bulk-onboarding-20261022/validation.json。参考调研见 monitoring-product-benchmark-20261022.md。

## 本地验证结果

后端4243项总计，实际执行3201、跳过1042，0失败/错误；本批新增10项。Python211项（本批新增28项，含上游新增安全测试）、Node186项、未改动前端4894项回归通过。官方Prometheus 3.15.1归档经发布SHA256核验，其中promtool实际验证了由合成清单渲染出的12个Prometheus任务配置语法；没有运行这些文档保留地址的采集。此验证不代表真实目标、凭据、TLS证书或持续采集已通过。正式128项TC仍NOT_RUN。

合入前主分支推进至485166fe，17项真实数据边界/验收保护更新原样保留，同步后Python与Node再次通过。没有执行上游清理工具或写入LAN业务库；所有本切片数据仍为离线或自有临时验证输入，不将上游部署/清理结果算作本轮执行证据。
