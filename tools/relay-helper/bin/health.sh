#!/system/bin/sh
# 定时体检落盘（crond 以 root 调用）。只追加，不删任何业务数据。
# 设备时基不可靠：以 /proc/uptime 为主时间轴，date 只作展示。
BB=/system/xbin/busybox
echo "=== uptime=$(cat /proc/uptime | $BB cut -d' ' -f1) wall=$($BB date 2>/dev/null) ==="
for i in 0 1 2; do
    d=/sys/bus/msm_subsys/devices/subsys$i
    echo "subsys$i state=$(cat $d/state 2>/dev/null) crash=$(cat $d/crash_count 2>/dev/null)"
done
echo "sim=$(getprop gsm.sim.state) net=$(getprop gsm.network.type) op=$(getprop gsm.operator.alpha)"
echo "memfree=$($BB grep MemFree /proc/meminfo 2>/dev/null)"
echo "panel8080=$(netstat -ltn 2>/dev/null | $BB grep ':8080 ' | $BB wc -l)"
echo "relay=$(ps 2>/dev/null | $BB grep com.sp970.smsrelay | $BB wc -l)"
echo
