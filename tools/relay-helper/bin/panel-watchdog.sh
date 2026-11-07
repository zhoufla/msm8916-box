#!/system/bin/sh
# JZ02 8080 面板看门狗（crond root 调用，每 5 分钟）
# 本机 ROM 的 BOOT_COMPLETED 不送达第三方静态 receiver，且 378MB 内存下
# LMK 可能回收 app 进程；只在 8080 没监听时拉起，避免重复启动。
BB=/system/xbin/busybox
if netstat -ltn 2>/dev/null | $BB grep -q ':8080 '; then
    exit 0
fi
echo "panel down uptime=$($BB cat /proc/uptime | $BB cut -d' ' -f1) -> restart"
/system/bin/am start -n com.sp970.smsrelay/.MainActivity >/dev/null 2>&1
$BB sleep 3
/system/bin/am startservice -n com.sp970.smsrelay/.RelayService >/dev/null 2>&1
