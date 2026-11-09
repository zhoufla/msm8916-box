#!/system/bin/sh
# JZ02 SIM mux selector.
# Runs once at "class_start main" as root (init.rc service flash_recovery),
# i.e. BEFORE rild / modem / framework bring-up. No hot-switch involved.
#
# Hardware is a 3-way SIM switch exposed through leds-gpio:
#   sim1_switch_gpio=1 -> eSIM1, built-in (ICCID 89861121280035280180)
#   sim2_switch_gpio=1 -> eSIM2, built-in
#   sim3_switch_gpio=1 -> external physical SIM socket
#   sim_hotdet_gpio    -> hot-swap strobe, idle low
#
# Vendor LightsService.switchSimSlot() is only triggered by an explicit
# "android.led.sim.switch" broadcast. The vendor Web panel / backend that
# used to send it was removed during hardening, so the mux stays on eSIM1
# even when a physical card is inserted. Selecting the slot here at cold
# boot avoids the framework's hot-switch state machine (which sleeps 4.5s
# on the system_server thread, then toggles radio and restores APNs).
#
# Control (property lives in /data/property, survives reboot):
#   setprop persist.jz02.sim.slot 3; reboot   -> external physical SIM
#   setprop persist.jz02.sim.slot 2; reboot   -> built-in eSIM2
#   setprop persist.jz02.sim.slot 1; reboot   -> built-in eSIM1 (explicit)
#   setprop persist.jz02.sim.slot 0; reboot   -> no override (kernel default)
#   property unset                           -> no override (SAFE DEFAULT)
#   rm /system/etc/install-recovery.sh        -> disable entirely
#
# The default is deliberately "no write": merely deploying this file must not
# change the SIM channel of a working device. Opt in explicitly.
#
# HARDENING (2026-11-09): init.rc chowns these nodes to system:system and
# chmod 0777 (lines 362-367 / 400-405), so any uid-1000 process - including
# the vendor LightsService - can flip the mux at runtime. After selecting the
# slot we chmod 0444, so only root (CAP_DAC_OVERRIDE) can ever move it again
# and the external SIM slot stays selected for the whole boot.
# Timing: init.rc's 0777 runs in "on boot" BEFORE "class_start main" (line 431)
# which starts this service, so our 0444 wins. No later script touches them.
# Rollback: unpack /sdcard/Download/devcmp-rollback.tar.gz (the original
#   install-recovery.sh is a member), or
#   chmod 0777 /sys/class/leds/sim{1,2,3}_switch_gpio/brightness \
#              /sys/class/leds/sim_hotdet_gpio/brightness

L=/sys/class/leds
MUX_NODES='sim1_switch_gpio sim2_switch_gpio sim3_switch_gpio sim_hotdet_gpio dualsim_switch_gpio dualsim_hotdet_gpio'

slot=$(getprop persist.jz02.sim.slot)
[ -n "$slot" ] || slot=0
case "$slot" in
    1|2|3) ;;
    *) slot=0 ;;
esac

[ -e "$L/sim1_switch_gpio/brightness" ] || exit 0

if [ "$slot" -ne 0 ]; then
    # Put all channels low first, then assert exactly one. Final state matches
    # the steady state of LightsService.switchSimSlot() with hotdet low.
    echo 0 > "$L/sim_hotdet_gpio/brightness"
    echo 0 > "$L/sim1_switch_gpio/brightness"
    echo 0 > "$L/sim2_switch_gpio/brightness"
    echo 0 > "$L/sim3_switch_gpio/brightness"
    case "$slot" in
        1) echo 1 > "$L/sim1_switch_gpio/brightness" ;;
        2) echo 1 > "$L/sim2_switch_gpio/brightness" ;;
        3) echo 1 > "$L/sim3_switch_gpio/brightness" ;;
    esac
fi

# Lock AFTER the writes; applies even when slot=0 (kernel default kept, but
# the mux is still pinned against the framework's hot-switch state machine).
for n in $MUX_NODES; do
    p="$L/$n/brightness"
    [ -e "$p" ] && chmod 0444 "$p"
done

# --- mdss early blank (v13, 2026-11-09) -----------------------------------
# cmdline asks for "1:spi:0:qcom,mdss_spi_st7735s_128128_cmd", but the probed
# fb0 is a "mipi dsi video panel" with no real display attached. SurfaceFlinger
# keeps composing video-mode frames; mdss_mdp_video_pollwait() times out on
# every vsync and logs "frame timeout" ~8 times/s from boot_progress_enable_
# screen (~43s) onwards, pinning mdss_fb0 in D state and burning ~10% of one
# core (loadavg 5+, SoC +4~5C vs SP970).
#
# The v7.2 fix wrote blank=4 from relay-helper/start.sh. But qcom-post-boot is
# "class late_start / disabled / oneshot", started by
#     on property:sys.boot_completed=1 -> start qcom-post-boot
# and start.sh then slept 90s => blank landed at ~142s. The whole 43-142s
# window still stormed ("补丁后仍在"). This hook runs at class_start main,
# i.e. ~100s earlier, so the storm never gets going.
#
# Safety:
#  - polling waits for fb0 to appear (kernel probe may lag userspace);
#  - first write is deferred until SurfaceFlinger has been "running" for 5s,
#    so blanking cannot disturb SF's readyToRun()/hwc_open();
#  - re-asserts every 1s for 240s, so SF's later enable_screen() unblank and
#    the old 90s fallback cannot resurrect the storm;
#  - writing 4 when already blanked is a verified no-op (show_blank_event
#    stays "panel_power_on = 0", dmesg gains no new events);
#  - hard fallback at i>90 even if init.svc.surfaceflinger never shows up.
# Forensics: /data/local/tmp/jz02-mdss-early.log holds first_write_uptime,
#            writes= count and last_uptime.
# Rollback: delete this block; start.sh keeps its 90s re-assert as safety net.
if [ "$(getprop ro.product.device)" = "UFI" ]; then
    (
        B=/sys/class/graphics/fb0/blank
        LOG=/data/local/tmp/jz02-mdss-early.log
        BB=/system/xbin/busybox
        : > "$LOG" 2>/dev/null
        i=0
        warm=0
        wrote=0
        first=0
        while [ "$i" -lt 240 ]; do
            [ "$(getprop init.svc.surfaceflinger)" = "running" ] && warm=$((warm + 1))
            if [ -e "$B" ] && { [ "$warm" -ge 5 ] || [ "$i" -gt 90 ]; }; then
                if [ "$first" = "0" ]; then
                    first=1
                    echo "first_write_uptime=$(cat /proc/uptime)" >> "$LOG"
                fi
                echo 4 > "$B" 2>/dev/null
                wrote=$((wrote + 1))
            fi
            i=$((i + 1))
            $BB sleep 1
        done
        echo "writes=$wrote warm=$warm last_uptime=$(cat /proc/uptime)" >> "$LOG"
    ) >/dev/null 2>&1 &
fi

exit 0
