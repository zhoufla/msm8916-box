#!/system/bin/sh
# =============================================================================
# SP970 USB adb 修复 · 看门狗（自动回滚）
# =============================================================================
# 由 enable.sh 以 setsid 拉起。等待 N 秒：
#   - 出现 /data/local/tmp/hwfix/ok -> 认定成功，保留配置
#   - 否则回滚到 rndis,adb（原厂可用状态）并恢复 rndis0，保证 PC 侧还能连回来
#
# 为什么要 setsid：adb shell 退出时会回收同一会话的子进程，
#   普通 nohup &\ 起不来，必须脱离会话。
#
# 用法：sh watchdog.sh [秒数]
# =============================================================================

L=/data/local/tmp/hwfix
WAIT=${1:-300}
busybox mkdir -p $L
exec >> $L/watchdog.log 2>&1

echo "=== WATCHDOG START $(date) pid=$$ wait=${WAIT}s ==="
sleep $WAIT

if [ -e $L/ok ]; then
    echo "ok 标记存在，保留当前配置 $(date)"
else
    echo "超时未确认，执行回滚 $(date)"
    setprop sys.usb.config rndis,adb
    sleep 4
    busybox ifconfig rndis0 192.168.42.129 netmask 255.255.255.0 up 2>/dev/null
    echo "回滚后: cfg=$(getprop sys.usb.config) fn=$(cat /sys/class/android_usb/android0/functions) adbd=$(getprop init.svc.adbd)"
fi
echo "=== WATCHDOG END $(date) ==="
