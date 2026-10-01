# 本地接续验证（2026-10-01）

用户最新要求本地继续开发。已拉取云端完整工作分支及最后交接提交`1db3976f10f94956ab6b2be500c67663d14f5b18`，云端已回报停止业务写入、无开发子代理和未提交文件。继续全部128项及逐功能提交规则，不将云端纯测试记录合并为本轮组件验收。

## 本批照片链路：已验证与待补范围

|检查|本地实际结果|边界|
|---|---|---|
|指定项目V5|独立CLI Check后ApplyPhotos一次；结构/ACL通过；再次Apply为0DDL|API/Worker启动不执行迁移；V1–V4历史不改；V5 SQL已冻结|
|根deploy/check|完整编译/打包、96纯单元/隔离、43指定PG、3指定MinIO通过，0失败/错误|现存25份Surefire核对；PG照片24+原19，历史fixture13项跳过，不能算通过|
|前端及实际契约|全量113项与V0.6契约8项通过；遮挡修复后41 operation+3 workspace定向回归及最终类型/构建通过|44项包含于113项，不累加；Vite7.3.6、1661 modules、8.10s，主464.09kB/Tickets54.49kB/Configurations49.84kB，无包体警告|
|GitHub CI独立回读|eba5630对应run 36836074365通过；照片提交f56ad1b对应push run 36846410035及3个job为completed/success，28步骤全部成功|分别对应各自SHA；CI不连接私有PG/MinIO/OIDC，见[照片提交摘要](evidence/github-f56ad1b-verification.json)|
|真实OIDC/HTTP|4个测试身份登录、80次HTTP全部通过并经V0.6 Ajv校验|真实Worker→PG/MinIO→平台grant原图/hash回读；[HTTP证据](evidence/local-report-photo-http.json)|
|边界/重启/存储断链/撤权专项|4场景52次真实HTTP通过，finally已恢复|5_000_000/5_000_001原JPEG边界与hash、耐久API/Worker重启、MinIO项目转发503/恢复、成员撤权/旧授权失效；[专项证据](evidence/local-photo-resilience.json)|
|运行中Worker断链RETRY_WAIT|独立25次真实HTTP通过，finally恢复|PG快照确认RETRY_WAIT、lease释放/未来next_at和审计，恢复后同PID/startTime按退避自然完成同job/file/op及hash；[新专项证据](evidence/local-photo-worker-retry.json)|
|照片桌面/390px UI|36项通过，0 pageerror，正常控件/截图逐张检查|Edge154.0.4258.48持久Playwright无头，1440×900与390×844手机仿真，物理相机/企微/全浏览器未执行；[UI证据](evidence/local-report-photo-ui.json)|

Surefire计数仅从安全testsuite属性读取，摘要见[local-photo-build-summary.json](evidence/local-photo-build-summary.json)，155项报告含142通过、13跳过。未读取XML环境属性或日志正文，公共记录不含密码、Cookie、CSRF/OIDC token、含密连接串。

UI实际发现并修复已登记表单被详情抽屉遮挡：有照片时暂存详情ID、保留表单补传操作，关闭后再开详情；上下文重置清空待ID，无照片登记仍即时回读。定向44项是既有113项回归，不新增测试计数。

36项覆盖先无图登记后补传、授权PNG下载/hash；真实提交后丢响应，验证同File/key及追加key/body/version/target；两页CAS409比较重试、3张已存配额、跨A/B队列、策略重载、租户A/B/A和logout401。50秒探索覆盖无效文件/空业务选择/URL筛选分页移除；手机仿真表单366px、长名换行无溢出。截图只存Git忽略目录，不捕获SSO/grant URL。

基础80次HTTP、恢复/撤权52次HTTP、运行中Worker重试25次HTTP分别计数。52次脚本断链前已停Worker，旧RETRY_WAIT=NOT_RUN只描述该脚本；另25次独立证据已补齐：ready200正常启动后断项目MinIO，真实PENDING/RETRY_WAIT、UPSTREAM_UNAVAILABLE/retryable=true，单PG快照有lease null、未来next_at、file PENDING/version2、attempt1/token1及retry审计；恢复后同PID/startTime按退避自然完成原job/file/op并回读原图hash。观测仅对新虚构job.next_at两次各1行CAS，不改lease/token/attempt/status；finally marker/hold、三进程ready200、四会话恢复，未改V5/IAM/成员或远程配置。

V5 SQL SHA-256：`5A84F62BDAAB9BADFD5D87F4D30AE0AC1F9C1AE2F48D0D84E0635485A7BBDC55`。已应用版本不可修改，后续结构变化新增迁移。独立入口先准备根工程构建/classpath并停止本项目应用：

```powershell
pwsh -File deploy/stop-dev.ps1
pwsh -File deploy/prepare-migration-classpath.ps1
pwsh -File deploy/migrate-test-schema.ps1
pwsh -File deploy/migrate-test-schema.ps1 -ApplyPhotos
pwsh -File deploy/migrate-test-schema.ps1
pwsh -File deploy/start-dev.ps1 -WithPhotoWorker
```

默认迁移Check不执行DDL，ApplyPhotos使用受控迁移身份并检查历史/构建资源/结构/ACL。照片启动显式开启独立PG Worker，`health/ready`实际探测PG/OIDC与照片策略、V5/ACL和MinIO，照片五个布尔字段全true；失败503及脱敏原因，没有fixture或其他服务回退。停止核验拥有者记录中的API/Web/Worker路径、命令、PID和启动时间，不停止远程组件或其他项目。

未绑定照片仅上传人可读，内部角色按授权租户读取其他人已绑定登记的照片。详情明确保存队列所属登记，可返回原登记或安全放弃；上传/追加不确定和202未终态回读时保留原File/键/版本/目标，跨登记不会携带A文件写B。新登记operation和detail均通过完整关联/门禁校验后才展示，受理后失败仅GET重查。

JPEG/PNG仅用户开发方向；16_384边长与25_000_000像素为decoder技术资源防护。扫描UNSCANNED、生产UNVERIFIED、保留null及正式状态/优先级/SLA null继续保留。真实手机/企微、正式规则/许可和完整M5-5/M8-6/128项合同验收未完成。

照片HTTP复现须先准备viewerA/tadmin/msp/viewerB四份真实OIDC私有会话，并生成原图夹具；它们是三个岗位、两个租户的四个身份。工程根目录命令：

```powershell
New-Item -ItemType Directory -Force .local/qa-report-photos/fixture-generator | Out-Null
javac -d .local/qa-report-photos/fixture-generator tests/GeneratePhotoFixtures.java
java -cp .local/qa-report-photos/fixture-generator GeneratePhotoFixtures
node tests/report-photo-smoke.mjs
```

公共生成器不含凭据，仅创建Git忽略目录中的明确虚构JPEG/PNG。四份会话仅在`deploy/private/qa-photo-{身份}.json`受控保存；缺原图、会话或组件时脚本失败，不使用fixture身份或假结果。

## MinIO JVM兼容修复：独立验证通过

首次完整Maven构建揭示MinIO 9.0.3依赖的OkHttp 5.3.2默认artifact只有Kotlin多平台元数据，Java不能找到`okhttp3`类；新版上传builder的两个长度参数为`Long`。本轮保留MinIO版本，明确引入`okhttp-jvm:5.3.2`、排除空的多平台artifact，根工程对齐Kotlin 2.2.21，并传入long长度参数。保持loopback固定命名空间、禁止重定向、条件PUT、原图hash/size验证及异常脱敏。

文件Controller的隔离重放测试重设已有Mockito回答时提前调用了null Supplier，造成测试自身NPE。改用`doReturn`配置已提交重放结果，继续验证存储断链时新请求503、已有受理回放不重新调用存储。

针对该SDK修复独立执行16项：MinIO适配器5项隔离安全测试、FileController8项隔离测试、指定MinIO3项，0失败/跳过；已独立提交推送`eba5630`。这些测试包含于本批全量，不重复累加。使用本项目`mop-test-photos`和受限账号，不改变其他项目、安装组件或启用删除，见[SDK证据](evidence/local-minio-sdk-verification.json)。

SDK独立提交不代表完整合同完成。本批V5、PG/MinIO、基础HTTP、恢复/重试专项及UI结果已更新于上表；照片功能提交推送由主任务执行，完整M5-5/M8-6与128项验收门禁继续保留。

## 历史环境预检

首次本地只读预检时，私有runtime仍在、指定PG17.9和OIDC可用，Flyway只有V1–V4、照片表尚不存在；这是V5应用前的历史状态。旧隧道PID被其他程序复用，经归属核验只归档本项目失效记录并建新工程隧道，没有停止其他程序。原始连接文件、凭据和会话仍只保存在Git忽略的私有目录；当前V5已应用以上表为准。
