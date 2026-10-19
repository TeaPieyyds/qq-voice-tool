#!/bin/bash
# 手工构建 APK（不依赖 Gradle）：javac → jar → d8 → aapt2 link → zipalign → apksigner
set -e

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
PLAT="${PLATFORM_JAR:-$SDK/platforms/android-34/android.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
OUT="$HERE/out"
PKG="com.slk.voicehelper"
MIN_SDK=30
TARGET_SDK=34
LIBS="libs/shizuku-api.jar;libs/shizuku-provider.jar;libs/shizuku-aidl.jar"

if [ ! -f "$PLAT" ]; then
  echo "找不到 android.jar：$PLAT"
  echo "请先装 Android SDK（platforms;android-34 + build-tools;34.0.0），或用环境变量指定："
  echo "  ANDROID_HOME=/path/to/Android/Sdk bash build.sh"
  exit 1
fi
if [ ! -d "$HERE/libs" ] || [ ! -f "$HERE/libs/shizuku-api.jar" ]; then
  echo "缺少依赖 jar，先运行：bash fetch-libs.sh"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac"
javac --release 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLAT;$LIBS" \
  -d "$OUT/classes" \
  $(find "$HERE/src" "$HERE/stubs" -name "*.java")

echo "[2/6] 打包 classes.jar + d8"
python - "$OUT/classes" "$OUT/classes.jar" <<'PY'
import sys, os, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
print("  classes.jar ok")
PY
cmd //c "$(cygpath -w "$BT/d8.bat") --min-api $MIN_SDK --lib $(cygpath -w "$PLAT") --output $(cygpath -w "$OUT/dex") $(cygpath -w "$OUT/classes.jar") $(cygpath -w "$HERE/libs/shizuku-api.jar") $(cygpath -w "$HERE/libs/shizuku-provider.jar") $(cygpath -w "$HERE/libs/shizuku-aidl.jar")"

echo "[3/6] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code 1 --version-name "1.0" \
  ${RES_ARGS:-}

if [ ! -f "$HERE/../node_modules/silk-wasm/lib/silk.wasm" ]; then
  echo "缺少 silk-wasm，先在项目根目录执行：npm install"
  exit 1
fi

echo "[3.5/6] 拷贝 silk-wasm 到 assets"
mkdir -p "$HERE/assets/www/lib"
cp -f "$HERE/../node_modules/silk-wasm/lib/index.mjs" "$HERE/assets/www/lib/"
cp -f "$HERE/../node_modules/silk-wasm/lib/silk.wasm" "$HERE/assets/www/lib/"
ls -lh "$HERE/assets/www/lib" | tail -2

echo "[4/6] 打入 classes.dex / assets"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" "$HERE/assets" <<'PY'
import sys, os, zipfile, shutil
apk, dex, assets = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = apk + ".tmp"
shutil.copy(apk, tmp)
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    if os.path.isdir(assets):
        for root, _, files in os.walk(assets):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, "assets/" + os.path.relpath(p, assets).replace("\\", "/"))
shutil.move(tmp, apk)
print("  已写入 dex / assets")
PY

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
KS="$HERE/debug.keystore"
KEYTOOL="${KEYTOOL:-keytool}"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "  已生成调试签名"
fi
cmd //c "$(cygpath -w "$BT/apksigner.bat") sign --ks $(cygpath -w "$KS") --ks-pass pass:android --key-pass pass:android --out $(cygpath -w "$OUT/app-signed.apk") $(cygpath -w "$OUT/aligned.apk")"

echo
echo "✅ 完成：$OUT/app-signed.apk"
ls -lh "$OUT/app-signed.apk"
