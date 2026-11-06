#!/system/bin/sh
# =============================================================================
# SP970 USB adb 修复 · 关闭（回到网络 adb）
# =============================================================================
# 把 sys.usb.config 切回 rndis,adb（原厂默认），撤掉 adb function，
# 恢复 rndis0 的 IP，让 adbd 重新回到 TCP 传输。
#
# 注意：
#   - 切回后 USB adb 立即消失，网络 adb 需要 PC 侧重新 connect：
#       adb kill-server && adb connect 192.168.42.129:5555
#     （server 会记着旧的 offline 传输，不 kill-server 常常连不上）
#   - 若设备已拔掉 USB 线插在充电器上，USB 传输本身不存在，
#     网络 adb 会自动恢复，同样需要上面那条 connect。
# 用法：sh disable.sh
# =============================================================================

L=/data/local/tmp/hwfix
busybox mkdir -p $L

echo "=== 关闭前 ==="
echo "cfg=$(getprop sys.usb.config) fn=$(cat /sys/class/android_usb/android0/functions)"

# 标记 ok，避免还在跑的看门狗又来回滚一次
touch $L/ok

setprop sys.usb.config rndis,adb
sleep 4

busybox ifconfig rndis0 192.168.42.129 netmask 255.255.255.0 up 2>/dev/null

echo "=== 关闭后 ==="
echo "cfg=$(getprop sys.usb.config) st=$(getprop sys.usb.state)"
echo "fn=$(cat /sys/class/android_usb/android0/functions)"
echo "ep=$(busybox ls /dev/usb-ffs/adb 2>/dev/null | busybox tr '\n' ' ')"
busybox ifconfig rndis0 | busybox head -3
echo "=== DONE：请在 PC 侧执行 adb kill-server && adb connect 192.168.42.129:5555 ==="
