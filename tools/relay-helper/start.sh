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
