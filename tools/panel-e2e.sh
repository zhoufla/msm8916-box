#!/bin/sh
# 面板全功能真机 e2e：所有接口 + 常用命令预设逐条执行 + WiFi 带密码连接实操。
# 配套 smoke-test.sh（30 项快速回归）；这个脚本做的是「发版前的全量体检」。
#
# 用法：
#   sh tools/panel-e2e.sh [base] [auth] [ssid] [pass]
#     base 默认 192.168.42.129:8080；auth 默认 admin:admin
#     ssid/pass 都给才做「断开→带密码重连」实操（结束后设备保持连回该 WiFi）；
#     不给则跳过连接实操（只验证 WiFi 状态读取）。
#
# 设计要点：
#  - 命令预设不复制一份，直接解析 assets/web/index.html 里的 CMD_PRESETS——
#    测试跑的就是面板上会执行的那条命令，改预设忘验证这里立刻红。
#  - 断言新增字段必须在（cpu/temps/mem）、电池字段必须不在（USB 直供无电池）。
#  - 全部只读/可恢复动作；唯一有副作用的 messages/test 会注入一条测试消息，
#    sta 实操结束时会连回原 WiFi。APN 增删、备份等破坏性较大的仍在 smoke-test。
set -u

BASE=${1:-192.168.42.129:8080}
AUTH=${2:-admin:admin}
E2E_SSID=${3:-}
E2E_PASS=${4:-}
URL="http://$BASE"
ROOT=$(cd "$(dirname "$0")/.." && pwd)

PASS=0
FAIL=0
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ok()  { PASS=$((PASS + 1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad() { FAIL=$((FAIL + 1)); printf "  \033[31m✗\033[0m %s\n" "$1"; }

# get <path> [outfile]：带认证 GET，写文件，非 200 报错
get() {
    curl -s -m 30 -u "$AUTH" "$URL$1" -o "$WORK/$2"
}
# post <path> <json> [outfile]
post() {
    curl -s -m 60 -u "$AUTH" -H 'Content-Type: application/json' -d "$2" "$URL$1" -o "$WORK/$3"
}
# jval <file> <python 表达式>：输出 json 字段求值结果（失败输出空）
jval() {
    python3 -c "
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    v=eval(sys.argv[2])
    print('' if v is None else v)
except Exception:
    print('')
" "$1" "$2"
}
# chk <名称> <file> <表达式> <期望值>
chk() {
    got=$(jval "$2" "$3")
    if [ "$got" = "$4" ]; then ok "$1"; else bad "$1（期望 $4，实际 ${got:-空}）"; fi
}
chktrue() {
    chk "$1" "$2" "$3" "True"
}

echo "== 只读接口（字段断言）"
get /api/status st.json
chk "/api/status ok"            "$WORK/st.json" "d.get('ok')" "True"
chktrue "/api/status 有 queue.pending" "$WORK/st.json" "'pending' in d.get('queue',{})"
chktrue "/api/status 无 battery（USB 直供）" "$WORK/st.json" "'battery' not in d"
chktrue "/api/status 有 channels" "$WORK/st.json" "isinstance(d.get('channels'),list)"

get /api/sys sy.json
chk "/api/sys ok"               "$WORK/sy.json" "d.get('ok')" "True"
chktrue "/api/sys.cpu.load1 存在"  "$WORK/sy.json" "bool(d.get('cpu',{}).get('load1'))"
chktrue "/api/sys.cpu.coresTotal>=1" "$WORK/sy.json" "(d.get('cpu',{}).get('coresTotal') or 0)>=1"
chktrue "/api/sys.mem.totalMb>0"   "$WORK/sy.json" "(d.get('mem',{}).get('totalMb') or 0)>0"
chktrue "/api/sys.temps 非空且 cpu>0" "$WORK/sy.json" "(d.get('temps',{}).get('cpu') or 0)>0"
chktrue "/api/sys.temps 无 bms/battery" "$WORK/sy.json" "not any(k in d.get('temps',{}) for k in ('bms','battery'))"
chktrue "/api/sys.storage 非空"    "$WORK/sy.json" "len(d.get('storage') or [])>=1"
chktrue "/api/sys.nets 非空"       "$WORK/sy.json" "len(d.get('nets') or [])>=1"
chktrue "/api/sys 无 battery"      "$WORK/sy.json" "'battery' not in d"
chktrue "/api/sys.uptime 存在"     "$WORK/sy.json" "bool(d.get('uptime'))"

for ep in messages outbox logs; do
    get "/api/$ep?limit=5" "$ep.json"
    chktrue "/api/$ep ok" "$WORK/$ep.json" "d.get('ok')"
done

get /api/config cf.json
chktrue "/api/config ok"           "$WORK/cf.json" "d.get('ok')"
chktrue "/api/config.json 可解析且有 web" "$WORK/cf.json" "'web' in json.loads(d.get('json','{}'))"

get /api/wifi wf.json
chktrue "/api/wifi ok"             "$WORK/wf.json" "d.get('ok')"
chktrue "/api/wifi.state.mode 合法" "$WORK/wf.json" "d.get('state',{}).get('mode') in ('ap','sta','off')"
chktrue "/api/wifi.state.hardware"  "$WORK/wf.json" "d.get('state',{}).get('hardware')"

echo "== 移动网络"
get /api/radio rd.json
chktrue "/api/radio ok"            "$WORK/rd.json" "d.get('ok')"
get /api/radio/apns ap.json
chktrue "/api/radio/apns ok 且 count>=1" "$WORK/ap.json" "(d.get('ok')) and (d.get('count') or 0)>=1 and len(d.get('list') or [])>=1"
get /api/radio/traffic tr.json
chktrue "/api/radio/traffic ok"    "$WORK/tr.json" "d.get('ok')"
get /api/radio/simlock sl.json
chktrue "/api/radio/simlock ok"    "$WORK/sl.json" "d.get('ok')"
chktrue "/api/radio 双槽状态数组非空" "$WORK/rd.json" "len(d.get('simSlots') or [])>=1"
chktrue "/api/radio 槽1 就绪"       "$WORK/rd.json" "(d.get('simSlots') or [''])[0]=='READY'"
chktrue "/api/radio ICCID 已读出"   "$WORK/rd.json" "len(d.get('iccid') or '')>=10"
post /api/netdiag '{"kind":"ping","target":"127.0.0.1","count":2}' nd.json
chktrue "/api/netdiag ping 回环 ok" "$WORK/nd.json" "d.get('ok')"

echo "== 升级"
get /api/update up.json
chktrue "/api/update ok 且有 config" "$WORK/up.json" "(d.get('ok')) and isinstance(d.get('config'),dict)"

echo "== 维护通道"
get /api/maint/helper mh.json
chktrue "/api/maint/helper alive"  "$WORK/mh.json" "d.get('alive')"
chktrue "/api/maint/helper uid=root" "$WORK/mh.json" "'root' in (d.get('uid') or '')"
get /api/health hl.json
chktrue "/api/health ok"           "$WORK/hl.json" "d.get('ok')"
get /api/maint/checkup ck.json
chktrue "/api/maint/checkup healthy" "$WORK/ck.json" "d.get('healthy')"
get /api/maint/files mf.json
chktrue "/api/maint/files ok"      "$WORK/mf.json" "d.get('ok')"
get /api/maint/cron mc.json
chktrue "/api/maint/cron ok"       "$WORK/mc.json" "d.get('ok')"

echo "== 写接口（非破坏）"
post /api/messages/test '{"peer":"10086","body":"panel-e2e"}' mt.json
chktrue "/api/messages/test ok"    "$WORK/mt.json" "d.get('ok')"
post /api/inbox/import '{}' ii.json
chktrue "/api/inbox/import ok"     "$WORK/ii.json" "d.get('ok')"

echo "== 常用命令预设（与面板同源，逐条真机执行）"
python3 - "$ROOT/assets/web/index.html" "$WORK" <<'PYEOF'
import re, sys, json, os
html = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'var CMD_PRESETS = \[(.*?)\n\];', html, re.S)
if not m:
    print("CMD_PRESETS 没找到"); sys.exit(2)
items = re.findall(r"\['([^']+)',\s*'([^']+)'\]", m.group(1))
out = os.path.join(sys.argv[2], 'presets.json')
json.dump(items, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
print(str(len(items)))
PYEOF
if [ ! -s "$WORK/presets.json" ]; then bad "解析 CMD_PRESETS 失败"; fi
NP=$(python3 -c "import json;print(len(json.load(open('$WORK/presets.json'))))" 2>/dev/null)
if [ "${NP:-0}" -lt 12 ]; then bad "预设数量异常（$NP < 12）"; fi
i=0
while [ "$i" -lt "${NP:-0}" ]; do
    cmd=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[int(sys.argv[2])][1])" "$WORK/presets.json" "$i")
    name=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[int(sys.argv[2])][0])" "$WORK/presets.json" "$i")
    body=$(python3 -c "import json,sys;print(json.dumps({'cmd':sys.argv[1]}))" "$cmd")
    post /api/maint/cmd "$body" cmdout.json
    chk "命令[$name] exit=0" "$WORK/cmdout.json" "d.get('exit')" "0"
    i=$((i + 1))
done

echo "== WiFi 实操（station 连接）"
if [ -n "$E2E_SSID" ] && [ -n "$E2E_PASS" ]; then
    post /api/wifi '{"action":"sta_disconnect"}' wdis.json
    chktrue "sta_disconnect ok" "$WORK/wdis.json" "d.get('ok')"
    sleep 4
    body=$(python3 -c "import json,sys;print(json.dumps({'action':'sta_connect','ssid':sys.argv[1],'pass':sys.argv[2]}))" "$E2E_SSID" "$E2E_PASS")
    post /api/wifi "$body" wcon.json
    chktrue "sta_connect（带密码）ok" "$WORK/wcon.json" "d.get('ok')"
    chktrue "sta_connect 拿到 IP" "$WORK/wcon.json" "'IP ' in (d.get('message') or '')"
    get /api/wifi wf2.json
    chktrue "实操后 station 已连接" "$WORK/wf2.json" "d.get('state',{}).get('station',{}).get('connected')"
else
    echo "  （未给 ssid/pass，跳过带密码连接实操）"
fi

echo
if [ "$FAIL" -gt 0 ]; then
    printf "\033[31m失败 %d 项 / 通过 %d 项\033[0m\n" "$FAIL" "$PASS"
    exit 1
fi
printf "\033[32m全部通过：%d 项\033[0m\n" "$PASS"
