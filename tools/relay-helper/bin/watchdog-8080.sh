#!/system/bin/sh
# =============================================================================
# 面板 8080 第二道防线（root，busybox crond 每 2 分钟调用一次）
# =============================================================================
# 第一道防线是 App 内的 AlarmManager（5 分钟）。2026-11-07 事故中系统半卡死，
# 闹钟 15 分钟没有投递，App 被 LMK 杀掉后一直没回来；内核又没有 /dev/watchdog、
# 没有 pstore，整机没人能复位，最后只能手动拔电。
#
# 这一层跑在 init 拉起的独立 root 进程里：只要内核还能调度 shell 就有效。
# 探活端点 /api/ping 免认证，只回 "ok"；端口从 App 写的端口文件读取
# （面板 8080 被占用时会自动顺延，读配置猜不到实际端口）。
#
# 端口文件不存在 = 面板被有意关闭（web.enabled=false）或新 APK 尚未启动过，
# 此时直接跳过，绝不把「用户主动关面板」判成故障去 reboot。
#
# 分级处置（每轮 2 分钟）：
#   fail=1   发 WATCHDOG_TICK 广播 → WatchdogReceiver 探活并重建 WebPanel
#   fail=2/3 force-stop 清掉卡死进程 → am start 拉起 Activity（清 stopped 状态）
#            → 广播；注意普通广播默认不投递给 force-stop 后的包，必须带
#            --include-stopped-packages，且 am start 后才能恢复服务
#   fail>=4  reboot（本机唯一可靠的整机复位手段）
# 临时禁用自动 reboot：touch /sdcard/Download/smsrelay-no-reboot
#
# 全程不使用管道，避免 SIGPIPE 在 /data/tombstones 刷 tombstone 噪音。
# =============================================================================

BB=/system/xbin/busybox
AM=/system/bin/am
REBOOT=/system/bin/reboot
PKG=com.sp970.smsrelay
ACTION=com.sp970.smsrelay.WATCHDOG_TICK
RECV=$PKG/.WatchdogReceiver
T=/data/local/tmp/relay-helper
DL=/sdcard/Download
PORTFILE=$DL/smsrelay-port
NOREBOOT=$DL/smsrelay-no-reboot
STATE=$T/wd8080.state
OUT=$T/wd8080.out
LOG=$DL/smsrelay-watchdog.log

[ -f "$PORTFILE" ] || exit 0
$BB mkdir -p "$T" "$DL" 2>/dev/null

PORT=$($BB cat "$PORTFILE" 2>/dev/null)
case "$PORT" in
  ''|*[!0-9]*) PORT=8080 ;;
esac

$BB rm -f "$OUT"
ALIVE=0
if $BB wget -q -T 4 -O "$OUT" "http://127.0.0.1:$PORT/api/ping" >/dev/null 2>&1; then
  R=$($BB cat "$OUT" 2>/dev/null)
  [ "$R" = "ok" ] && ALIVE=1
fi
$BB rm -f "$OUT"

if [ "$ALIVE" = 1 ]; then
  echo 0 > "$STATE"
  exit 0
fi

f=0
[ -f "$STATE" ] && f=$($BB cat "$STATE" 2>/dev/null)
case "$f" in
  ''|*[!0-9]*) f=0 ;;
esac
f=$((f + 1))
[ "$f" -gt 4 ] && f=4
echo $f > "$STATE"

UP=$($BB cut -d' ' -f1 /proc/uptime 2>/dev/null)
WALL=$($BB date 2>/dev/null)
echo "=== watchdog uptime=$UP wall=$WALL port=$PORT fail=$f ===" >> "$LOG"

case "$f" in
  1)
    echo "  action: broadcast WATCHDOG_TICK" >> "$LOG"
    $AM broadcast --include-stopped-packages -a "$ACTION" -n "$RECV" >> "$LOG" 2>&1
    ;;
  2|3)
    echo "  action: force-stop + am start + broadcast" >> "$LOG"
    $AM force-stop "$PKG" >> "$LOG" 2>&1
    sleep 1
    # force-stop 会把包置为 stopped，普通广播默认带 FLAG_EXCLUDE_STOPPED_PACKAGES
    # 投递不到静态接收器。am start 是清掉 stopped 状态的可靠动作，
    # MainActivity.onCreate 会 startService(RelayService) 把面板带起来。
    $AM start -n "$PKG/.MainActivity" >> "$LOG" 2>&1
    sleep 1
    $AM broadcast --include-stopped-packages -a "$ACTION" -n "$RECV" >> "$LOG" 2>&1
    ;;
  *)
    if [ -e "$NOREBOOT" ]; then
      echo "  action: reboot 被 smsrelay-no-reboot 阻止" >> "$LOG"
    else
      echo "  action: reboot（连续 4 轮探活失败）" >> "$LOG"
      $REBOOT >> "$LOG" 2>&1
    fi
    ;;
esac
