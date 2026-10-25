#!/usr/bin/env bash
# 不依赖 Gradle 的 APK 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
#   ANDROID_HOME=/path/to/sdk bash build.sh
#
# 为什么不用 Gradle：这个项目只有一个 module、零第三方依赖，
# 用 Gradle 要下几百 MB 的依赖 + 整套 AGP，构建时间从 20 秒变成 5 分钟。
# 手工链路反而更容易在 CI 里复现。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
KS="$ROOT/keystore.jks"
KS_PASS="${KS_PASS:-todayproxy}"
KS_ALIAS="${KS_ALIAS:-todayproxy}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"

say() { printf '\033[32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[ -x "$BT/aapt2" ]  || die "找不到 aapt2：$BT"
[ -f "$PLATFORM" ]  || die "找不到 android.jar：$PLATFORM"
command -v javac  >/dev/null || die "没有 javac，请装 JDK 17"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen"

say "1/6 编译资源 (aapt2 compile)"
count=0
while IFS= read -r f; do
  "$BT/aapt2" compile -o "$OUT/res" "$f"
  count=$((count+1))
done < <(find "$APP/res" -type f \( -name '*.png' -o -name '*.xml' \))
say "    编译了 $count 个资源文件"

say "2/6 链接资源 (aapt2 link)"
# shellcheck disable=SC2046
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 23 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  $(find "$OUT/res" -name '*.flat')

say "3/6 编译 Java"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  -encoding UTF-8 \
  @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
[ -f "$OUT/classes/com/main8986/todayproxy/MainActivity.class" ] \
  || die "javac 编译失败"

say "4/6 dex (d8)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 23 --lib "$PLATFORM" \
  --output "$OUT/dex" @"$OUT/classes.txt"

say "5/6 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "6/6 签名"
if [ ! -f "$KS" ]; then
  say "    生成签名证书（首次）"
  keytool -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=today-proxy, OU=local, O=main8986, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$ROOT/today-proxy-android.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$ROOT/today-proxy-android.apk" | head -3 || true
ls -lh "$ROOT/today-proxy-android.apk"
say "完成：$ROOT/today-proxy-android.apk"
