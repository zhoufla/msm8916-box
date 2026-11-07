#!/bin/sh
# 原型验证：root 进程直接写 SMS provider（收件箱镜像可行性测试）
set -x
cd "$(dirname "$0")"
export JAVA_HOME=/home/zhoufl/android-tools/jdk-17.0.13+11
BT=/home/zhoufl/android-sdk/build-tools/34.0.0
PJ=/home/zhoufl/android-sdk/platforms/android-19/android.jar
export PATH="$JAVA_HOME/bin:$BT:$PATH"

rm -rf classes out tiny.jar
mkdir -p src/t classes out
[ -f src/t/Tiny.java ] || exit 1

PATH="$JAVA_HOME/bin:$PATH" javac -source 1.8 -target 1.8 -encoding UTF-8 \
    -bootclasspath "$PJ" -d classes src/t/Tiny.java || exit 2

"$BT/d8" --min-api 19 --output out $(find classes -name '*.class') || exit 3
cd out
"$JAVA_HOME/bin/jar" cf ../tiny.jar classes.dex
cd ..
PATH="$JAVA_HOME/bin:$PATH" ls -l tiny.jar
exit 0
