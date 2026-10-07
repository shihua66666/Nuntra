#!/usr/bin/env bash
# 生成固定 debug 签名密钥库（只需运行一次）
#
# 不固定签名的话，每次 CI 构建的 APK 签名都不同，
# Android 会拒绝覆盖安装，必须先卸载 → 数据（标签/关注人/主题）全丢。
set -euo pipefail

cd "$(dirname "$0")/.."

KS="app/keystore/debug.keystore"
if [ -f "$KS" ]; then
  echo "[跳过] $KS 已存在。要重新生成请先删除它。"
else
  if ! command -v keytool >/dev/null 2>&1; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
      PATH="$JAVA_HOME/bin:$PATH"
    else
      echo "[错误] 找不到 keytool。请安装 JDK 17 并设置 JAVA_HOME。" >&2
      exit 1
    fi
  fi
  mkdir -p app/keystore
  keytool -genkeypair -v \
    -keystore "$KS" \
    -storetype PKCS12 \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
  echo "[成功] 已生成 $KS"
fi

echo
echo "=== KEYSTORE_BASE64（粘贴到 GitHub Secrets）==="
base64 -w0 "$KS" 2>/dev/null || base64 "$KS" | tr -d "\n"
echo
