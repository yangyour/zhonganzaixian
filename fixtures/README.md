# 本地测试夹具

测试租户及身份由 `server/adapters` 中的 fixture 适配器提供，资产由 fixture 仓储初始化。全部为虚构数据，不导入静态原型中客户名称、演示账号或真实凭据。

每个资产必须标记 `source=fixture`。草稿没有真实采样时间，状态为未知；保存草稿不能触发监控接入。重启 API 会清除本轮内存修改。

fixture 身份入口只允许 `local_fixture` 环境；真实身份接入和数据库持久化完成前，integration/production 应拒绝启动。
