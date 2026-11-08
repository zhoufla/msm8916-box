#!/system/bin/sh
# relay-helper · 禁用开机锁屏（SP970 的 720p Keyguard 白占 3 块 framebuffer ≈ 11 MB）
#
# 实测背景（2026-11-08，两台 msm8916 / Android 4.4.4）：
#   * Keyguard 是否显示，看的是 /data/system/locksettings.db 里的
#     lockscreen.disabled，不是 SettingsProvider。单跑
#     `settings put secure lockscreen.disabled 1` 重启后仍停在 Keyguard
#     （mShowingLockscreen=true / mCurrentFocus=Keyguard）——两处存储，别只写一处。
#   * SP970：DB 里该行存在且为 0，/system/bin/sqlite3 可用，需改成 1。
#   * JZ02：build.prop 有 ro.lockscreen.disable.default=true，锁屏本就不显示；
#     它没有 sqlite3、DB 里也没有这一行 —— 不要去动它的 DB。
#   * 改完必须重启：Keyguard 只在开机时读这个值。
#   * 修复后实测 Lost RAM 32864 → 18072 kB、Free RAM +8.6 MB。
#
# 用法（root）：
#   sh disable-lockscreen.sh                 只读体检（同 --check）
#   sh disable-lockscreen.sh --check        0=已禁用 / 1=需修复 / 2=无法判定
#   sh disable-lockscreen.sh --apply        幂等修复；已正确时零写入；需重启生效
#   sh disable-lockscreen.sh --restore      回滚（值改回 0）；需重启生效
#   --quiet                                仅在实际发生写入时输出（给 crontab 用）
#
# 回滚说明：本脚本只改一个布尔值，不做整库文件备份以免在 /sdcard 堆积；
# 需要恢复锁屏用 --restore，恢复出厂/刷机后重跑 --apply 即可。
# 注意：JZ02 由 build.prop 属性禁用，--restore 改 DB 不会让它的锁屏回来。

BB=/system/xbin/busybox
DB=/data/system/locksettings.db
KEY=lockscreen.disabled
MODE=check
QUIET=0

for a in "$@"; do
    case "$a" in
        --check)   MODE=check ;;
        --apply)   MODE=apply ;;
        --restore) MODE=restore ;;
        --quiet)   QUIET=1 ;;
        -h|--help)
            $BB head -25 "$0" | $BB grep '^#' | $BB grep -v '^#!/' | $BB sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown option: $a (try --help)" >&2; exit 2 ;;
    esac
done

say() { [ "$QUIET" = 1 ] || echo "$@"; }
err() { echo "$@" >&2; }

PROP=$(getprop ro.lockscreen.disable.default 2>/dev/null)
SETTING=$(settings get secure "$KEY" 2>/dev/null)
PROP_OK=0
case "$PROP" in true|TRUE|True|1) PROP_OK=1 ;; esac

SQLITE=""
for c in /system/bin/sqlite3 /system/xbin/sqlite3 /system/bin/busybox; do
    if [ "$c" = /system/bin/busybox ]; then
        [ -x "$BB" ] && $BB --list 2>/dev/null | $BB grep -qx sqlite3 && SQLITE="$BB sqlite3"
    elif [ -x "$c" ]; then
        SQLITE=$c
        break
    fi
done

DB_ACCESS=0
DBVAL=""
if [ -n "$SQLITE" ] && [ -r "$DB" ]; then
    DBVAL=$($SQLITE "$DB" "select value from locksettings where name='$KEY' limit 1;" 2>/dev/null | $BB head -1)
    DB_ACCESS=1
fi

if [ "$PROP_OK" = 1 ] || [ "$DBVAL" = "1" ]; then DISABLED=1; else DISABLED=0; fi

if [ "$MODE" = check ] || [ "$QUIET" = 0 ]; then
    say "device=$(getprop ro.product.device 2>/dev/null) prop=${PROP:-<unset>} settings=${SETTING:-<unset>} db=${DBVAL:-<n/a>} sqlite3=${SQLITE:-none}"
fi

case "$MODE" in
check)
    if [ "$DISABLED" = 1 ]; then
        say "OK: 锁屏已禁用，无需改动"
        exit 0
    fi
    if [ "$PROP_OK" = 0 ] && [ "$DB_ACCESS" = 0 ]; then
        say "UNKNOWN: 无 build.prop 属性、且无 sqlite3 或 DB 不可读"
        exit 2
    fi
    say "NEED_FIX: 开机仍会停在 Keyguard，请执行 --apply 并重启"
    exit 1
    ;;

apply)
    if [ "$DISABLED" = 1 ]; then
        # 保持两处存储一致（幂等）；已正确时不写任何东西
        if [ "$SETTING" != "1" ]; then settings put secure "$KEY" 1 >/dev/null 2>&1; fi
        say "OK: 已是禁用状态，未改动"
        exit 0
    fi
    if [ "$DB_ACCESS" = 0 ]; then
        err "ERROR: 无 $DB 访问权限或缺少 sqlite3，且本机没有 ro.lockscreen.disable.default=true；请改用 build.prop 属性方案"
        exit 2
    fi

    # 先 UPDATE；若该行不存在（changes()=0）再尝试 INSERT。
    # 写入前后都校验，避免静默失败。
    CH=$($SQLITE "$DB" "update locksettings set value='1' where name='$KEY'; select changes();" 2>/dev/null | $BB tail -1)
    if [ "$CH" = "0" ] || [ -z "$CH" ]; then
        $SQLITE "$DB" "insert into locksettings(name,value) values('$KEY','1');" >/dev/null 2>&1
    fi
    NEW=$($SQLITE "$DB" "select value from locksettings where name='$KEY' limit 1;" 2>/dev/null | $BB head -1)
    if [ "$NEW" != "1" ]; then
        err "ERROR: 写入后校验失败（db=$NEW）。locksettings 表结构可能含额外非空列，请人工检查：$SQLITE $DB '.schema locksettings'"
        exit 2
    fi
    settings put secure "$KEY" 1 >/dev/null 2>&1
    echo "APPLIED: $KEY -> 1（DB + SettingsProvider 双写）；请重启设备生效（Keyguard 开机时读取）"
    exit 0
    ;;

restore)
    if [ "$DB_ACCESS" = 1 ] && [ "$DBVAL" != "0" ] && [ -n "$DBVAL" ]; then
        $SQLITE "$DB" "update locksettings set value='0' where name='$KEY';" >/dev/null 2>&1
    fi
    settings put secure "$KEY" 0 >/dev/null 2>&1
    if [ "$PROP_OK" = 1 ]; then
        echo "RESTORED: DB 已改回 0，但本机 ro.lockscreen.disable.default=true 仍会禁用锁屏；要恢复需去掉该属性"
    else
        echo "RESTORED: $KEY -> 0；请重启设备，锁屏恢复"
    fi
    exit 0
    ;;
esac
