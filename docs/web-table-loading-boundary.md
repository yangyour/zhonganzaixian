# Web表格按路由加载与构建体积证据

## 改动与实际收益

此前main.ts全局注册ElTable/ElTableColumn，登录路由尚未使用表格也需加载相关入口代码。现将ElTable局部绑定在AppTable，11个使用列的SFC分别局部绑定ElTableColumn，仍沿用既有懒加载路由。资产、审计、日期查询使用精确行类型实例；配置页同时含登记行和差异行，仅登记动作列指定Configuration泛型，未用any或跳过类型检查掩盖差异。

同一Node22.22.1/Vite7.3.6工具链，用生产构建manifest遍历静态imports、去重文件并读取实际字节。基线为14b67943源码；最终入口静态JS由501639降至424970 bytes（减少76669，约15.28%）；登录路由静态JS由504275降至427606 bytes（约15.20%）。资产路由及入口联合静态JS由663215增至664142 bytes（增加927，约0.14%），不能称所有页面总下载均减少。入口和各路由CSS字节保持不变。

只移动ElTable的第一轮实验入口反而增加6 bytes，未达到目标；继续同时移除列组件的全局绑定后才得到上述减少。最大单个JS现在424970 bytes，构建不再出现>500kB chunk告警；没有修改Vite警告阈值、网络/端口、依赖版本或权限。

这些是本地生产产物的静态依赖图计量，不是网络传输、浏览器缓存、首屏时间、FPS或实际交互流畅度证明；既有浏览器B03仍待解决。gzip另以Node zlib level9逐文件计算并加总，不能与不同工具/压缩级别的gzip数字混用。懒加载将表格成本留到使用路由，资产页总体略增是明确的取舍。

## 验证与可复现方式

运行npm --prefix apps/web run build -- --manifest后，用tools/inspect-web-bundle.mjs读取manifest和实际文件，验证所有静态引用存在并输出入口、登录及资产路由去重JS/CSS字节/摘要。输出文件使用wx创建，不覆盖既有证据。

13新增结构/实际Vue编译器测试覆盖入口无表格全局注册、AppTable本地绑定及全部11个列模板编译后引用本地组件，不靠全局resolveComponent。初始2项及扩展后的12/13预期红测均保留；迭代中类型检查发现编译器bindings可空、Element Plus DefaultRow与业务行不匹配、配置页多种行不能统一泛型，均明确修正后通过，没有关闭类型检查。

全853Web、typecheck、生产构建、70契约及生成一致性通过，所有manifest引用的产物存在。无新HTTP、后台写、迁移或组件部署；API0.33未重启。前导入只读14b67943 CI37162432876 success已回读。见[基线](evidence/niko-table-lazy-before-20261003.json)、[最终](evidence/niko-table-lazy-after-20261003.json)、[验证摘要](evidence/niko-table-loading-boundary-20261003.json)。M1/全部128项不据此标DONE。
