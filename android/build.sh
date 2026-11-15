#!/bin/bash
# 手工构建 APK（不依赖 Gradle）：javac -> d8 -> aapt2 link -> zip -> zipalign -> apksigner
# Linux / macOS 通用。Windows 用 WSL。
set -e

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
PLAT="${PLATFORM_JAR:-$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
OUT="$HERE/out"
MIN_SDK=30
TARGET_SDK=34
LIBS="$HERE/libs/shizuku-api.jar:$HERE/libs/shizuku-provider.jar:$HERE/libs/shizuku-aidl.jar"

echo "SDK      = $SDK"
echo "BT       = $BT"
echo "PLATFORM = $PLAT"

[ -f "$PLAT" ] || { echo "缺 android.jar：$PLAT"; exit 1; }
[ -f "$HERE/libs/shizuku-api.jar" ] || { echo "缺依赖，先跑 bash fetch-libs.sh"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/7] javac"
javac --release 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLAT:$LIBS" \
  -d "$OUT/classes" \
  $(find "$HERE/src" "$HERE/stubs" -name "*.java")

echo "[2/7] classes.jar"
python3 - "$OUT/classes" "$OUT/classes.jar" <<'PY'
import sys, os, zipfile
src, dst = sys.argv[1], sys.argv[2]
n = 0
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
            n += 1
print(f"  {n} 个 class")
PY

echo "[3/7] d8"
"$BT/d8" --min-api $MIN_SDK --lib "$PLAT" --output "$OUT/dex" \
  "$OUT/classes.jar" \
  "$HERE/libs/shizuku-api.jar" \
  "$HERE/libs/shizuku-provider.jar" \
  "$HERE/libs/shizuku-aidl.jar"

echo "[4/7] aapt2 compile + link"
RES_FLAT="$OUT/res-flat"
rm -rf "$RES_FLAT" && mkdir -p "$RES_FLAT"
if [ -d "$HERE/res" ]; then
  find "$HERE/res" -type f -print0 | xargs -0 -I{} "$BT/aapt2" compile -o "$RES_FLAT" {}
  echo "  资源 $(ls -1 "$RES_FLAT" | wc -l) 个"
fi

"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code 5 --version-name "2.3" \
  $(ls -1 "$RES_FLAT"/*.flat 2>/dev/null | tr '\n' ' ')

echo "[5/7] 打包 dex + assets"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$HERE/assets" <<'PY'
import sys, os, zipfile, shutil
apk, dex, assets = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = apk + ".tmp"
shutil.copy(apk, tmp)
cnt = 0
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    if os.path.isdir(assets):
        for root, _, files in os.walk(assets):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, "assets/" + os.path.relpath(p, assets).replace("\\", "/"))
                cnt += 1
shutil.move(tmp, apk)
print(f"  dex + {cnt} 个资源")
PY

echo "[6/7] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
KS="$HERE/voicehelper.keystore"
KEY_ALIAS=voicehelper
KEY_PASS=android

if [ ! -f "$KS" ]; then
  echo "  缺签名密钥 $KS，无法继续"
  echo "  （请从仓库恢复，否则每次构建签名都会变）"
  exit 1
fi
echo "  使用固定密钥：$(basename "$KS")"

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/app-signed.apk" "$OUT/aligned.apk"

echo
echo "完成：$OUT/app-signed.apk"
ls -lh "$OUT/app-signed.apk"