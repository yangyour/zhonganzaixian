# 域名校验文件发布记录（2026-10-19）

本次针对用户提供的 `mop.all-online.com.cn` 和原始 `WW_verify_iFfTEbx1uklTpULT.txt` 完成站点静态文件发布。校验文件本身需要公开访问，不包含服务器登录凭据；服务器连接资料继续仅从 Git 忽略的私有配置读取。

## 实施与验证

- 发布前访问 HTTP 根路径下的同名文件，返回 200，但响应是 854 字节的 MOP 首页 HTML，不能视为校验成功。
- 使用已有固定主机键信任连接指定 LAN 测试服务器，核对 `/opt/mop-lan-test/owner.json` 和 `mop-lan-web` 项目标签；域名返回的 `index.html` 与容器实际静态根目录中的文件逐字节一致。
- 将附件原始 16 字节文件添加到当前 Web 挂载目录 `/opt/mop-lan-test/releases/runtime-center-web-01391a70022b/web/`，权限 0644。创建操作拒绝覆盖不同内容的已有文件，无容器重启或 Nginx 配置变更。
- HTTP 校验地址为 `http://mop.all-online.com.cn/WW_verify_iFfTEbx1uklTpULT.txt`。发布后返回 200、`Content-Type: text/plain`、16 字节，响应内容与附件逐字节相同。
- 原文件同步保存到 `apps/web/public/WW_verify_iFfTEbx1uklTpULT.txt`，随 Vite 默认 public 资源复制机制进入后续前端发布，避免只存在于当前服务器版本目录。
- 本机通过正常证书校验访问 HTTPS 时，Windows Schannel 返回 `SEC_E_CERT_EXPIRED`。本次仅按截图要求验证 HTTP 地址；未关闭证书校验，HTTPS 证书问题待域名运维方处理。

## 验收边界

已验证服务器发布及当前客户端经域名读取原文件。企业微信管理后台最终“验证”操作及其校验服务器的公网访问结果尚未观察，不能写成平台侧验证已通过。本次不涉及业务 API、数据库结构或完整合同验收。
