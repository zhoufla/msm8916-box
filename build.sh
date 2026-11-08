#!/bin/sh
# 构建 SP970 短信转发 APK（不依赖 Gradle）
# 依赖：JDK 17、Android SDK（platforms/android-19 + build-tools/34.0.0）
# 版本号：默认取 AndroidManifest.xml；CI 打 tag 时用 VER=1.2.0 VC=42 覆盖
# 机型：MODEL=sp970（默认，无后缀）| MODEL=jz02（产物 -jz02 后缀 + 内嵌 jz02 默认配置）
set -e

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-19/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk-17.0.13+11}"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

MODEL="${MODEL:-sp970}"
case "$MODEL" in
  sp970) SUFFIX="" ;;
  jz02)  SUFFIX="-jz02" ;;
  *) echo "未知机型: $MODEL（sp970 | jz02）"; exit 1 ;;
esac

for f in "$PLATFORM" "$BT/aapt" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    [ -e "$f" ] || { echo "缺少: $f"; exit 1; }
done

APP=com.sp970.smsrelay
DEF_VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
DEF_VC=$(grep -o 'versionCode="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
VER="${VER:-$DEF_VER}"
VC="${VC:-$DEF_VC}"
OUT=dist
rm -rf build "$OUT"
mkdir -p build/classes build/dex "$OUT"

echo "== 1/6 生成未签名骨架（manifest + assets 网页） versionName=$VER versionCode=$VC model=$MODEL"
# jz02 包内嵌机型默认配置（首次开机无 /sdcard/smsrelay.conf 时的兜底）；sp970 用 AppConfig 内置默认
if [ "$MODEL" = "jz02" ]; then
  cp config/smsrelay.conf.jz02.example assets/model.conf
fi
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/" \
    AndroidManifest.xml > build/AndroidManifest.xml
aapt package -f -M build/AndroidManifest.xml -A assets -I "$PLATFORM" -F build/base.apk \
    --min-sdk-version 19 --target-sdk-version 19
rm -f assets/model.conf

echo "== 2/6 编译 Java"
find src -name '*.java' > build/sources.txt
if ! javac -source 1.8 -target 1.8 -encoding UTF-8 -bootclasspath "$PLATFORM" \
        -d build/classes @build/sources.txt 2> build/javac.log; then
    cat build/javac.log
    echo "编译失败"
    exit 1
fi
grep -E "error|错误" build/javac.log || true

echo "== 3/6 生成 dex"
find build/classes -name '*.class' > build/classes.txt
d8 --min-api 19 --lib "$PLATFORM" --output build/dex @build/classes.txt

echo "== 4/6 打包"
cp build/base.apk build/unsigned.apk
(cd build && python3 -c "
import zipfile
z = zipfile.ZipFile('unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('dex/classes.dex', 'classes.dex')
z.close()
")

echo "== 5/6 对齐"
zipalign -f 4 build/unsigned.apk build/aligned.apk

echo "== 6/6 签名"
if [ ! -e debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -alias sp970 \
        -storepass sp970sp970 -keypass sp970sp970 \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SP970 SMS Relay, O=sp970, C=CN" >/dev/null 2>&1
    echo "   已生成 debug.keystore"
fi
apksigner sign --ks debug.keystore --ks-pass pass:sp970sp970 \
    --key-pass pass:sp970sp970 --out "$OUT/$APP-$VER$SUFFIX.apk" build/aligned.apk

apksigner verify --print-certs "$OUT/$APP-$VER$SUFFIX.apk" | head -5
ls -lh "$OUT/$APP-$VER$SUFFIX.apk"

# 升级用的元数据：CI 会把它当 release 资产挂上去，设备只比 version + sha256
SIZE=$(wc -c < "$OUT/$APP-$VER$SUFFIX.apk" | tr -d ' ')
SHA=$(sha256sum "$OUT/$APP-$VER$SUFFIX.apk" | cut -d' ' -f1)
cat > "$OUT/latest$SUFFIX.json" <<EOF
{
  "version": "$VER",
  "code": $VC,
  "model": "$MODEL",
  "apk": "$APP-$VER$SUFFIX.apk",
  "size": $SIZE,
  "sha256": "$SHA"
}
EOF
echo "机型 $MODEL · 版本 $VER (versionCode $VC) sha256=${SHA%????????????????????????????????????????????????}"
echo "完成"
