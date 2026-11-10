#!/system/bin/sh
# =============================================================================
# 内核/系统日志快照（每 2 分钟，由 busybox crond 以 root 调用）
# =============================================================================
# 本机 3.10 内核没有 ramoops/pstore：/proc/last_kmsg 与 /sys/fs/pstore 都不存在。
# 一旦系统硬挂或用户拔电，内存里的 dmesg / logcat 全部丢失，事后无法定位。
# 这里覆盖式保留很小的快照文件，放在 /sdcard/Download（断电后仍在）。
#
# 关键：全程不用管道（例如 dmesg | tail）。上游被 SIGPIPE 打死会在
# /data/tombstones 里刷出新的 tombstone，反而给已经高负载的设备添乱
# （2026-11-07 事故复盘中确认过这类噪音）。一律重定向到临时文件再读。
#
# 先写 .tmp 再 mv 原子替换：写到一半拔电也不会破坏上一份完整快照。
#
# 2026-11-10：显示帧缓冲（mdss_fb_release/libin）、USB 描述符轮询、无电池机型的
# 充电控制循环每秒刷十几行，3.10 的 dmesg 环形缓冲只剩约 2 分钟，快照里绝大多数
# 是噪音、真正的内核报错几分钟就被冲掉。现在按 NOISE 过滤后单独落一份
# smsrelay-dmesg.log（带 uptime/wall 表头），合并快照也改用过滤后的内容。
# =============================================================================

BB=/system/xbin/busybox
T=/data/local/tmp/relay-helper
DL=/sdcard/Download
OUT=$DL/smsrelay-lastkmsg.log
DOUT=$DL/smsrelay-dmesg.log
KEEP_DMESG=200
KEEP_LOGCAT=80
# 刷屏来源：libin / mdss_fb_release（显示）、read descriptors|strings（USB 枚举）、
# CHG:（无电池机型的充电控制循环）、untracked pid（保活每轮 root 执行派生的 sh 正常退出）。
NOISE='mdss_fb_release|libin|read descriptors|read strings|CHG:|untracked pid'

$BB mkdir -p "$T" "$DL" 2>/dev/null

UP=$($BB cut -d' ' -f1 /proc/uptime 2>/dev/null)
WALL=$($BB date 2>/dev/null)

dmesg > "$T/dmesg.tmp" 2>&1
logcat -d -v time -t $KEEP_LOGCAT > "$T/logcat.tmp" 2>&1

$BB grep -v -E "$NOISE" "$T/dmesg.tmp" > "$T/dmesg.clean.tmp" 2>/dev/null
GRC=$?
# grep 出错（返回 >1，例如缺少 -E）时退回原始 dmesg：宁可吵，也别丢内容
if [ $GRC -gt 1 ]; then
  $BB cp "$T/dmesg.tmp" "$T/dmesg.clean.tmp"
fi

{
  echo "=== dmesg snapshot uptime=$UP wall=$WALL ==="
  $BB tail -n $KEEP_DMESG "$T/dmesg.clean.tmp"
} > "$DOUT.tmp" 2>&1

{
  echo "=== snapshot uptime=$UP wall=$WALL ==="
  echo "--- dmesg (滤波后 last 60，完整见 smsrelay-dmesg.log) ---"
  $BB tail -n 60 "$T/dmesg.clean.tmp"
  echo "--- logcat (last $KEEP_LOGCAT) ---"
  $BB tail -n $KEEP_LOGCAT "$T/logcat.tmp"
} > "$OUT.tmp" 2>&1

$BB mv "$OUT.tmp" "$OUT"
$BB mv "$DOUT.tmp" "$DOUT"
$BB rm -f "$T/dmesg.tmp" "$T/dmesg.clean.tmp" "$T/logcat.tmp"
