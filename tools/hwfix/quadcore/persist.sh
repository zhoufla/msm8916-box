#!/system/bin/sh
# =============================================================================
# SP970 四核修复 · 固化（重启后自动生效）
# =============================================================================
# 做什么：
#   1) 备份 /system/etc/init.qcom.post_boot.sh -> 同名 .sp970bak
#   2) 把 4 个 payload 装到 /system/etc/sp970_hotfix/
#   3) 在 post_boot.sh 第 1 行（shebang）之后插入 hook 片段
#      —— 开机时由 init 以 root 执行，dd 写 /dev/mem 打补丁并拉起 1..3 核
#
# 不动 boot 分区、不改 zImage/DTB，可 unpersist.sh 一键撤销。
# 幂等：已安装时会直接退出。
# 用法：sh persist.sh [payload_dir]
# =============================================================================

D=$(dirname "$0")
BIN=${1:-$D/bin}
PB=/system/etc/init.qcom.post_boot.sh
BAK=$PB.sp970bak
SP=/system/etc/sp970_hotfix

[ -f "$PB" ] || { echo "找不到 $PB，中止"; exit 1; }

if busybox grep -q "SP970 quad-core hotfix BEGIN" "$PB" 2>/dev/null; then
    echo "已安装过（存在 hotfix 标记），跳过"
    exit 0
fi

echo "=== 1. 备份开机脚本 ==="
cp -p "$PB" "$BAK" || { echo "备份失败，中止"; exit 1; }

echo "=== 2. 安装 payload ==="
mkdir -p "$SP"
for f in trampoline.bin patch_45ec.bin rollback_stub.bin rollback_45ec.bin; do
    cp "$BIN/$f" "$SP/$f" || { echo "复制 $f 失败"; exit 1; }
    chmod 0644 "$SP/$f"
done

echo "=== 3. 插入 hook（第 1 行之后）==="
busybox sed -i "1r $D/post_boot.snippet" "$PB" || { echo "插入失败，正在还原"; cp -p "$BAK" "$PB"; exit 1; }
chmod 0755 "$PB"

echo "=== 4. 校验 ==="
busybox grep -n "SP970 quad-core hotfix" "$PB"
busybox head -4 "$PB"
busybox ls -l "$SP"
echo "=== DONE：下次开机自动生效（本次不重启）==="
