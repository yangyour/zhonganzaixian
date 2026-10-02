# niko API运行产物与Maven构建隔离

## 原现象与修复范围

12列验证期间，一次新OIDC登录在回到API-only服务的/assets时得到500，API日志包含ch.qos.logback.classic.spi.ThrowableProxy的ClassNotFoundException/NoClassDefFoundError。当时JVM直接运行server/api/target内JAR，该文件随后曾被Maven重新打包。旧有效会话的资产接口仍可通过，不能据此声称重新登录正常；停止本次拥有的栈并重新启动后，6身份登录恢复。此时序是定位线索，不把它写成已完整复现的唯一根因。

为消除运行JAR被常规重构建覆盖的明确风险，run-stack启动前现在将本项目构建JAR复制到每次启动独立的.local/niko-test/runtime-artifacts/api-随机目录/SHA256.jar。检查本项目Boot入口、ZIP完整性/重复条目、源文件复制前后身份与修改时间，完整拷贝后文件为0400；没有硬链接到target，没有覆盖旧运行副本。只验证和复制已构建项目，不下载/运行新来源软件。

start-api必须显式接收该副本路径，在加载私有运行配置前检查固定目录、无符号链接、当前UID、非共享目录、独立普通文件、0400及SHA256。缺失参数、target路径、改字节/改权限/硬链接均失败，不退回原target。JVM仍使用固定本项目JDK、内存参数，只注入既有runtime配置，不注入migrator配置。

进程守卫继续核验父PID、UID、完整JVM命令、固定JDK可执行文件以及回环8080监听socket归属；现在同时核验启动时的确切只读副本。显式API故障模式在同一次运行中复用同一副本，V10故障脚本的产物指纹也改为实际运行副本，而非可能变化的target。旧故障证据未覆盖或重跑，新代码接线不能冒充新的SIGKILL验收。

## 操作方式

从仓库根执行既有tools/niko-test/run-stack.sh。脚本先校验副本再启动已准备PG/Keycloak/API，仍只停止本次直接子进程；不要手动无参数调用start-api.py。退出拥有的运行栈后再改runner。所有Maven命令仍必须串行，运行副本隔离并不解决多个Maven同时写同一target的问题。

运行目录及副本均被.local忽略，不进入Git，旧副本保留用于此次验证；没有新增账号/密钥/代理/权限、迁移或原服务器操作。应用API字段、实际版本0.16和V1–V10保持不变。

## 真实验证

新增11个产物守卫及2个进程守卫测试，niko纯Python守卫总48通过；覆盖复制独立、target重写/删除、每次新副本、坏ZIP/错误项目、源变化、路径/符号链接/硬链接/权限/UID/无参数拒绝。shell/Python/Node语法通过。

在真实niko PG/OIDC/API运行期间，先验证副本哈希与原target相同、确切拥有进程及监听；再强制Maven JAR重构建并串行执行根测试，616报告=603执行（含163真实PG）+13历史fixture跳过，6MinIO排除，0失败错误。重打包前后target SHA256确实不同；JVM PID及start-ticks不变、运行副本SHA256不变、监听仍属于同一进程。

不重启API直接重新捕获6个真实OIDC身份，20个只读HTTP包含live/ready、各身份session/当前租户资产以及API-only /assets应为404；均通过，未放宽捕获脚本对500的失败判断。见[真实重构建证据](evidence/niko-api-runtime-artifact-20261002.json)。该20请求是运行恢复烟测，不是完整API契约覆盖，也不是浏览器DOM。

首个证据采集尝试在读取/proc/PID/task/PID/children时失败，因为本执行环境未暴露该文件，尚未启动Maven、未生成基线或成功证据。改为只读列举PID，沿用父进程/UID/完整命令/副本/监听全部校验；不发送任何信号、不借此停止其他进程。修正后同一拥有运行栈完成上述重构建验证。

此项解除本机常规Maven覆盖运行JAR的风险，不解除OSS、原服务器、浏览器、PG/Worker故障或客户业务决策阻塞；全部128项仍非DONE。
