# 本地候选组件记录

本批依赖仅供内部本地开发验证。依照原开发文档05及合同的披露要求，正式集成与交付还需客户批准、完整传递依赖清单和支持范围核验；此记录不表示客户已批准。

|组件|冻结方式|许可/来源|
|---|---|---|
|Java 17|构建要求，当前验证使用Temurin 17|JDK发行许可另随客户运行环境核验|
|Spring Boot 3.5.16 / Security|根pom使用固定Boot parent/BOM，不单独追逐Security版本|Apache-2.0，[官方兼容要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[源码许可](https://github.com/spring-projects/spring-boot/blob/3.5.x/LICENSE.txt)|
|Vue 3 / Vue Router|`apps/web/package.json`精确版本+package-lock|MIT，[Vue源码](https://github.com/vuejs/core/blob/main/LICENSE)、[TypeScript与类型检查](https://vuejs.org/guide/typescript/overview)|
|Element Plus|精确版本+锁文件|MIT，[组件源码许可](https://github.com/element-plus/element-plus/blob/dev/LICENSE)、[安装文档](https://element-plus.org/en-US/guide/installation.html)|
|Vite / TypeScript / vue-tsc / Vitest|开发工具精确版本+锁文件|各包许可由锁定包和传递依赖清单核验|
|Ajv 8.17.1 / ajv-formats 3.0.1|根package.json精确版本+根package-lock，仅用于实际HTTP响应契约测试|MIT，正式披露包括其传递依赖|
|PostgreSQL JDBC 42.7.11 / HikariCP 6.3.3|由Spring Boot BOM锁定，已核对实际jar；只连接已有测试PostgreSQL|JDBC BSD类许可 / HikariCP Apache-2.0；完整POM及传递依赖披露仍需核验|
|Flyway 11.7.2 / PostgreSQL模块|Boot BOM锁定；测试库V1迁移已执行，后续只追加|Apache-2.0开源模块；不使用商业高级迁移功能|
|Spring Security OAuth2 Client 6.5.11|Boot BOM锁定；真实授权码、PKCE S256及OIDC/RP注销|Apache-2.0，[PKCE官方API](https://docs.spring.io/spring-security/reference/6.5/api/java/org/springframework/security/oauth2/client/web/OAuth2AuthorizationRequestCustomizers.html)|
|Paramiko 5.0.0|tools/requirements.txt固定版本，仅为本机SSH测试客户端|安装包METADATA标注LGPL-2.1；正式分发/集成披露与书面许可仍按Q03，未作为生产组件批准|
|Playwright 1.62.1|根package.json精确版本+锁文件；使用已安装浏览器，真实OIDC登录测试|Apache-2.0，仅本机测试客户端，不在服务器另建浏览器或身份服务|

依赖不从生产页面CDN加载，不安装Zabbix、消息服务、堡垒机或数据库。已有服务器组件版本和缺口另见environment/component-inventory.md；复用测试环境不表示生产许可或支持基线已批准。

前端及根目录契约测试直接/传递依赖明细由 `node tools/dependency-inventory.mjs` 从锁文件与已安装包的 `package.json` 生成 `docs/evidence/npm-components.json`，标记为候选。Maven BOM与完整解析树可通过 `mvn.cmd dependency:tree` 查看；客户正式审查前还需补完整传递组件许可、安全与支持版本核验。
