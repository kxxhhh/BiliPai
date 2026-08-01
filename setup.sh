#!/bin/bash
set -e

echo "=== 1. 初始化并安装基础依赖包 ==="
sudo apt-get update -y
sudo apt-get install -y curl wget unzip zip git jq build-essential openjdk-17-jdk

echo "=== 2. 安装 SDKMAN! 并配置 Java 17 & Kotlin 2.8.0 ==="
if [ ! -d "$HOME/.sdkman" ]; then
    curl -s "https://get.sdkman.io" | bash
fi

# 加载 SDKMAN 环境
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 安装与设置 Java 17
sdk install java 17.0.10-tem || true
sdk default java 17.0.10-tem

# 安装与设置 Kotlin 2.8.0
sdk install kotlin 2.8.0 || true
sdk default kotlin 2.8.0

echo "=== 3. 配置 Android SDK (API 36) ==="
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"

# 下载 Android Command Line Tools
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    cd /tmp
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
    mv cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
    rm -rf cmdline-tools cmdline-tools.zip
fi

# 设置环境变量
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 接受许可并安装 Android SDK 36
yes | sdkmanager --licenses || true
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

echo "=== 4. 配置 Codex 及 CLI 配置文件 ==="
# 写入 ~/.codex/config.toml
mkdir -p ~/.codex
cat << 'EOF' > ~/.codex/config.toml
model_provider = "OpenAI"
model = "gpt-5.6-luna"
review_model = "gpt-5.6-luna"
model_reasoning_effort = "xhigh"
disable_response_storage = true
network_access = "enabled"
windows_wsl_setup_acknowledged = true

[model_providers.OpenAI]
name = "OpenAI"
base_url = "https://toolcode.top"
wire_api = "responses"
requires_openai_auth = true

[features]
goals = true
EOF

# 写入 ~/.codex/auth.json
cat << 'EOF' > ~/.codex/auth.json
{
  "OPENAI_API_KEY": "sk-b1d30d2f712ca46ee52dd659c4f89b95eb13515491664969a17c14644c99de0e"
}
EOF

# 设置安全权限
chmod 600 ~/.codex/auth.json

echo "=== 5. 写入全局环境变量到 ~/.bashrc ==="
cat << 'EOF' >> ~/.bashrc

# Android SDK
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"

# SDKMAN Environment
export SDKMAN_DIR="$HOME/.sdkman"
[[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"
EOF

echo "=== ✅ 全部环境配置完成！请运行 'source ~/.bashrc' 激活环境变量。 ==="
