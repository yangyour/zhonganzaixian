# 报障详情的规则等待记录回读

2026-10-01 补齐 D07 详情查询：打开已登记报障后，以其 `operation_id` 单次读取现有 `GET /api/v1/operations/{id}`。结果必须与当前登记及租户一致，保持现有 CUSTOMER_REPORT_REGISTRATION、PENDING、CONFIG_PENDING 和 null 进度语义。

详情显示服务端错误原文和待确认编号，处理进度显示未提供，不转成0%或业务成功。读取失败保留已登记事实，可手动GET重查；切换详情、租户、身份或权限时取消旧请求并丢弃迟到结果。没有轮询、新POST、再次创建工单或自动解除规则门禁。

实际验证：新增29项隔离测试通过，全前端9个测试文件共98项通过；Vue类型检查、Vite构建及差异格式检查通过。主包464.08kB，工单页面51.26kB，无包体警告。机器记录见 [独立证据](evidence/cloud-report-operation-verification.json)。

复用原API契约，无新依赖、接口或迁移。真实OIDC/PG/HTTP和1440/390px页面仍未执行；该回读不完成正式工单状态、SLA、通知或完整D07验收。
