# 已确认的界面样式基线

2026-10-22 用户明确确认：“样式就按当前的确认下来，后续不要再改样式，新增页面样式按现有结构改动”。

- 保持现有顶栏、侧栏、工作区、表单、弹窗、按钮和表格的视觉结构，不自行换主题、配色或重新排版已有页面。
- 新页面与新增功能使用现有组件和样式层；只增加功能所需的局部结构与响应式规则，不覆盖全局设计。
- 功能、可用性、授权与状态恢复继续开发和测试；不把“样式定版”解释为停止实现功能。
- 本记录以已保留的上游36b91a12（包含123b5f5）样式为基础；没有修改这些样式文件。未来如用户明确要求改样式，另行记录新基线。

## 当前全局层字节摘要（SHA256）

- apps/web/src/styles/navigation.css: 93d40a65effb12749b5ca7f4e143fa8428a8d2aa3a31c12e636cbec4ce7b25d7
- apps/web/src/styles/workspace.css: d66f6bfd97e9ae8e1576ca55dbe75c662ac86ebb55431879b11fd88998b27479
- apps/web/src/styles/controls.css: 2571c9d2484c7e861c434b6946798f1b298453ba3528c57a7ca40f9dfe6bc224
- apps/web/src/styles/adminPrototype.css: a0df6299e0faa0351d2943655713c05eee92c0a123b18405e39b1416ad564d4c
- apps/web/src/styles/experience.css: 17ea7dc2547eb2e8204e9512f550c60c4c874136805d2d453f9e39685785be8f
- apps/web/src/styles/compact-workspaces.css: 6541c9c8c970208b55026c48d5d5602022668ae4ad8188ea0811594230ff1f07
