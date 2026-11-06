#!/bin/bash
# 修改 system-custom.img 的 USB 默认配置：charging,none -> rndis,adb
IMG=/mnt/f/wifi/sp970/bak/v11/system-custom.img
mkdir -p /tmp/syspatch
debugfs -R "dump /build.prop /tmp/syspatch/build.prop" "$IMG" 2>/dev/null
[ -s /tmp/syspatch/build.prop ] || { echo "dump 失败"; exit 1; }
sed -i 's/^persist.sys.usb.config=.*/persist.sys.usb.config=rndis,adb/' /tmp/syspatch/build.prop
sed -i 's/^ro.usb.default.config=.*/ro.usb.default.config=rndis,adb/' /tmp/syspatch/build.prop
echo "=== 修改后 ==="
grep -E "usb.*config|adb.secure" /tmp/syspatch/build.prop
debugfs -w -R "rm /build.prop" "$IMG" 2>/dev/null
debugfs -w -R "write /tmp/syspatch/build.prop /build.prop" "$IMG" 2>/dev/null
debugfs -w -R "sif /build.prop mode 0100644" "$IMG" 2>/dev/null
debugfs -w -R "sif /build.prop uid 0" "$IMG" 2>/dev/null
debugfs -w -R "sif /build.prop gid 0" "$IMG" 2>/dev/null
e2fsck -f -y "$IMG" >/dev/null 2>&1 || true
echo "=== 镜像内复核 ==="
debugfs -R "cat /build.prop" "$IMG" 2>/dev/null | grep -E "persist.sys.usb.config|ro.usb.default"
debugfs -R "stat /build.prop" "$IMG" 2>/dev/null | head -3
