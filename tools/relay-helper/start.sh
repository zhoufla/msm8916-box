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

mkdir -p "$TMP" "$ROOT/cron.d" 2>/dev/null

# JZ02 专有：关断断电 fb 面板（blank=4）
# JZ02 的 DSI panel 无响应（无实体屏），framework 却持续按 video mode 合成帧，
# kernel 里 mdss_mdp_video_pollwait 每秒 vsync 超时 → mdss_fb0 恒 D 态
# 烧 ~10% 单核内核时间，loadavg 恒 5+，SoC 温度比 SP970 高 4~5℃（实测对拍）。
# blank=4 后风暴立刻停止（mdss_fb0 stime 30s 零增长，framework 不受影响，
# boot_completed/8080/8899/modem 全正常）。SP970 无此问题（mdss_fb0 ~0.8%），
# 故以 ro.product.device=UFI 门控。延迟 90s 确保框架完成界面初始化。
# 回滚：kill 掉 sleep 子进程或注释本块即可恢复原状（面板风暴原样回来）。
if [ "$(getprop ro.product.device)" = "UFI" ]; then
    ($BB sleep 90; echo 4 > /sys/class/graphics/fb0/blank) &
fi

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
while :; do
    sleep 30
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
