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

L=/sys/class/leds
slot=$(getprop persist.jz02.sim.slot)
[ -n "$slot" ] || slot=0

case "$slot" in
    0) exit 0 ;;
    1|2|3) ;;
    *) exit 0 ;;
esac

[ -e "$L/sim1_switch_gpio/brightness" ] || exit 0

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

exit 0
