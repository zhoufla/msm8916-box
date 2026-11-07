#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JZ02 v6: remove the last vendor-customised app and its residue (real device, needs root).

Usage:
  python strip-vendor.py                 # strip, keep a full rollback path
  python strip-vendor.py --reboot       # strip + reboot (PMS rescans /system)
  python strip-vendor.py --with-launcher --reboot
                                        # also install SP970 Launcher3 as HOME
  python strip-vendor.py --restore --reboot   # put everything back

Kept (hard red lines): /system/vendor/Default (qcril.db), speccfg/vendor.prop,
speccfg/spec, all Qualcomm libs, android.policy.odex / services.odex,
TeleService / TelephonyProvider / ims / Settings, relay-helper layer.
"""
import argparse
import hashlib
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"F:\wifi\sp970\adb\platform-tools\adb.exe"
SERVER = "tcp:5039"
SERIAL = "0"
BB = "/system/xbin/busybox"

BACKUP_APK = os.path.join(HERE, "ufilauncherzx.backup.apk")
BACKUP_MD5 = "1e4c5b165e87b704aae84f6cef27911f"
L3 = os.path.join(HERE, "Launcher3_sp970.apk")
L2RES = os.path.join(HERE, "Launcher2LayoutRes_sp970.apk")
STUB = os.path.join(HERE, "switch_spec.stub")

STUB_TEXT = "#!/system/bin/sh\n# disabled: switch_spec.sh used to wipe data when switching carrier spec.\nexit 0\n"

STRIP_SH = r"""#!/system/bin/sh
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
"""

RESTORE_SH = r"""#!/system/bin/sh
BB=/system/xbin/busybox
echo "== restore vendor app =="
$BB cp /data/local/tmp/ufilauncherzx.backup.apk /system/priv-app/ufilauncherzx.apk
$BB chmod 644 /system/priv-app/ufilauncherzx.apk
if [ -f /system/vendor/speccfg/switch_spec.sh.pre-slim ]; then
  $BB cp /system/vendor/speccfg/switch_spec.sh.pre-slim /system/vendor/speccfg/switch_spec.sh
  $BB chmod 755 /system/vendor/speccfg/switch_spec.sh
fi
$BB rm -f /system/priv-app/Launcher3.apk /system/app/Launcher2LayoutRes.apk
echo RESTORED
"""


def adb(args, timeout=120, binary=False):
    cmd = [ADB, "-L", SERVER, "-s", SERIAL] + list(args)
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout)
    out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    return out, err, p.returncode


def sh(command, timeout=120):
    return adb(["shell", command], timeout=timeout)


def push(local, remote):
    out, err, rc = adb(["push", local, remote], timeout=180)
    if rc != 0:
        raise SystemExit("push failed %s -> %s: %s %s" % (local, remote, out, err))
    print("  pushed %s" % os.path.basename(local))


def snapshot(tag):
    cmd = ("%s ls -l /system/priv-app/ufilauncherzx.apk /system/priv-app/Launcher3.apk "
           "/system/vendor/speccfg/switch_spec.sh 2>&1; "
           "echo ---; %s ls -d /system/vendor/CTA /system/vendor/ChinaMobile "
           "/system/vendor/overlay /data/SelfHost /data/data/com.youdo.server 2>&1; "
           "echo ---; pm list packages -f | %s grep -iE 'youdo|launcher'" % (BB, BB, BB))
    out, _, _ = sh(cmd)
    path = os.path.join(HERE, "vendor-strip-%s.txt" % tag)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("  snapshot -> %s" % path)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--restore", action="store_true")
    ap.add_argument("--with-launcher", action="store_true")
    ap.add_argument("--reboot", action="store_true")
    args = ap.parse_args()

    out, _, _ = sh("id")
    if "uid=0(root)" not in out:
        raise SystemExit("adb shell 不是 root，终止（当前：%s）" % out.strip())
    print("[pre] root OK")

    if not os.path.exists(BACKUP_APK) or \
            hashlib.md5(open(BACKUP_APK, "rb").read()).hexdigest() != BACKUP_MD5:
        raise SystemExit("回滚备份缺失或校验不符：%s" % BACKUP_APK)
    print("[pre] rollback APK backup OK (md5 %s)" % BACKUP_MD5)

    if not os.path.exists(STUB):
        with open(STUB, "w", encoding="utf-8", newline="\n") as f:
            f.write(STUB_TEXT)
    print("[pre] stub ready")

    if args.restore:
        print("=== RESTORE ===")
        push(BACKUP_APK, "/data/local/tmp/ufilauncherzx.backup.apk")
        script = RESTORE_SH
    else:
        print("=== STRIP ===")
        snapshot("before")
        push(BACKUP_APK, "/data/local/tmp/ufilauncherzx.backup.apk")
        push(STUB, "/data/local/tmp/switch_spec.stub")
        if args.with_launcher:
            for p in (L3, L2RES):
                if not os.path.exists(p):
                    raise SystemExit("缺少 SP970 桌面文件：%s" % p)
                push(p, "/data/local/tmp/" + os.path.basename(p))
        else:
            sh("rm -f /data/local/tmp/Launcher3_sp970.apk "
               "/data/local/tmp/Launcher2LayoutRes_sp970.apk")
        script = STRIP_SH

    # `adb remount` (client-side request via adbd) is what actually unlocks
    # /system on this 4.4 build; an in-shell `mount -o remount,rw` does not.
    print("[pre] adb remount")
    out, err, _ = adb(["remount"], timeout=60)
    print("  " + (out + err).strip()[:200])
    probe, _, _ = sh("echo ok > /system/.wtest 2>/dev/null && echo WRITABLE; "
                     "%s rm -f /system/.wtest" % BB)
    if "WRITABLE" not in probe:
        raise SystemExit("/system 仍是只读，终止（%s）" % probe.strip())
    print("[pre] /system writable OK")

    local_sh = os.path.join(HERE, "_strip-vendor-device.sh")
    with open(local_sh, "w", encoding="utf-8", newline="\n") as f:
        f.write(script.replace("\r\n", "\n"))
    push(local_sh, "/data/local/tmp/strip-vendor.sh")
    out, err, rc = sh("sh /data/local/tmp/strip-vendor.sh", timeout=180)
    print(out.strip())
    if err.strip():
        print("[stderr] " + err.strip()[:300])
    if "DONE" not in out and "RESTORED" not in out:
        raise SystemExit("设备侧脚本未正常结束 (rc=%d)" % rc)

    if not args.restore:
        snapshot("after")

    if args.reboot:
        print("=== reboot ===")
        sh("reboot", timeout=30)
    else:
        print("\n未重启。PMS 需要重启才会重新扫描 /system；加 --reboot 或手动 adb reboot。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
