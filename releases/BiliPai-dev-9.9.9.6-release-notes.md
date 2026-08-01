# BiliPai Dev 9.9.9.6

- 构建任务：`:app:assembleDev`
- APK 构建产物：`app/build/outputs/apk/dev/BiliPai-dev-9.9.9.6.apk`
- Release 归档：`releases/BiliPai-dev-9.9.9.6.apk`
- APK SHA-256：`a237f049e6708c9a03645d44bc4affc0c1b26b44fa130a5c8226d0ac49add796`
- 桌宠素材：透明二次元 `pet_sprites.png`
- 桌宠动画：按 WALK、RUN、JUMP、IDLE 序列帧切片，100ms 更新，支持方向翻转、跳跃形变与接触阴影
- 桌宠互动：点击摸头回复、弹幕彩蛋、弹幕连击、经验等级、好感度和反应特效
- AI 桌宠助手：视频页可翻译标题、总结标题/简介/热门评论资料、按偏好查找评论，并支持自动翻一页
- AI 供应商：OpenAI 兼容接口（可填写第三方中转站 Base URL、接口路径和模型）、Anthropic Messages、Google Gemini
- AI 安全：API Key 使用 Android Keystore 加密保存，不写入插件配置 JSON；摘要明确不伪称读取视频音频或画面
- 观看提醒：默认连续观看 45 分钟提醒休息，可在 5-240 分钟范围内调整
- 插件包：`releases/Bili-Companion-1.1.0.bpplugin`
- 插件包 SHA-256：`ddd31f17eb1fb755dbcfee09aae3826d1668f47875d42f3d9c18135b6fd1fbcc`

验证：`aapt2 dump resources` 已确认 APK 包含 `drawable/pet_sprites`；`.bpplugin` 使用 `SHA256withRSA` 签名并已验证通过。
