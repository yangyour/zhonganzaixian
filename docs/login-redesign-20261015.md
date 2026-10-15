# 登录页精简与生成背景

用户要求：删除无用描述，简化样式，只保留登录相关内容，并用imagegen生成适合系统的背景。本轮沿用项目的蓝白配色与MOP标识，不改变认证、CSRF、会话恢复、密码策略和SSO能力开关。

## 设计与资源

删除宣传标题、介绍段落、四张功能说明卡和重复页脚。登录页只保留品牌、账号/密码、密码可见切换、忘记密码入口、登录，以及实际可用时的统一认证。测试环境以小字标识；错误、失效、Caps Lock和帮助仅在相关状态出现。

背景通过内置 `image_gen` 生成，不使用CLI/API回退。生成原图为1672×941 PNG，保留在本机生成目录，并复制到项目忽略目录 `.local/login-imagegen/login-infrastructure-original.png`。仅做WebP编码，不裁切、不重绘；运行资源为 `apps/web/src/assets/login-infrastructure-20261015.webp`，60,498字节。登录组件与登录专用预加载指向同一新资源；旧图片不再由这两个入口引用。

完整生成提示词记录在本文件末尾。背景只有浅蓝白机房与网络设施，没有文字、标志或假登录控件。桌面在留白区域显示登录卡片，窄屏居中；背景加载失败时仍有浅色底和完整表单。

## 验证清单

- 默认、加载、输入错误、展开找回说明、显示/隐藏密码、SSO条件分支；原认证测试保持。
- 320/390/600/768/900/1024/1440/1920宽度及844×390矮屏：背景显示、品牌/表单边界、按钮可见、无横向溢出。
- 真实指定API登录；账号/密码从Git忽略的配置读取，不进入截图/日志。生成背景是装饰资产，不替代业务数据或身份响应。
- 图片失败降级、键盘焦点与输入保留；类型、相关回归、生产构建和发布门禁。

验证使用已提交 `5a5f142` 的独立工作区，只叠加本轮登录页、预加载及图片资源。同期配置化/服务端修改不混入验证。本轮未部署服务器。

## 执行结果

18项现有登录、预加载及登录返回路由测试通过，类型检查、生产构建和静态发布/策略门禁通过。认证请求、CSRF、重复提交抑制、密码策略、错误回焦、密码显示/隐藏、页面隐藏时重新遮盖密码及可选SSO条件分支保持。真实项目账号登录返回200并进入授权资产页，随后退出自己的测试会话，返回空账号/密码表单；可选SSO没有冒充真实提供方联调。

9组视口通过：320×720、390×844、600×800、768×800、900×800、1024×768、1440×900、1920×1080及844×390。默认页面无横向溢出，常规高度的登录按钮在首屏内；矮屏可正常纵向滚动。320px空提交、错误定位和展开帮助检查通过，密码切换正常。初次错误图处于组件过渡动画中，已等待稳定并重拍。

新背景实际加载尺寸1672×941，登录专用预加载与img使用同一资源，生产包内只有新的60,498字节WebP背景。旧PNG不再打入此版本。背景请求失败检查发现浏览器破图标记，已补充隐藏失败装饰图的处理；复验为纯浅色底，表单与校验继续可用，没有新增无关错误说明。

最终构建 `a5d6f83974465a5d8195837da395bf2fa16d366d2a5832e196b6c38db304c02a`。指定环境只读盘点14个服务运行，ready=true、production_ready=false。页面脚本异常0，业务实体写入0；真实认证操作仅登录与注销自己的会话。截图不含真实账号或密码。物理手机、原生浏览器缩放及生产验收未由本轮替代。

本地预览：`http://127.0.0.1:15175/login`。验收浏览器完成后关闭，预览进程保留。

证据：[桌面](evidence/login-redesign-20261015/login-desktop.png)、[手机](evidence/login-redesign-20261015/login-mobile.png)、[错误与帮助](evidence/login-redesign-20261015/login-errors-help-320.png)、[图片失败降级](evidence/login-redesign-20261015/login-image-fallback.png)、[机器记录](evidence/login-redesign-20261015/validation.json)。

## 生成提示词

Use case: stylized-concept. Asset type: full-bleed background image for a minimal enterprise network operations management system login page. Create a refined, quiet 3D architectural illustration, wide landscape 16:9, high resolution. A small, carefully composed group of abstract server-rack forms and network infrastructure modules sits in the left third and lower-left, made of matte porcelain-white and very pale ice-blue glass with subtle cobalt-blue edge accents. A few thin elegant network connection lines flow gently along the lower left, suggesting connected infrastructure without a dense web. Soft daylight, restrained shadows, clean professional engineering aesthetic, generous negative space. The right 55 percent must remain a calm almost-white blue-tinted open field for a real HTML login form to be placed later, no baked-in card. Smooth transition between illustration and empty area, no harsh dividing line, no busy details behind the future form. Colors should fit an existing light blue business dashboard: off-white, mist blue, small accents near #2165c5. Avoid dark cyberpunk, neon, glowing grids, dramatic lighting, stock-photo people, shields, padlocks, globes, floating badges, dashboard panels, UI controls, decorative typography. Absolutely no text, letters, numbers, logos or watermark. This is a background illustration only, not a screenshot or UI mockup.

