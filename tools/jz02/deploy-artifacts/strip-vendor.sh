#!/system/bin/sh
BB=/system/xbin/busybox
echo "== 1 remove vendor app =="
$BB rm -f /system/priv-app/ufilauncherzx.apk
$BB rm -rf /data/data/com.youdo.server
echo "== 2 remove empty carrier-spec shells =="
for d in CTA ChinaMobile ChinaTelecom ChinaUnicom overlay; do $BB rm -rf /system/vendor/$d; done
for d in SelfHost fota switch_spec; do $BB rm -rf /data/$d; done
echo "== 3 stub switch_spec.sh (wipe-data guard) =="
if [ ! -f /system/vendor/speccfg/switch_spec.sh.pre-slim ]; then
  $BB cp /system/vendor/speccfg/switch_spec.sh /system/vendor/speccfg/switch_spec.sh.pre-slim
fi
$BB cp /data/local/tmp/switch_spec.stub /system/vendor/speccfg/switch_spec.sh
$BB chmod 755 /system/vendor/speccfg/switch_spec.sh
echo "== 4 clean dalvik cache entries =="
$BB rm -f /data/dalvik-cache/*ufilauncherzx* /data/dalvik-cache/*youdo*
echo "== 5 optional HOME launcher =="
if [ -f /data/local/tmp/Launcher3_sp970.apk ]; then
  $BB cp /data/local/tmp/Launcher3_sp970.apk /system/priv-app/Launcher3.apk
  $BB cp /data/local/tmp/Launcher2LayoutRes_sp970.apk /system/app/Launcher2LayoutRes.apk
  $BB chmod 644 /system/priv-app/Launcher3.apk /system/app/Launcher2LayoutRes.apk
  echo LAUNCHER_INSTALLED
else
  echo LAUNCHER_SKIPPED
fi
echo "== result =="
if [ -e /system/priv-app/ufilauncherzx.apk ]; then echo VENDOR_APK_STILL_THERE; else echo VENDOR_APK_GONE; fi
$BB head -4 /system/vendor/speccfg/switch_spec.sh
echo DONE
