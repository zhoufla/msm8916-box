#!/bin/sh
# 装完自检：把面板 API 逐项打一遍，全绿才算部署成功。
# 用法：sh tools/smoke-test.sh [host:port] [user:pass] [测试号码]
#   默认 192.168.42.129:8080 / admin:admin；给了号码才会真的发一条短信
set -u

BASE=${RELAY_BASE:-${1:-192.168.42.129:8080}}
AUTH=${2:-admin:admin}
URL="http://$BASE"
PASS=0
FAIL=0
PY=""
command -v python3 >/dev/null 2>&1 && PY=python3

ok()  { PASS=$((PASS + 1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad() { FAIL=$((FAIL + 1)); printf "  \033[31m✗\033[0m %s → %s\n" "$1" "$2"; }

get()  { curl -s -m 8 -u "$AUTH" "$URL$1"; }
post() { curl -s -m 20 -u "$AUTH" -H 'Content-Type: application/json' -d "$2" "$URL$1"; }

# 取 JSON 字段：有 python3 就精确解析，没有就粗略 sed（只用于展示，不影响判定）
show() {
    [ -n "$PY" ] || return 0
    printf '%s' "$2" | $PY -c "import sys,json
d=json.load(sys.stdin)
for path in '$1'.split(','):
    cur=d
    for k in path.split('.'):
        cur=cur.get(k,{}) if isinstance(cur,dict) else {}
    print('     ' + path + ' = ' + str(cur))" 2>/dev/null
}

check_ok() {
    if printf '%s' "$2" | grep -q '"ok":true'; then
        ok "$1"
        return 0
    fi
    bad "$1" "$(printf '%s' "$2" | cut -c1-120)"
    return 1
}

echo "== 面板自检 $URL（$AUTH）"

code=$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$URL/api/status")
[ "$code" = "401" ] && ok "鉴权生效（无凭据返回 401）" || bad "鉴权生效" "无凭据返回 $code"

echo "-- 页面"
page=$(curl -s -m 8 -u "$AUTH" "$URL/")
printf '%s' "$page" | grep -q 'MSM8916 Box' && ok "首页 / 返回面板页面" \
    || bad "首页 /" "没拿到页面（$(printf '%s' "$page" | wc -c) 字节）"

echo "-- 只读接口"
st=$(get /api/status)
if check_ok "/api/status" "$st"; then
    show "device,model,android,activeChannels,queue.pending,queue.sent,queue.failed,panel.port" "$st"
fi
sy=$(get /api/sys)
if check_ok "/api/sys" "$sy"; then
    show "baseband,uptime,operator,netType,cpu.load1,mem.totalMb,temps.cpu,signal.dbm,signal.text" "$sy"
fi
check_ok "/api/messages" "$(get '/api/messages?limit=5')"
check_ok "/api/outbox" "$(get '/api/outbox?limit=5')"
check_ok "/api/sysinbox" "$(get '/api/sysinbox?page=1&page_size=5')"
check_ok "/api/config" "$(get /api/config)"
check_ok "/api/logs" "$(get '/api/logs?limit=10')"
check_ok "/api/wifi" "$(get /api/wifi)"

echo "-- 写接口"
check_ok "注入测试消息" "$(post /api/messages/test '{"peer":"10086","body":"smoke-test"}')"
check_ok "补收系统收件箱" "$(post /api/inbox/import '{}')"

if [ $# -ge 3 ]; then
    echo "-- 实发短信（收件人 $3）"
    check_ok "发送短信" "$(post /api/send "{\"to\":\"$3\",\"body\":\"smoke-test\"}")"
else
    echo "-- 跳过实发短信（要测就带号码：sh tools/smoke-test.sh $BASE $AUTH 10086）"
fi

echo "-- 维护通道（relay-helper）"
hp=$(get /api/maint/helper)
if printf '%s' "$hp" | grep -q 'uid=0(root)'; then
    ok "/api/maint/helper 返回 uid=0"
    show "alive,url,token" "$hp"
else
    bad "/api/maint/helper" "$(printf '%s' "$hp" | cut -c1-140)"
fi
check_ok "/api/health" "$(get /api/health)"
ck=$(get /api/maint/checkup)
if check_ok "/api/maint/checkup" "$ck"; then
    show "healthy,bootId,uptimeSec,sim,memFreeKb" "$ck"
fi
ci=$(post /api/maint/cmd '{"cmd":"id"}')
if printf '%s' "$ci" | grep -q 'uid=0(root)'; then
    ok "命令框执行 id 返回 uid=0"
else
    bad "命令框执行 id" "$(printf '%s' "$ci" | cut -c1-140)"
fi
check_ok "/api/maint/logs?kind=main&lines=20" "$(get '/api/maint/logs?kind=main&lines=20')"
check_ok "/api/maint/files" "$(get /api/maint/files)"
check_ok "/api/maint/cron" "$(get /api/maint/cron)"
check_ok "打包日志" "$(post /api/maint/logs/bundle '{}')"
check_ok "生成备份" "$(post /api/maint/backup '{}')"
check_ok "LED 灭（无害动作）" "$(post /api/maint/led '{"action":"off"}')"
# 重启 / 关机 / 恢复出厂类动作不进自检，需人工在面板二次确认

echo "-- 移动网络 / B2"
check_ok "/api/radio" "$(get /api/radio)"
ap=$(get /api/radio/apns)
if check_ok "/api/radio/apns" "$ap"; then
    show "numeric,count,preferredApnId" "$ap"
fi
check_ok "/api/radio/traffic" "$(get /api/radio/traffic)"
check_ok "/api/radio/simlock（读取）" "$(get /api/radio/simlock)"
check_ok "/api/netdiag attach" "$(post /api/netdiag '{"kind":"attach"}')"
check_ok "/api/netdiag ping 127.0.0.1" "$(post /api/netdiag '{"kind":"ping","target":"127.0.0.1","count":1}')"
check_ok "/api/wifi/adv（只读）" "$(get /api/wifi/adv)"

echo "-- APN 增删演练（ZZ_SMOKE，加完即删，不影响首选）"
if [ -n "$PY" ]; then
    ins=$(post /api/radio/apn/save '{"name":"ZZ_SMOKE","apn":"zzsmoke","type":"default"}')
    zid=$(printf '%s' "$ins" | $PY -c "import sys,json
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')" 2>/dev/null)
    if [ -n "$zid" ]; then
        ok "新增 APN（id=$zid）"
        del=$(post /api/radio/apn/delete "{\"id\":$zid}")
        check_ok "删除 APN #$zid" "$del"
    else
        bad "新增 APN" "$(printf '%s' "$ins" | cut -c1-140)"
    fi
else
    echo "  （没有 python3，跳过 APN 演练）"
fi
# 写状态类动作（制式 / 数据开关 / 漫游 / 首选 APN 切换）会断网或需重启，不进自检

echo
if [ "$FAIL" -eq 0 ]; then
    printf "\033[32m全部通过：%d 项\033[0m\n" "$PASS"
    exit 0
fi
printf "\033[31m失败 %d 项 / 通过 %d 项\033[0m\n" "$FAIL" "$PASS"
exit 1
