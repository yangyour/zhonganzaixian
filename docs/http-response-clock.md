# M1/M4 响应时钟与采样年龄边界

## 客户端契约

新增ApiClient.requestWithClock(path, {signal})，仅发一次GET，返回data及clock。运行时也只取signal，不接受调用者夹带的POST/body/幂等键；旧request返回形状、原body/key/CSRF及requestBlob不变。所有成功结果在HTTP正文、时钟观察及外层返回前继续核对授权generation/AbortSignal，旧上下文不能取得新的业务结果或时钟观察。

clock只解释实际HTTP Date头，不是新增的服务端业务字段、generated_at或sampled_at；HTTP API仍0.33。按[RFC9110第6.6.1节](https://www.rfc-editor.org/rfc/rfc9110.html#name-date)，Date是消息生成时间的近似，且中间层可能补写或替换。因此clock_accuracy_verified固定false；不宣称NTP同步、源服务器真实性或监控采样已验证。

只识别规范IMF-fixdate，校验完整日历和星期一致；不可能日期、重复合并Date、旧式格式、非GMT均UNKNOWN。要求明确no-store且没有Age（包括0）；否则不拿可能缓存的消息时间充当当前时钟。这里是保守的本项目观察门禁，不是通用HTTP缓存协议实现。缺Date、坏时间或单调时钟异常只使clock未知，仍保留成功data；不以浏览器Date.now补造服务器时间。

## 年龄估计不是健康判断

estimateSampleAge只使用原sampled_at、响应Date秒级值、测得的请求往返和同页单调时间经过量；BigInt保留输入纳秒，输出毫秒十进制字符串区间。范围只估计Date秒级量化和请求耗时，不保证涵盖真实当前时刻，因为时钟本身未校准/认证；不能用作SLA或自动处置依据。

同一个旧样本随新响应只会按新时钟重新估计，不能因刷新成功把样本时间重置为现在。缺样本为NO_SAMPLE，坏样本/缺时钟/单调时间倒退/明显晚于估计时钟均保留未知；有估计也保持freshness_verified=false、monitoring_status=null。没有填Q06心跳、1分钟失联判定或300秒达标结论，也没有监控页轮询/页面接线。

观察只供当前页面同一单调时钟生命周期使用，不持久化或跨重载复用。未来页面须随租户/身份/卸载失效，并在恢复可见或时钟生命周期变化后重新读取；本切片未验证不同操作系统休眠时单调时钟行为。

## 验证

12时间核心测试及10客户端测试新增，旧客户端12项一起定向34通过；全875Web/typecheck/build/70契约/生成一致性通过。新方法未实现时9/10客户端测试先失败；首次文本补丁因多处相同transport模式被预条件拦截，原client文件未改，之后使用精确补丁并通过全部检查。

实际编译ApiClient/估计核心连接niko原PG/OIDC/API0.33：18次HTTP，含8个Date/no-store观察、5份资产DTO与旧request读取逐字一致；5份sampled_at均null，估计均NO_SAMPLE，未制造监控采样。1次正常request的只读阈值POST保留CSRF和返回形状，未保存规则；旧request时钟函数调用0次。真实403拒绝和1次挂起真实200后失效上下文均验证。415条配置登记本轮前后摘要一致，0业务写/0新样本。

HTTP Date+no-store可读不代表组件采集健康。旧样本随刷新增加年龄、错误日期、亚毫秒及未来值等只由隔离单元验证，不称真实监控事件；没有浏览器DOM/性能、物理故障、迁移或API重启。前表格加载e24ad3ee CI37163403512 success已回读。见[真实HTTP](evidence/niko-http-clock-client-http-20261004.json)、[回归证据](evidence/niko-http-response-clock-20261004.json)。
