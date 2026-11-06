#!/system/bin/sh
# =============================================================================
# SP970 四核修复 · 运行时回滚（本次运行恢复单核）
# =============================================================================
# 把 trampoline 落点和 0xc08345ec 一起写回原厂指令。
# 只影响当前这次开机；开机 hook 若仍存在，下次重启还会重新打补丁，
# 需要彻底恢复请先跑 unpersist.sh。
# 用法：sh rollback.sh [payload_dir]
# =============================================================================

D=$(dirname "$0")
BIN=${1:-$D/bin}
STUB=539020608
P45=539021691

for f in rollback_stub.bin rollback_45ec.bin; do
    [ -f "$BIN/$f" ] || { echo "缺少 payload: $BIN/$f"; exit 1; }
done

echo "=== 写回原厂指令 ==="
dd if="$BIN/rollback_stub.bin" of=/dev/mem bs=4 seek=$STUB count=11 conv=notrunc 2>/dev/null
dd if="$BIN/rollback_45ec.bin" of=/dev/mem bs=4 seek=$P45 count=1  conv=notrunc 2>/dev/null

echo "=== 回读校验 ==="
echo -n "stub : "; dd if=/dev/mem bs=4 skip=$STUB count=11 2>/dev/null | busybox od -An -tx4
echo -n "45ec : "; dd if=/dev/mem bs=4 skip=$P45 count=1 2>/dev/null | busybox od -An -tx4
echo "online = $(cat /sys/devices/system/cpu/online)"
echo "=== DONE ==="
