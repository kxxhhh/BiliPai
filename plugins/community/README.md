# 🧩 社区插件

欢迎来到 BiliPai 社区插件目录！这里收录了由社区开发者贡献的规则插件（`.json` / `.bp`）。

## 📥 如何安装

1. 复制下方插件的 **Raw 链接**
2. 在 BiliPai 中进入 **设置 → 插件中心 → 导入外部插件**
3. 粘贴链接并安装

## 📝 如何贡献

1. Fork 本仓库
2. 在 `plugins/community/` 目录下创建你的插件规则文件（`.json` 或 `.bp`，内容均为 JSON）
3. 更新此 README 添加你的插件信息
4. 提交 Pull Request

### 命名规范

- 文件名使用小写字母和下划线，如 `my_cool_plugin.json`
- `id` 字段应与文件名一致（不含 `.json`）

### 质量要求

- [ ] JSON 语法正确
- [ ] 包含完整的元信息（id, name, description, version, author）
- [ ] 规则经过测试，确保有效
- [ ] 不包含恶意规则或隐私侵犯

---

## 🎁 插件列表

> 即将推出更多社区插件...

| 插件名称 | 描述 | 作者 | 链接 |
|----------|------|------|------|
| BP 示例过滤插件 | 示例 .bp 插件：过滤低质短视频与营销关键词，带 iconUrl | BiliPai Community Demo | [Raw](https://raw.githubusercontent.com/jay3-yy/BiliPai/codex/bp-demo-package/plugins/community/bp_demo_focus_filter.bp) |
| Bili-Companion：蓝雪女仆桌宠 | `.bpplugin` Kotlin 包：真实二次元角色素材、签名和弹幕能力声明 | BiliPai项目组 | [包文件](./../../releases/Bili-Companion-1.1.0.bpplugin) |

> `.bpplugin` 当前是预览格式：BiliPai 会校验包结构、SHA-256、签名和能力授权，但暂不执行外部 Dex。桌宠运行能力由宿主内置版本提供。

---

## 📌 注意事项

1. 社区插件由社区成员创建和维护，BiliPai 团队不对其内容负责
2. 使用前请仔细阅读插件规则，确保符合你的需求
3. 如发现问题，请在 Issues 中反馈

---

<p align="center">
  <sub>🤝 感谢所有贡献者的付出</sub>
</p>
