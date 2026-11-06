#!/system/bin/sh
# =============================================================================
# SP970 四核修复 · 运行时热补丁
# =============================================================================
# 缺陷：原厂内核 per_cpu(idle_threads, 1..3) 为 NULL，cpu_up() 走到
#       idle_thread_get() 时返回 -ENOMEM，于是 CPU1/2/3 永远上不了线，
#       /sys/devices/system/cpu/online 恒为 0（单核跑）。
#
# 做法：不动 boot / 不刷机 / 不改 zImage，只用 /dev/mem 把内核里
#       0xc08345ec 那条 mvneq r4,#0xb 换成 beq 0xc0833500，
#       跳到预先写好的 trampoline；trampoline 调 fork_idle() 现场补建
#       idle 线程，写回 per_cpu(idle_threads,cpu)，再跳回原流程。
#
# 地址（VA / PA）：本机 3.10.28 内核，PA = VA - 0x40000000
#   trampoline 落点 0xc0833500 -> word 539020608（44 字节 / 11 word）
#   补丁点         0xc08345ec -> word 539021691（1 word）
#
# 前提：root；SELinux Enforcing 下需 root shell（本机 context=u:r:shell:s0 可写）。
# 用法：sh apply.sh [payload_dir]
#       payload_dir 默认为脚本同级的 bin/ 目录。
# =============================================================================

D=$(dirname "$0")
BIN=${1:-$D/bin}
STUB=539020608
P45=539021691

for f in trampoline.bin patch_45ec.bin; do
    [ -f "$BIN/$f" ] || { echo "缺少 payload: $BIN/$f"; exit 1; }
done

echo "=== 1. 备份原指令 ==="
dd if=/dev/mem of=/data/local/tmp/hwfix_bak_stub.bin bs=4 skip=$STUB count=11 2>/dev/null
dd if=/dev/mem of=/data/local/tmp/hwfix_bak_45ec.bin bs=4 skip=$P45 count=1 2>/dev/null
busybox od -An -tx4 /data/local/tmp/hwfix_bak_45ec.bin

echo "=== 2. 写入 trampoline (44B) ==="
dd if="$BIN/trampoline.bin" of=/dev/mem bs=4 seek=$STUB count=11 conv=notrunc 2>/dev/null

echo "=== 3. 写入 beq @0xc08345ec ==="
dd if="$BIN/patch_45ec.bin" of=/dev/mem bs=4 seek=$P45 count=1 conv=notrunc 2>/dev/null

echo "=== 4. 回读校验 ==="
echo -n "stub : "; dd if=/dev/mem bs=4 skip=$STUB count=11 2>/dev/null | busybox od -An -tx4
echo -n "45ec : "; dd if=/dev/mem bs=4 skip=$P45 count=1 2>/dev/null | busybox od -An -tx4
echo -n "期望 : "; busybox od -An -tx4 "$BIN/patch_45ec.bin"

echo "=== 5. 刷 I-Cache（execve 触发）==="
i=0
while [ $i -lt 20 ]; do
    cat /proc/kallsyms   > /dev/null 2>&1
    cat /proc/interrupts > /dev/null 2>&1
    ps                   > /dev/null 2>&1
    i=$((i+1))
done

echo "=== 6. 拉起 CPU1/2/3 ==="
for c in 1 2 3; do
    echo 1 > /sys/devices/system/cpu/cpu$c/online 2>/dev/null
    echo "cpu$c -> rc=$? online=$(cat /sys/devices/system/cpu/online)"
done

echo "=== 7. 结果 ==="
echo "online  = $(cat /sys/devices/system/cpu/online)"
echo "present = $(cat /sys/devices/system/cpu/present)"
for c in 0 1 2 3; do echo "cpu$c/online = $(cat /sys/devices/system/cpu/cpu$c/online 2>/dev/null)"; done
echo "--- 每核 kthread（应有 migration/N、ksoftirqd/N）---"
ps 2>/dev/null | busybox grep -E "migration|ksoftirqd"
echo "=== DONE ==="
