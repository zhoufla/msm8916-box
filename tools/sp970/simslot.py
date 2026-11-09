#!/usr/bin/env python3
"""SP970 第二卡槽（ril-daemon1）停用 / 恢复：临时（运行时）与永久（跨重启）。

背景
----
SP970-B 按 DSDS 编译：/system/build.prop:202 写死
`persist.radio.multisim.config=dsds`，vendor RIL 据此把 `ril-daemon1`
（/init.qcom.rc:743，disabled 服务，rild -c 1）`ctl.start` 起来。
但本机第二 SIM 卡槽**物理上未焊接**，卡 2 恒为 ABSENT，这路 RIL 纯属空转。

两层手段
--------
- off / on：运行时 stop/start ril-daemon1，不改配置、不重启，重启即恢复。
- permanent-off / permanent-on：在 relay-helper 的 crontab 里安装/移除一条
  守护行（每分钟检查，running 就 ctl.stop），**跨重启自动生效且持续保持**。
  框架仍按原厂 DSDS 初始化，主卡完全不受影响；不改 boot、不改 rild、
  不动 persist 属性，回滚只需删掉 crontab 里那一行。

为什么不用「改 persist.radio.multisim.config 让框架单卡」
--------------------------------------------------------
2026-11-09 实测：写 ssss（落 /data/property 覆盖 build.prop）并重启后，
框架确实按单卡初始化（simSlots 只剩 1 项、ril-daemon1 未启动），
但主卡变成 NOT_READY / OUT_OF_SERVICE，等 120 秒仍不恢复。
日志：PhoneFactory 用 preferredNetworkType=7（GLOBAL，因双卡残留
preferred_network_mode="9,1" 无法解析）、qcril 报
`Error reading iccid for slot 0` / IMSI、ICCID fetch failed /
qcril_db_init readonly database。即此 ROM 按 DSDS 编译与校准，
单卡路径会连主卡 UIM 一起初始化失败。**这条路径已否决，脚本不再提供。**
失败实验的完整记录见 tools/sp970/README.md。

用法
----
    python3 tools/sp970/simslot.py status
    python3 tools/sp970/simslot.py off [--wait 20] [--no-rollback]
    python3 tools/sp970/simslot.py on

    python3 tools/sp970/simslot.py permanent-off                 # 装守护 + 立即停用
    python3 tools/sp970/simslot.py permanent-off --verify-reboot # 再重启验证
    python3 tools/sp970/simslot.py permanent-on                  # 移除守护 + 恢复双卡

连接参数：
    --host   设备 IP，默认 192.168.10.196（也可用 $RELAY_BASE，形如 host:8080）
    --port   面板端口，默认 8080
    --auth   面板 Basic 凭据，默认 $AUTH 或 admin:admin（与 panel-e2e.sh 一致）

等价手工命令（网络 ADB）：
    adb -s 192.168.10.196:5555 shell setprop ctl.stop ril-daemon1
    adb -s 192.168.10.196:5555 shell start ril-daemon1
守护行本体在 tools/relay-helper/crontab（JZ02/SP970 共用，脚本内保留同文副本），
设备侧由面板「维护 → 定时任务」写入，start.sh 30 秒内自动重载 busybox crond。
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.request

SERVICE = "ril-daemon1"
MULTISIM = "persist.radio.multisim.config"
PROP_FILE = "/data/property/" + MULTISIM
GUARD_MARK = "ctl.stop " + SERVICE
GUARD_BEGIN = "# 第二卡槽物理未焊接"
# 与 tools/relay-helper/crontab 里的片段保持一致（改动时两边同步）
GUARD_BLOCK = """# 第二卡槽物理未焊接（SP970）：保持 ril-daemon1 停止。框架仍按原厂 DSDS 初始化，
# 卡 2 恒 ABSENT，主卡不受影响；开机后 crond 最多 60s 内停掉第二路 RIL 并持续保持。
# 详见 tools/sp970/README.md「永久停用第二卡槽」。JZ02 无此服务，条件天然为假、零副作用。
# 回滚：删掉下一行，再执行 start ril-daemon1。
* * * * * [ \"$(/system/bin/getprop init.svc.ril-daemon1)\" = running ] && /system/bin/setprop ctl.stop ril-daemon1 >/dev/null 2>&1
"""


class Panel:
    def __init__(self, host, port, auth):
        self.base = "http://%s:%d" % (host, port)
        self.header = {
            "Authorization": "Basic " + base64.b64encode(auth.encode()).decode(),
            "Content-Type": "application/json",
        }

    def get(self, path, timeout=20):
        req = urllib.request.Request(self.base + path, headers=self.header)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())

    def post(self, path, payload, timeout=30):
        req = urllib.request.Request(
            self.base + path, data=json.dumps(payload).encode(), headers=self.header
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())

    def shell(self, cmd, timeout=30):
        body = json.dumps({"cmd": cmd}).encode()
        req = urllib.request.Request(
            self.base + "/api/maint/cmd", data=body, headers=self.header
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
        if not d.get("ok"):
            raise RuntimeError("面板命令失败: %s" % d.get("message"))
        return d.get("out") or ""

    def prop(self, name):
        return self.shell("getprop " + name).strip()


def state(p):
    """一次性取回双卡状态、健康检查、射频读数。"""
    out = p.shell(
        "echo ---props---; "
        "getprop persist.radio.multisim.config; "
        "getprop gsm.sim.state; "
        "getprop init.svc.ril-daemon; "
        "getprop init.svc.ril-daemon1; "
        "echo ---rild---; ps | grep -E 'rild|com.android.phone|smsrelay'; "
        "echo ---service---; dumpsys telephony.registry | grep mServiceState"
    )
    props = {}
    lines = out.splitlines()
    try:
        i = lines.index("---props---")
        props = {
            "multisim": lines[i + 1].strip(),
            "sim_state": lines[i + 2].strip(),
            "rild0": lines[i + 3].strip(),
            "rild1": lines[i + 4].strip(),
        }
    except (ValueError, IndexError):
        props = {"parse_error": out[:200]}
    service = next((l.strip() for l in lines if "mServiceState=" in l), "")
    try:
        radio = p.get("/api/radio")
        sysinfo = p.get("/api/sys")
        status = p.get("/api/status")
        checkup = p.get("/api/maint/checkup")
        sig = sysinfo.get("signal") or {}
        extra = {
            "simSlots": radio.get("simSlots"),
            "netType": radio.get("netType"),
            "rsrp": sig.get("rsrp"),
            "rsrq": sig.get("rsrq"),
            "rssnr": sig.get("rssnr"),
            "level": sig.get("level"),
            "device": sysinfo.get("device"),
            "panel_ok": status.get("ok"),
            "keepalive_ok": (status.get("keepalive") or {}).get("lastPingOk"),
            "checkup_healthy": checkup.get("healthy"),
        }
    except Exception as e:
        extra = {"api_error": str(e)}
    return props, service, extra, out


def health(p, verbose=True):
    """主卡是否仍然健康。任一关键项失败即判定需要回滚。"""
    try:
        st = p.get("/api/status")
        sy = p.get("/api/sys")
        svc = p.shell("dumpsys telephony.registry | grep mServiceState")
        ps = p.shell("ps | grep -E 'com.android.phone|smsrelay|rild'")
        rsrp = (sy.get("signal") or {}).get("rsrp")
        # 面板刷新瞬间可能返回 None / 2147483647，重试一次再判定，避免误报
        if not (isinstance(rsrp, (int, float)) and rsrp < 0):
            time.sleep(2)
            rsrp = ((p.get("/api/sys").get("signal") or {}).get("rsrp"))
        items = {
            "面板可用": st.get("ok") is True,
            "主卡在服务": "0 0" in svc and "LTE" in svc,
            "phone 进程": "com.android.phone" in ps,
            "短信网关": "smsrelay" in ps,
            "数据保活": bool((st.get("keepalive") or {}).get("lastPingOk")),
            "信号读数": isinstance(rsrp, (int, float)) and rsrp < 0,
        }
    except Exception as e:
        items = {"健康检查异常": False}
        print("  健康检查异常: %s" % e)
    ok = all(items.values())
    if verbose:
        for k, v in items.items():
            print("  %-12s %s" % (k, "OK" if v else "FAIL"))
    return ok


def persisted_value(p):
    """/data/property 里的持久化值；不存在返回 None（用 build.prop 默认 dsds）。"""
    out = p.shell(
        "if [ -f %s ]; then echo __FILE__; cat %s; else echo __NONE__; fi"
        % (PROP_FILE, PROP_FILE)
    )
    lines = out.splitlines()
    if lines and lines[0].strip() == "__FILE__":
        return "".join(lines[1:]).strip()
    return None


def rild_count(p):
    out = p.shell("ps | grep -E '/system/bin/rild|rild -c'")
    return len([l for l in out.splitlines() if "rild" in l and "grep" not in l])


def cron_text(p):
    return (p.get("/api/maint/cron").get("text") or "")


def cron_save(p, text):
    r = p.post("/api/maint/cron", {"text": text})
    if not r.get("ok"):
        raise RuntimeError("保存 crontab 失败: %s" % r.get("message"))
    return r


def guard_present(text):
    return GUARD_MARK in text


def cron_with_guard(text):
    if guard_present(text):
        return text
    return text.rstrip("\n") + "\n" + GUARD_BLOCK.rstrip("\n") + "\n"


def cron_without_guard(text):
    out, skipping = [], False
    for line in text.splitlines():
        if GUARD_BEGIN in line:
            skipping = True
            continue
        if skipping:
            if GUARD_MARK in line:
                skipping = False
            continue
        if GUARD_MARK in line:  # 兜底：单独残留的任务行
            continue
        out.append(line)
    return "\n".join(out) + "\n"


def show(p, tag):
    props, service, extra, _ = state(p)
    print("[%s]" % tag)
    print("  multisim=%s  gsm.sim.state=%s  persist覆盖=%s" % (
        props.get("multisim"), props.get("sim_state"), persisted_value(p)))
    print("  ril-daemon=%s  ril-daemon1=%s  rild进程数=%d" % (
        props.get("rild0"), props.get("rild1"), rild_count(p)))
    print("  永久守护(crontab)=%s" % ("已安装" if guard_present(cron_text(p)) else "未安装"))
    print("  simSlots=%s  netType=%s" % (extra.get("simSlots"), extra.get("netType")))
    print("  RSRP=%s  RSRQ=%s  SINR=%s  level=%s" % (
        extra.get("rsrp"), extra.get("rsrq"), extra.get("rssnr"), extra.get("level")))
    print("  panel_ok=%s  keepalive=%s  checkup=%s" % (
        extra.get("panel_ok"), extra.get("keepalive_ok"), extra.get("checkup_healthy")))
    print("  %s" % service.strip()[:160])
    return props, extra


def do_off(p, wait, rollback):
    show(p, "关闭前")
    if p.prop("init.svc." + SERVICE) != "running":
        print("%s 当前不是 running，无需操作。" % SERVICE)
        return 0
    print("\n>>> stop %s" % SERVICE)
    p.shell("stop " + SERVICE)
    time.sleep(3)
    now = p.prop("init.svc." + SERVICE)
    print("3 秒后: init.svc.%s=%s" % (SERVICE, now))
    if now == "running":
        print("服务被立即拉起，运行时停用无效（请检查永久守护是否失效）。")
        return 1
    print("观察 %d 秒是否被守护复活 ..." % wait)
    time.sleep(wait)
    revived = p.prop("init.svc." + SERVICE)
    print("复活检查: init.svc.%s=%s" % (SERVICE, revived))
    print("\n主卡健康检查:")
    ok = health(p)
    problems = []
    if revived == "running":
        problems.append("第二路 RIL 被自动复活")
    if not ok:
        problems.append("主卡健康检查未通过")
    if problems and rollback:
        print("\n!!! %s，自动回滚 start %s" % ("；".join(problems), SERVICE))
        p.shell("start " + SERVICE)
        time.sleep(3)
        print("回滚后 init.svc.%s=%s" % (SERVICE, p.prop("init.svc." + SERVICE)))
        print("回滚后健康检查:")
        health(p)
        return 1
    print("\n>>> 完成：第二卡槽已停用（仅运行时；已装永久守护则重启后仍保持）")
    show(p, "关闭后")
    if problems:
        print("注意：%s（已按 --no-rollback 保留停用状态）" % "；".join(problems))
    return 0


def do_on(p):
    print(">>> start %s" % SERVICE)
    p.shell("start " + SERVICE)
    time.sleep(3)
    print("init.svc.%s=%s" % (SERVICE, p.prop("init.svc." + SERVICE)))
    print("健康检查:")
    health(p)
    show(p, "恢复后")
    return 0


def uptime(p):
    try:
        return float(p.shell("cat /proc/uptime").split()[0])
    except Exception:
        return 0.0


def boot_id(p):
    try:
        return p.shell("cat /proc/sys/kernel/random/boot_id").strip()
    except Exception:
        return ""


def reboot_and_wait(p, timeout=240):
    """触发重启，等面板离线再回来，且确认 boot_completed=1 + 新一次启动。"""
    up0, bid0 = uptime(p), boot_id(p)
    print(">>> 触发重启（重启前 uptime %.0f s，会短暂断网）" % up0)
    try:
        p.shell("setprop sys.powerctl reboot", timeout=8)
    except Exception as e:
        print("  reboot 请求断开（预期）: %s" % type(e).__name__)

    def wait_offline(seconds):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                p.get("/api/status", timeout=3)
            except Exception:
                return True
            time.sleep(2)
        return False

    off = wait_offline(25)
    if not off:
        print("  sys.powerctl 未触发重启，改用 reboot 命令")
        try:
            p.shell("reboot", timeout=6)
        except Exception as e:
            print("  reboot 请求断开（预期）: %s" % type(e).__name__)
        off = wait_offline(25)
    print("  确认离线: %s" % ("是" if off else "否（可能重启极快）"))

    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            st = p.get("/api/status", timeout=4)
            boot = p.prop("sys.boot_completed")
            bid = boot_id(p)
            up = uptime(p)
            fresh = ((bid and bid0 and bid != bid0) or up < up0 - 5
                     or (up0 < 180 and up < 240))
            if st.get("ok") and boot == "1" and fresh:
                print("  设备已回来：boot_completed=1 uptime=%.0f s" % up)
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def do_permanent_off(p, verify_reboot, settle):
    show(p, "改动前")
    cur = p.prop(MULTISIM)
    if cur != "dsds":
        print("\n!!! 当前 multisim=%s（不是原厂 dsds）。" % cur)
        print("    单卡配置会让主卡 NOT_READY，请先执行 permanent-on 恢复并重启。")
        return 1
    text = cron_text(p)
    if not guard_present(text):
        print("\n>>> 在 crontab 安装永久守护行")
        cron_save(p, cron_with_guard(text))
        print("    已保存，start.sh 30 秒内自动重载 crond")
    else:
        print("\n>>> 永久守护行已存在，跳过写入")
    print(">>> 立即 setprop ctl.stop %s" % SERVICE)
    p.shell("setprop ctl.stop " + SERVICE)
    time.sleep(3)
    print("    init.svc.%s=%s  rild进程数=%d" % (
        SERVICE, p.prop("init.svc." + SERVICE), rild_count(p)))
    if verify_reboot:
        if not reboot_and_wait(p):
            print("!!! 重启后设备未恢复，请人工检查")
            return 2
        print("等待 %d 秒让 crond 至少执行过一次守护行 ..." % settle)
        time.sleep(settle)
    print("\n主卡健康检查:")
    ok = health(p)
    show(p, "完成状态")
    if p.prop("init.svc." + SERVICE) == "running" or rild_count(p) > 1:
        print("\n!!! 第二路 RIL 仍在运行，守护可能未重载；请查 crond.log")
        return 1
    if not ok:
        print("\n!!! 主卡健康检查未通过，可用 permanent-on 回滚")
        return 1
    print("\n>>> 完成：第二卡槽已永久停用（守护跨重启生效，框架保持原厂 DSDS）")
    return 0


def do_permanent_on(p, verify_reboot, settle):
    show(p, "改动前")
    changed = p.prop(MULTISIM) != "dsds"
    if changed:
        print("\n>>> 检测到非原厂 multisim，写回 dsds（重启后生效）")
        p.shell("setprop %s dsds" % MULTISIM)
    text = cron_text(p)
    if guard_present(text):
        print(">>> 从 crontab 移除永久守护行")
        cron_save(p, cron_without_guard(text))
    print(">>> start %s" % SERVICE)
    p.shell("start " + SERVICE)
    time.sleep(3)
    if changed or verify_reboot:
        if not reboot_and_wait(p):
            print("!!! 重启后设备未恢复，请人工检查")
            return 2
        time.sleep(settle)
    print("\n主卡健康检查:")
    health(p)
    show(p, "恢复后状态")
    print("\n>>> 完成：已恢复原厂双卡（ril-daemon1 运行）")
    return 0


def main():
    ap = argparse.ArgumentParser(description="SP970 第二卡槽停用/恢复（临时或永久）")
    ap.add_argument("action", choices=[
        "status", "off", "on", "permanent-off", "permanent-on"])
    ap.add_argument("--host", default=os.environ.get("RELAY_BASE", "192.168.10.196").split(":")[0])
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--auth", default=os.environ.get("AUTH", "admin:admin"))
    ap.add_argument("--wait", type=int, default=20, help="off 后观察复活的秒数")
    ap.add_argument("--no-rollback", action="store_true", help="off 健康检查失败也不回滚（不推荐）")
    ap.add_argument("--verify-reboot", action="store_true",
                    help="permanent-* 完成后重启一次，验证跨重启生效")
    ap.add_argument("--settle", type=int, default=95,
                    help="--verify-reboot 后等待守护执行的秒数（默认 95，覆盖 crond 一分钟周期）")
    a = ap.parse_args()
    p = Panel(a.host, a.port, a.auth)
    try:
        if a.action == "status":
            show(p, "当前状态")
            print("\n健康检查:")
            health(p)
            return 0
        if a.action == "off":
            return do_off(p, a.wait, not a.no_rollback)
        if a.action == "on":
            return do_on(p)
        if a.action == "permanent-off":
            return do_permanent_off(p, a.verify_reboot, a.settle)
        return do_permanent_on(p, a.verify_reboot, a.settle)
    except Exception as e:
        print("失败: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
