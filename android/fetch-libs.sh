#!/bin/bash
# 下载 Shizuku 的三个 AAR 并解出 classes.jar 到 libs/
# 版本固定，避免上游变动导致编译不过
set -e

VER="${SHIZUKU_VER:-13.1.5}"
BASE="https://maven.aliyun.com/repository/public/dev/rikka/shizuku"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
mkdir -p libs

fetch() {
  local name="$1"
  local url="$BASE/$name/$VER/$name-$VER.aar"
  echo "→ $name $VER"
  curl -fsSL --retry 3 -o "libs/$name.aar" "$url" || {
    echo "  下载失败：$url"
    echo "  可手动下载后放到 libs/ 目录"
    exit 1
  }
  python - "libs/$name.aar" "libs/shizuku-$name.jar" <<'PY'
import sys, zipfile, shutil
with zipfile.ZipFile(sys.argv[1]) as z, z.open('classes.jar') as f, open(sys.argv[2], 'wb') as o:
    shutil.copyfileobj(f, o)
PY
  rm -f "libs/$name.aar"
  echo "  → libs/shizuku-$name.jar"
}

fetch api
fetch provider
fetch aidl

echo
echo "✅ 依赖就绪："
ls -lh libs/*.jar
