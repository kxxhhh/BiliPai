# BiliPai Dev 9.9.9.6

- 构建任务：`:app:assembleDev`
- APK 构建产物：`app/build/outputs/apk/dev/BiliPai-dev-9.9.9.6.apk`
- Release 归档：`releases/BiliPai-dev-9.9.9.6.apk`
- APK SHA-256：`bedca192d07ddf8e62cea9fbe1ba7227340b3c351493cb1b9a5dd6b4c1836c09`
- 桌宠素材：透明二次元 `pet_sprites.png`
- 桌宠动画：按 WALK、RUN、JUMP、IDLE 序列帧切片，100ms 更新，支持方向翻转、跳跃形变与接触阴影
- 插件包：`releases/Bili-Companion-1.1.0.bpplugin`
- 插件包 SHA-256：`ddd31f17eb1fb755dbcfee09aae3826d1668f47875d42f3d9c18135b6fd1fbcc`

验证：`aapt2 dump resources` 已确认 APK 包含 `drawable/pet_sprites`；`.bpplugin` 使用 `SHA256withRSA` 签名并已验证通过。
