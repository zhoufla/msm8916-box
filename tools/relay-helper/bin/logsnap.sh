#!/system/bin/sh
# =============================================================================
# 内核/系统日志快照（每 2 分钟，由 busybox crond 以 root 调用）
# =============================================================================
# 本机 3.10 内核没有 ramoops/pstore：/proc/last_kmsg 与 /sys/fs/pstore 都不存在。
# 一旦系统硬挂或用户拔电，内存里的 dmesg / logcat 全部丢失，事后无法定位。
# 这里覆盖式保留一个很小的快照文件，放在 /sdcard/Download（断电后仍在）。
#
# 关键：全程不用管道（例如 dmesg | tail）。上游被 SIGPIPE 打死会在
# /data/tombstones 里刷出新的 tombstone，反而给已经高负载的设备添乱
# （2026-11-07 事故复盘中确认过这类噪音）。一律重定向到临时文件再读。
#
# 先写 .tmp 再 mv 原子替换：写到一半拔电也不会破坏上一份完整快照。
# =============================================================================

BB=/system/xbin/busybox
T=/data/local/tmp/relay-helper
DL=/sdcard/Download
OUT=$DL/smsrelay-lastkmsg.log
KEEP_DMESG=60
KEEP_LOGCAT=80

$BB mkdir -p "$T" "$DL" 2>/dev/null

UP=$($BB cut -d' ' -f1 /proc/uptime 2>/dev/null)
WALL=$($BB date 2>/dev/null)

dmesg > "$T/dmesg.tmp" 2>&1
logcat -d -v time -t $KEEP_LOGCAT > "$T/logcat.tmp" 2>&1

{
  echo "=== snapshot uptime=$UP wall=$WALL ==="
  echo "--- dmesg (last $KEEP_DMESG) ---"
  $BB tail -n $KEEP_DMESG "$T/dmesg.tmp"
  echo "--- logcat (last $KEEP_LOGCAT) ---"
  $BB tail -n $KEEP_LOGCAT "$T/logcat.tmp"
} > "$OUT.tmp" 2>&1

$BB mv "$OUT.tmp" "$OUT"
$BB rm -f "$T/dmesg.tmp" "$T/logcat.tmp"
