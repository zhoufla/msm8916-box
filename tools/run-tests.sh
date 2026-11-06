#!/bin/sh
# 离线测试：不需要设备、不需要 Android SDK，只要一个 JDK。
# 用法：sh tools/run-tests.sh
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)

# 找 JDK：优先 JAVA_HOME，其次 PATH，最后找仓库常见位置
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JBIN="$JAVA_HOME/bin"
elif command -v javac >/dev/null 2>&1; then
    JBIN=$(dirname "$(command -v javac)")
else
    JBIN=""
    for d in "$HOME"/android-tools/jdk-*/bin "$HOME"/jdk-*/bin /usr/lib/jvm/*/bin; do
        [ -x "$d/javac" ] && JBIN=$d && break
    done
    [ -n "$JBIN" ] || { echo "找不到 javac，请设置 JAVA_HOME"; exit 1; }
fi

# org.json：只用它给桌面端补上 Android 里那份实现
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/msm8916-box
JAR=$CACHE/json-20240303.jar
if [ ! -f "$JAR" ]; then
    mkdir -p "$CACHE"
    echo "下载 org.json → $JAR"
    curl -fsSL -o "$JAR" \
        https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/com/sp970/smsrelay" "$WORK/android/util"

# 能在桌面跑的部分：不含任何 android.* 依赖（Logs 用 test 里的替身）
for f in Channel SmsEvent Deliver Sender Tpl Ver Retry MD5 Http WifiCfg LteBand FreqTable; do
    cp "$ROOT/src/com/sp970/smsrelay/$f.java" "$WORK/com/sp970/smsrelay/"
done
cp -r "$ROOT/test/." "$WORK/"

cd "$WORK"
find . -name '*.java' -exec sed -i 's/\r$//' {} +
"$JBIN/javac" -encoding UTF-8 -cp "$JAR" -d out $(find . -name '*.java')

echo "== 逻辑测试"
"$JBIN/java" -cp "out:$JAR" com.sp970.smsrelay.LogicTest

echo "== WiFi 配置测试"
"$JBIN/java" -cp "out:$JAR" com.sp970.smsrelay.WifiTest

echo "== LTE 频段测试"
"$JBIN/java" -cp "out:$JAR" com.sp970.smsrelay.BandTest

echo "== CPU 频率测试"
"$JBIN/java" -cp "out:$JAR" com.sp970.smsrelay.CpuFreqTest

echo "== HTTP 测试"
"$JBIN/java" -cp "out:$JAR" com.sp970.smsrelay.HttpTest
