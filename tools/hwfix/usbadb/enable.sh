#!/system/bin/sh
# =============================================================================
# SP970 USB adb 修复 · 启用（运行时，root）
# =============================================================================
# 缺陷：原厂 /init.qcom.usb.rc 第 618 行 on property:sys.usb.config=rndis,adb
#       写的是 functions rndis（漏了 ,adb）且没有 start adbd，却把
#       sys.usb.state 伪报成 rndis,adb。于是内核 gadget 只挂 rndis，
#       FunctionFS 的 ep1/ep2 从未建立，USB adb 永远起不来；
#       框架层又被伪报的 state 骗过，界面/日志看起来「已启用」。
#       相邻第 626 行 on property:sys.usb.config=rndis,none,adb 写法正确
#       （functions rndis,adb + start adbd，PID 仍 9024）。
#
# 做法：把 sys.usb.config 切成 rndis,none,adb，命中 626 行正确规则。
#
# 关键坑：切换 functions 会重建 rndis0 网卡并丢掉 IP，必须立刻重新配置，
#         否则 Windows 侧退成 169.254，网络 adb 也一起失效。
#
# 重要限制：本机 adbd 只服务一条传输。USB adb 生效期间 TCP 5555 会 offline，
#           即「USB adb」与「网络 adb」不能同时用。
#
# 用法：sh enable.sh [看门狗秒数]     默认 300 秒
#       启用后请在确认可用时 touch /data/local/tmp/hwfix_ok，否则自动回滚。
# =============================================================================

D=$(dirname "$0")
L=/data/local/tmp/hwfix
WD=${1:-300}

snap() {
    echo "[$1] cfg=$(getprop sys.usb.config) st=$(getprop sys.usb.state) " \
         "fn=$(cat /sys/class/android_usb/android0/functions) " \
         "ep=$(busybox ls /dev/usb-ffs/adb 2>/dev/null | busybox tr '\n' ' ') " \
         "adbd=$(getprop init.svc.adbd)"
}

echo "=== ENABLE START ==="
snap BASE

# 1) 先起独立看门狗（setsid 脱离会话，adb 断线也不会被回收）
rm -f $L/ok
busybox mkdir -p $L
busybox setsid sh "$D/watchdog.sh" "$WD" </dev/null >/dev/null 2>&1 &
echo "看门狗已启动，${WD}s 内未 touch $L/ok 则自动回滚"

# 2) 切到正确规则
setprop sys.usb.config rndis,none,adb
sleep 4
snap AFTER_SETPROP

# 3) 恢复 rndis0（少了这步 RNDIS 会丢 IP）
busybox ifconfig rndis0 192.168.42.129 netmask 255.255.255.0 up
sleep 2
busybox ifconfig rndis0 | busybox head -3
snap AFTER_RNDIS_UP

echo "=== 现在应该能在 PC 侧看到 USB ADB 接口 ==="
echo "    确认可用后执行：touch $L/ok"
echo "=== ENABLE DONE ==="
