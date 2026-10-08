# 登录页视觉与组件整理（2026-10-08）

用户要求使用 ImageGen 生成登录背景，去掉无用描述，并统一使用成套组件。本次范围是登录页；不改变登录 API、账号策略、租户权限或后台组件。

## 视觉与文案

背景采用内置 ImageGen 生成的机房主题画面：服务器机柜、交换机、配线架和整齐布线位于左侧，右侧留出深蓝区域放登录卡。图片内没有文字、按钮或监控数据；所有可操作内容由实际 Vue / Element Plus 组件呈现。它是生成的主题装饰图，不表示客户真实机房或实际设备状态。

删除宣传标题、功能列举和重复授权说明，只保留产品名、测试环境标识、登录表单，以及按需出现的连接/校验/找回提示。找回通道、可选统一认证和测试环境边界保持真实。

资产：`apps/web/src/assets/login-datacenter.png`，1672×941、1,913,591字节，SHA256 为 `d54f65cd93a1cdff11ac49e54e5ed84a1cb38b7752abcfb98ae7e47db0d03780`。使用内置 `image_gen`，未使用 CLI/API fallback。原始生成图保存在 Codex 生成目录，工程引用已复制到仓库，不依赖机器外部路径。

表单统一采用项目已有 Element Plus 2.14.7：ElForm、ElFormItem、ElInput、ElButton、ElAlert、ElTag；没有引入另一套组件。显隐保留键盘可达按钮，输入焦点、空白校验、原密码空格、CSRF、防重复提交、卸载取消及忙碌区域外状态提示继续保留。旧全局登录样式移除，新样式只作用于登录页。

## 最终生成提示词

```text
Use case: photorealistic-natural.
Asset type: full-bleed login-page background for a NETWORK OPERATIONS MANAGEMENT PLATFORM (network device inventory, monitoring, incident management). Wide 16:9 landscape.
Primary request: a believable, professionally photographed modern enterprise data center / network operations infrastructure. The image must immediately read as IT operations and network equipment management, NOT abstract sculpture.
Scene: on the LEFT 55 percent of the frame, a refined real server room aisle with dark charcoal server racks, visible rack-mounted network switches, patch panels, neatly organized blue fiber/ethernet cables and small realistic cool-blue / green equipment indicator lights. Racks recede into depth with tasteful perspective; emphasize credible equipment detailing, ventilation perforations and orderly patch leads. Dark slate metallic materials, subtle reflections, clean data center floor. No people.
Composition designed for actual login UI: left-center contains the infrastructure subject; RIGHT 45 percent fades smoothly into uncluttered deep desaturated navy space where a white HTML login card will be overlaid. Keep important hardware out of this right-side form area. Top-left should have enough darker calm space for a small white logo to be overlaid. Do NOT draw the card or any UI. Panoramic framing, naturally balanced negative space, camera eye-level, premium corporate photography.
Lighting: calm low-key blue ambient room light with controlled highlights and soft practical equipment lights; professional trustworthy atmosphere, no neon glare. Palette deep navy #10243b, charcoal, steel blue and restrained cyan. Sharp realistic hardware in the foreground, gentle background depth of field. High visual quality, sober and readable as a background.
Constraints: no abstract glass ribbons or sculptural platforms; no illustrations of clouds or globes; no holograms; no HUD overlays; no dashboards, charts, mock graphs, text, letters, logos, numbers, fake data, watermark, interface elements, buttons, borders or people. This is only a photographic background image, optimized for a login page.
```

## 验证记录

登录组件 9 项、完整前端 114 文件/1820 项通过，类型检查和生产构建通过。原有测试已等待 Element Plus 字段错误状态更新，未降低输入/焦点/取消断言。子代理首轮因沙箱 EPERM 未执行测试，主执行环境成功完成以上实际回归。

Edge 154.0.4258.62 使用当前生产静态构建连接原指定 API，20 项检查通过：背景实际请求200并正常解码，四种空表单尺寸（1440×1000、1280×720、390×844、320×740）控件全部可见；登录选项读取中断后可重新连接；显隐、空白账号、找回说明和既有账号真实登录通过。原有资产加载/失败/恢复也保持通过。没有业务写入、账号修改或组件变更。

8 张登录截图逐张核对，无叠字、遮挡、裁切或页面横向溢出；另外10张资产回归截图保留为自动检查材料。状态、产物/图片摘要和明确边界见[机器证据](evidence/login-design-20261008/result.json)与[视觉核对](evidence/login-design-20261008/visual-review.json)。本机加载新版静态文件不代表服务器已经部署，也不代表物理手机或实际读屏验收。

复现：在项目根执行 `npm.cmd --prefix apps/web run build`，随后运行 `node tools/verify-ui-polish-browser.mjs`。连接资料仍由忽略的私有配置读取，每轮产生独立的 `.local/ui-polish-<UUID>/` 证据目录。
