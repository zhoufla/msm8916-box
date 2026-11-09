#!/system/bin/sh
# relay-helper · 开机自启 + 看门狗 + 定时任务
#
# 由 /system/etc/init.qcom.post_boot.sh 在「两个长循环之前」以 setsid & 拉起。
# init 本身是 root，所以 httpd / crond 都是 uid=0。
# 这个脚本自己不退出：后半段是 30s 一轮的存活检查。

BB=/system/xbin/busybox
ROOT=/system/etc/relay-helper
TMP=/data/local/tmp/relay-helper
PORT=8899
URL=http://127.0.0.1:$PORT/ping.txt
BLANK=/sys/class/graphics/fb0/blank
SHOW_BLANK=/sys/class/graphics/fb0/show_blank_event
USB_STATE=/sys/class/android_usb/android0/state

mkdir -p "$TMP" "$ROOT/cron.d" 2>/dev/null

IS_UFI=0
[ "$(getprop ro.product.device)" = "UFI" ] && IS_UFI=1

# JZ02 专有：关断断电 fb 面板（blank=4）—— 这里是【兜底】，不是主修复。
# JZ02 的 DSI panel 无响应（无实体屏），framework 却持续按 video mode 合成帧，
# kernel 里 mdss_mdp_video_pollwait 每秒 vsync 超时 → mdss_fb0 恒 D 态
# 烧 ~10% 单核内核时间，loadavg 恒 5+，SoC 温度比 SP970 高 4~5℃（实测对拍）。
# blank=4 后风暴立刻停止（mdss_fb0 stime 30s 零增长，framework 不受影响，
# boot_completed/8080/8899/modem 全正常）。SP970 有实屏，绝不写 blank，
# 故以 ro.product.device=UFI 门控。
#
# v13 变更：主修复已前移到 /system/etc/install-recovery.sh（class_start main，
# 约 43s 之前就写入），因为本脚本由 qcom-post-boot 在 sys.boot_completed=1
# 才拉起，再 sleep 90 就是 ~142s，43~142s 的开机风暴一直没被覆盖。
# 这里保留 90s 的一次补写，下面 30s 看门狗每轮也会按 show_blank_event 补写。
# 回滚：删除 install-recovery.sh 里的 early blank 块 + 注释本脚本的两处补写。
if [ "$IS_UFI" = "1" ]; then
    ($BB sleep 90; echo 4 > "$BLANK" 2>/dev/null) &
fi

# --- SP970 / 非 UFI：USB RNDIS 网络共享的链路状态门控 -----------------------
# USBtether.apk（com.example.usbtethering，sharedUserId=android.uid.system）
# 【就是】这台设备的 RNDIS 网络共享实现，不要禁用、不要删除、不要改 APK。
# 解包结论（classes.dex 字节码）：
#   UsbDetact.is_port_locked() 只做 getprop persist.sys.usb.config，等于
#     "charging,none" / "charging,none,adb" 时返回 1；
#   UsbDetact.onReceive() 在 0088 是 if-eqz -> return-void，也就是【返回 1
#     才 startService(com.example.USBTether)】，返回 0 反而直接返回。
#     所以"充电口配置"恰恰是打开轮询的条件，不是跳过条件。
#   USBTether.onStart() 动态注册两个 IntentFilter（"USB tethering" 和
#     ACTION_BATTERY_CHANGED），且 onDestroy() 不 unregisterReceiver。
#   USBTether$1.onReceive() 每收到一次电量广播就 sleep(500ms)，反射
#     ConnectivityManager 的所有方法找 setUsbTethering，然后 invoke(true)。
#   全过程没有读过 /sys/class/android_usb/android0/state，也没看 rndis0。
# 当前插的是供电口：android_usb_state=DISCONNECTED、rndis0 DOWN，
# setUsbTethering(true) 永远不可能成功，于是每 ~20s 重复一次，伴生
# Tethering churn 和 QcrilMsgTunnelSocket IOException。
#
# 这里从 APK 外部补上它缺的那个判断（不动 APK、不动签名、不停包）：
#   state=CONFIGURED（真 USB 主机/数据口） -> 保持/恢复厂商 RNDIS 链路；
#   其它（供电口/无主机）                 -> am stopservice .USBTether，
#       实测服务销毁时进程一并退出，泄漏的动态 BATTERY_CHANGED 接收器消失；
#       实测 am startservice 能在 30s 内完整重建服务与反射调用链。
# 之后插入真实数据线，30s 内自动恢复 RNDIS，行为与厂版完全一致。
usb_tether_gate() {
    [ "$IS_UFI" = "1" ] && return 0
    s=$(cat "$USB_STATE" 2>/dev/null)
    running=0
    dumpsys activity services com.example.usbtethering 2>/dev/null | $BB grep -q 'ServiceRecord{' && running=1
    if [ "$s" = "CONFIGURED" ]; then
        if [ "$running" = "0" ]; then
            am startservice -a com.example.USBTether >/dev/null 2>&1
            echo "uptime=$(cat /proc/uptime) state=$s action=start" >> "$TMP/usb-tether-gate.log"
        fi
    else
        if [ "$running" = "1" ]; then
            am stopservice -a com.example.USBTether >/dev/null 2>&1
            echo "uptime=$(cat /proc/uptime) state=$s action=stop" >> "$TMP/usb-tether-gate.log"
        fi
    fi
    return 0
}

# 只允许一个看门狗实例；重复部署时由 deploy-helper.py 按 pid 收掉旧的。
# 不用 flock：这台 4.4 的 /system/bin/sh 对 fd 9 重定向支持有问题（实测 Bad file descriptor）。
#
# pidfile 判活必须核对 /proc/<pid>/cmdline：/data 分区跨开机不清理，
# 上一次开机的 pid 到新开机里几乎一定被别的进程复用（实测 1374 变成带
# androidboot 参数的内核进程），只看 /proc/<pid> 存在就会永远退出、helper 不自启。
cmd_is() {
    # cmd_is <pid> <片段>：cmdline 里包含片段才算活着
    [ -n "$1" ] && [ -d "/proc/$1" ] || return 1
    x=$(cat "/proc/$1/cmdline" 2>/dev/null | $BB tr '\000' ' ')
    case "$x" in *"$2"*) return 0 ;; esac
    return 1
}

if [ -f "$TMP/start.pid" ]; then
    old=$(cat "$TMP/start.pid" 2>/dev/null)
    if cmd_is "$old" "start.sh"; then exit 0; fi
fi
echo $$ > "$TMP/start.pid"

start_httpd() {
    if [ -f "$TMP/httpd.pid" ]; then
        p=$(cat "$TMP/httpd.pid" 2>/dev/null)
        if cmd_is "$p" "httpd"; then return 0; fi
    fi
    $BB httpd -p 127.0.0.1:$PORT -h "$ROOT" -f >/dev/null 2>&1 &
    echo $! > "$TMP/httpd.pid"
}

start_crond() {
    if [ ! -f "$ROOT/crontab" ]; then return 0; fi
    cp "$ROOT/crontab" "$ROOT/cron.d/root" 2>/dev/null
    chmod 600 "$ROOT/cron.d/root" 2>/dev/null
    p=$(cat "$TMP/crond.pid" 2>/dev/null)
    if cmd_is "$p" "crond"; then kill $p 2>/dev/null; fi
    # -f 前台 + 本脚本后台化，才能拿到可管理的真实 pid（crond -b 会自己 daemon 化）
    $BB crond -c "$ROOT/cron.d" -f -l 8 -L "$TMP/crond.log" >/dev/null 2>&1 &
    echo $! > "$TMP/crond.pid"
}

start_httpd
start_crond

last_cron=$(cat "$ROOT/crontab" 2>/dev/null)

# 首轮立即执行，避免 BOOT_COMPLETED 之后到第一次 30s 之间出现空窗。
usb_tether_gate

while :; do
    sleep 30
    # JZ02：只要面板又被点亮就补一次 blank=4（已是 0 则不动，避免无谓写入）。
    if [ "$IS_UFI" = "1" ] && [ -e "$BLANK" ]; then
        v=$(cat "$SHOW_BLANK" 2>/dev/null)
        case "$v" in
            *"= 0"*) ;;
            *) echo 4 > "$BLANK" 2>/dev/null ;;
        esac
    fi
    # 非 UFI：按真实 USB 链路状态维护 RNDIS 轮询器。
    usb_tether_gate
    if ! $BB wget -q -T 3 -O /dev/null "$URL" 2>/dev/null; then
        p=$(cat "$TMP/httpd.pid" 2>/dev/null)
        if cmd_is "$p" "httpd"; then kill $p 2>/dev/null; fi
        rm -f "$TMP/httpd.pid"
        start_httpd
    fi
    now_cron=$(cat "$ROOT/crontab" 2>/dev/null)
    if [ "$now_cron" != "$last_cron" ]; then
        start_crond
        last_cron=$now_cron
    fi
done
