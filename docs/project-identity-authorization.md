# 项目内账号与权限体系

2026-10-05用户要求替换默认Keycloak登录，采用项目内账号与权限管理，同时预留统一身份认证接口。用户随后明确“先不设置这些”，未采用建议的口令长度、历史、失败锁定、额外闲置期限及强制改密基线；这些配置保持null。已有服务器会话设置不在本次任意改动。

## 实施范围

- 本地用户名/密码登录、当前账号、修改密码、管理员重置、启停及会话撤销；密码只保存带随机盐的自适应单向哈希，不记录明文或可离线猜测的普通请求摘要。
- 用户与当前租户成员管理；保持用户ID和既有业务/审计记录。授权范围必须明确，跨租户对象统一404，禁止凭全局角色越过当前成员关系。
- 四类合同基础角色作为业务类别；同类别可建立配置化角色并选择能力集合，能力不能越过基础类别的业务边界。菜单/按钮/API使用有效能力，数据库写入与Worker按当前成员及权限版本复核。
- 角色/成员修改CAS、幂等和审计；保护最后一个可用管理身份。用户停用、改密、重置和会话撤销使旧认证上下文失效。
- 统一认证保留OIDC适配及明确issuer/sub绑定。默认关闭外部提供方；不按邮箱/用户名自动合并账号，也不信任外部角色声明直接授予业务权限。
- 暂无邮件/短信恢复通道时由管理员重置，界面说明实际能力；不伪称已发送找回通知。

## 成熟实现依据

继续使用既有Spring Security作为安全基础，项目实现用户与授权业务。密码编码采用框架PasswordEncoder及可升级格式；参考[Spring Security密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)、[OWASP认证实践](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)、[授权实践](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)和[密码存储实践](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。不引入整套第三方管理平台或自行设计密码算法。

实施、真实组件/API/UI验证与切换证据按功能追加。当前文档是本次范围和用户选择，不预写完成；Q09正式岗位/签字、Q17尚未设置的策略及完整128项验收继续分别登记。

首批有效权限值模型已完成：角色权限显式选择、不可变、禁止跨基础业务类别提权、停用后能力为空。7项新增及4项既有身份映射测试共11项通过。此批尚未改变服务器登录、数据库结构或运行授权来源。

## 2026-10-05 口令编码基础（未切换登录）

新增Spring Security/Argon2id版本化编码器，22项库级测试含独立libsodium金样通过；随机盐、完整Unicode/长输入、有界存储格式、不接受明文/降级算法。未注册Bean、未创建实际账号或口令、未改会话/数据库/服务器登录；密码业务策略仍null。详见[编码说明](project-password-storage.md)和[证据](evidence/niko-project-password-storage-20261005.json)。
