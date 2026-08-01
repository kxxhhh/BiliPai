# Bili-Companion：蓝雪女仆桌宠

这是 BiliPai 第三方 Kotlin 插件包示例，输出文件为 `bili-companion-1.1.0.bpplugin`。

插件包使用项目已有的蓝雪女仆二次元位图，不使用几何脸或几何身体。内置宿主桌宠负责真实角色渲染、接触阴影、界面边界感知、跳跃巡游、拖拽、双击收起、长按追随和双指缩放；外部包声明并实现 SDK 当前支持的弹幕读取与状态样式接口。

> 当前 BiliPai 的 `.bpplugin` 运行时仍处于预览阶段：宿主会解析 manifest、计算 SHA-256、验证可信签名并保存能力授权，但不会执行外部 Dex。安装后立即生效的桌宠能力仍由宿主内置插件提供。

## 构建

先准备 PKCS#8 RSA 私钥。私钥不应提交到仓库：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out /tmp/bili-companion-private.pem
BILIPAI_BPPLUGIN_SIGNING_KEY=/tmp/bili-companion-private.pem \
  ../../../gradlew -p . packageBpPlugin --no-daemon --no-configuration-cache
```

输出：

```text
build/distributions/bili-companion-1.1.0.bpplugin
```

包根目录包含：

- `plugin-manifest.json`
- `plugin-signature.json`，RSA `SHA256withRSA`
- `classes.jar`
- `companion/avatar.png`，真实二次元角色位图
- `companion/profile.json`
