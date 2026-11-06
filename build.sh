#!/bin/sh
# 构建 SP970 短信转发 APK（不依赖 Gradle）
# 依赖：JDK 17、Android SDK（platforms/android-19 + build-tools/34.0.0）
set -e

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-19/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk-17.0.13+11}"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

for f in "$PLATFORM" "$BT/aapt" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    [ -e "$f" ] || { echo "缺少: $f"; exit 1; }
done

APP=com.sp970.smsrelay
OUT=dist
rm -rf build "$OUT"
mkdir -p build/classes build/dex "$OUT"

echo "== 1/6 生成未签名骨架（manifest 编译成二进制 XML）"
aapt package -f -M AndroidManifest.xml -I "$PLATFORM" -F build/base.apk \
    --min-sdk-version 19 --target-sdk-version 19

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
    --key-pass pass:sp970sp970 --out "$OUT/$APP-1.0.apk" build/aligned.apk

apksigner verify --print-certs "$OUT/$APP-1.0.apk" | head -5
ls -lh "$OUT/$APP-1.0.apk"
echo "完成"
