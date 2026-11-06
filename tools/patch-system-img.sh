#!/bin/sh
# 离线定制 system.img：删除厂商应用 + 内置短信转发 APK。
#
# 不需要 root：debugfs 可以以普通用户身份改 ext4 镜像。
# 用法：
#   ./patch-system-img.sh [输入 system.img] [输出 system-custom.img]
set -e

SRCDIR=$(cd "$(dirname "$0")" && pwd)
SRC="${1:-/mnt/f/wifi/sp970/bak/v11/system.img}"
OUT="${2:-$SRCDIR/system-custom.img}"
LIST="$SRCDIR/remove-vendor.txt"
APK="$SRCDIR/dist/com.sp970.smsrelay-1.0.apk"

[ -f "$SRC" ] || { echo "找不到输入镜像: $SRC"; exit 1; }
[ -f "$LIST" ] || { echo "找不到删除清单: $LIST"; exit 1; }
[ -f "$APK" ] || { echo "找不到 APK: $APK（先跑 ./build.sh）"; exit 1; }

echo "输入: $SRC"
echo "输出: $OUT"
cp -f "$SRC" "$OUT"
chmod u+w "$OUT"

echo "== 1/4 修复日志（原镜像 needs_recovery）"
e2fsck -f -y "$OUT" >/dev/null 2>&1 || true

echo "== 2/4 删除清单内的应用"
before=$(debugfs -R "ls -l /app" "$OUT" 2>/dev/null | grep -c "\.apk" || true)
removed=0
while IFS= read -r line; do
    case "$line" in
        ''|'#'*) continue ;;
    esac
    name=$(echo "$line" | tr -d ' \r')
    if debugfs -R "stat /app/$name" "$OUT" >/dev/null 2>&1; then
        debugfs -w -R "rm /app/$name" "$OUT" >/dev/null 2>&1 && {
            echo "   删除 /app/$name"
            removed=$((removed + 1))
        }
    else
        echo "   跳过（不存在）: $name"
    fi
done < "$LIST"
after=$(debugfs -R "ls -l /app" "$OUT" 2>/dev/null | grep -c "\.apk" || true)
echo "   /app: $before -> $after（删除 $removed 个）"

echo "== 3/4 注入短信转发 APK"
debugfs -w -R "mkdir /priv-app/SmsRelay" "$OUT" >/dev/null 2>&1 || true
debugfs -w -R "write $APK /priv-app/SmsRelay/SmsRelay.apk" "$OUT" >/dev/null
debugfs -w -R "sif /priv-app/SmsRelay/SmsRelay.apk mode 0100644" "$OUT" >/dev/null
debugfs -w -R "sif /priv-app/SmsRelay/SmsRelay.apk uid 0" "$OUT" >/dev/null
debugfs -w -R "sif /priv-app/SmsRelay/SmsRelay.apk gid 0" "$OUT" >/dev/null
debugfs -R "ls -l /priv-app/SmsRelay" "$OUT" 2>/dev/null | grep SmsRelay

echo "== 4/4 收尾校验"
e2fsck -f -y "$OUT" >/dev/null 2>&1 || true
echo "   文件系统:"
e2fsck -n "$OUT" 2>&1 | tail -2
echo "   剩余空间:"
debugfs -R "stats" "$OUT" 2>/dev/null | grep -E "Free blocks|Block count"
md5sum "$OUT"
echo "完成：$OUT"
