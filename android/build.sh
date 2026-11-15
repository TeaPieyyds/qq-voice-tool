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
  --version-code 10 --version-name "2.8" \
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

echo "[7/7] 签名（固定使用 Rinkai）"

# 签名密钥解析顺序（任何 CI 都能用）：
#   1) 环境变量 SIGN_KEY / SIGN_CERT（绝对路径）
#   2) 环境变量 SIGN_KEY_B64 / SIGN_CERT_B64（base64，适合放 Secrets）
#   3) 仓库内 keys/Rinkai.pk8 + keys/Rinkai.x509.pem
SIGN_TMP="$OUT/sign"
mkdir -p "$SIGN_TMP"

if [ -n "$SIGN_KEY" ] && [ -n "$SIGN_CERT" ]; then
  KS_PK8="$SIGN_KEY"
  KS_PEM="$SIGN_CERT"
  echo "  来源：环境变量 SIGN_KEY / SIGN_CERT"
elif [ -n "$SIGN_KEY_B64" ] && [ -n "$SIGN_CERT_B64" ]; then
  KS_PK8="$SIGN_TMP/Rinkai.pk8"
  KS_PEM="$SIGN_TMP/Rinkai.x509.pem"
  printf '%s' "$SIGN_KEY_B64"  | base64 -d > "$KS_PK8"
  printf '%s' "$SIGN_CERT_B64" | base64 -d > "$KS_PEM"
  chmod 600 "$KS_PK8"
  echo "  来源：环境变量 SIGN_KEY_B64 / SIGN_CERT_B64"
else
  KS_PK8="$HERE/keys/Rinkai.pk8"
  KS_PEM="$HERE/keys/Rinkai.x509.pem"
  echo "  来源：仓库 keys/"
fi

if [ ! -f "$KS_PK8" ] || [ ! -f "$KS_PEM" ]; then
  echo ""
  echo "  ✗ 找不到签名密钥，拒绝用临时签名构建"
  echo "    pk8 : $KS_PK8"
  echo "    pem : $KS_PEM"
  echo "    三种提供方式："
  echo "      SIGN_KEY / SIGN_CERT              绝对路径"
  echo "      SIGN_KEY_B64 / SIGN_CERT_B64      base64（推荐放 CI Secrets）"
  echo "      keys/Rinkai.pk8 + keys/Rinkai.x509.pem   随仓库提交"
  exit 1
fi

"$BT/apksigner" sign \
  --key "$KS_PK8" \
  --cert "$KS_PEM" \
  --out "$OUT/app-signed.apk" "$OUT/aligned.apk"

# 自检：确认签出来的就是 Rinkai
EXPECT_FP="${SIGN_EXPECT_SHA256:-bcfb3350cf10e3524cb59714d51f0f165d5bc10391acadec67572375233ad79a}"
VERIFY_OUT="$("$BT/apksigner" verify --print-certs "$OUT/app-signed.apk" 2>/dev/null)"
GOT_FP="$(echo "$VERIFY_OUT" | grep -i "certificate SHA-256 digest" | head -1 | sed "s/.*digest: *//" | tr -d " ")"

if [ -n "$EXPECT_FP" ] && [ "$GOT_FP" != "$EXPECT_FP" ]; then
  echo ""
  echo "  X 签名指纹不对，已中止"
  echo "    期望 $EXPECT_FP"
  echo "    实际 $GOT_FP"
  exit 1
fi
echo "  指纹校验通过：$GOT_FP"

echo
echo "完成：$OUT/app-signed.apk"
ls -lh "$OUT/app-signed.apk"
