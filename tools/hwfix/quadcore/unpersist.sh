#!/system/bin/sh
# =============================================================================
# SP970 四核修复 · 撤销固化（恢复原厂开机脚本）
# =============================================================================
# 还原 /system/etc/init.qcom.post_boot.sh，并删除 /system/etc/sp970_hotfix/。
# 注意：本脚本只撤销「开机自动打补丁」，当前内存里的补丁仍然生效；
#       若要让本次运行也回到单核，再跑一次 rollback.sh（或直接重启）。
# 用法：sh unpersist.sh
# =============================================================================

PB=/system/etc/init.qcom.post_boot.sh
BAK=$PB.sp970bak
SP=/system/etc/sp970_hotfix

if [ -f "$BAK" ]; then
    echo "=== 1. 还原开机脚本 ==="
    cp -p "$BAK" "$PB" && echo "已还原 $PB"
else
    echo "未找到备份 $BAK（可能从未固化）"
fi

if [ -d "$SP" ]; then
    echo "=== 2. 删除 payload ==="
    rm -rf "$SP" && echo "已删除 $SP"
fi

echo "=== 3. 校验 ==="
busybox grep -c "SP970 quad-core hotfix" "$PB" 2>/dev/null || echo "hook 已不在"
busybox ls -l "$PB" 2>/dev/null
echo "=== DONE：下次开机会回到单核；当前运行态请再执行 rollback.sh ==="
