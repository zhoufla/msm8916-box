#!/system/bin/sh
# JZ02 部署后自检（设备侧执行，需 root；用 /system/xbin/busybox 提供的 applet）
# 用法: adb -L tcp:5039 -s 0 shell sh /data/local/tmp/verify-jz02.sh
BB=/system/xbin/busybox
ROOT=/system/etc/relay-helper
echo "=== JZ02 verify-jz02.sh ==="
echo "model=$(getprop ro.product.model) build=$(getprop ro.build.display.id) sn=$(getprop ro.serialno)"
echo "uid=$(id)"
echo "-- root --"
echo "ro.secure=$(getprop ro.secure) ro.debuggable=$(getprop ro.debuggable) ro.adb.secure=$(getprop ro.adb.secure)"
echo "net_adb_port=$(getprop service.adb.tcp.port)"
echo "-- adb key --"
cat /data/misc/adb/adb_keys 2>/dev/null | $BB cut -c1-60
echo "-- busybox --"
if [ -x "$BB" ]; then echo "busybox OK applets=$($BB --list | $BB wc -l)"; else echo "busybox MISSING"; fi
echo "-- relay-helper --"
if [ -f "$ROOT/token" ]; then echo "helper files OK token=$($BB cat $ROOT/token)"; else echo "helper MISSING"; fi
echo "boot_injected=$($BB grep -c 'relay-helper autostart BEGIN' /system/etc/init.qcom.post_boot.sh)"
echo "watchdog=$($BB cat /data/local/tmp/relay-helper/start.pid 2>/dev/null)"
echo "-- 8899 ping --"
$BB wget -q -T 4 -O - http://127.0.0.1:8899/ping.txt || echo "ping FAILED"
echo "-- listening ports --"
netstat -ltn 2>/dev/null | $BB grep -E ':8899|:8080|:80 |:5555' || echo "(netstat no match)"
echo "-- 强化档落地（应全部 GONE） --"
for f in /system/etc/security/otacerts.zip /system/vendor/speccfg/ota.zip \
         /system/app/CarrierConfigure.apk /system/bin/ptt_socket_app \
         /system/app/LatinIME.apk /system/etc/init.qcom.rootagent.sh /system/xbin/qlogd; do
    if [ -e "$f" ]; then echo "  EXISTS(BAD) $f"; else echo "  gone      $f"; fi
done
echo "-- 保留红线（应 EXISTS） --"
for f in /system/priv-app/ufilauncherzx.apk /system/etc/apns-conf.xml \
         /system/app/TelephonyProvider.apk /system/priv-app/TeleService.apk \
         /system/vendor/speccfg/switch_spec.sh /system/etc/adbkey.pub; do
    if [ -e "$f" ]; then echo "  exists    $f"; else echo "  MISSING(BAD) $f"; fi
done
echo "-- SIM / radio（上一轮遗留：SIM READY 但 OUT_OF_SERVICE） --"
echo "sim=$(getprop gsm.sim.state) operator=$(getprop gsm.sim.operator.alpha) numeric=$(getprop gsm.operator.numeric)"
dumpsys telephony.registry 2>/dev/null | $BB grep -iE 'mServiceState|mDataConnectionState' | $BB head -4
echo "=== END ==="
