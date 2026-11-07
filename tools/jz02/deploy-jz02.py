#!/usr/bin/env python3
"""Deploy relay-helper to JZ02 (UFI / richal / msm8916 / Android 4.4.4).

Why this exists instead of tools/deploy-helper.py:
  * JZ02 init.qcom.post_boot.sh has NO `setprop service.adb.tcp.port 5555`
    anchor and NO long loops -> the SP970 script aborts with SystemExit.
    Here we inject right after the shebang (the script is sequential, 728 lines).
  * JZ02 has no busybox at all, and the hardened image clears /system/xbin.
    -> ship tools/jz02/busybox (armv7 ELF, taken from SP970).
  * The v3 full image sets ro.secure=0 -> adb shell is already root,
    so `adb remount` works and no web-console trick is needed.
  * Network adb is off by default -> the snippet fixes service.adb.tcp.port.

Usage:
  python tools/jz02/deploy-jz02.py --dry-run             # offline, no device
  python tools/jz02/deploy-jz02.py                       # deploy + verify
  python tools/jz02/deploy-jz02.py --verify-only
  # deploy 现在也会安装/启动 8080 面板 APK 并预置 JZ02 配置
  python tools/jz02/deploy-jz02.py --serial 192.168.42.129:5555
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FORK_ROOT = os.path.dirname(os.path.dirname(HERE))
HELPER = os.path.join(FORK_ROOT, "tools", "relay-helper")
SNAPSHOTS = os.path.join(HERE, "snapshots")

DEVICE_ROOT = "/system/etc/relay-helper"
BB = "/system/xbin/busybox"
POST_BOOT = "/system/etc/init.qcom.post_boot.sh"
MARK_BEGIN = "# --- relay-helper autostart BEGIN"
MARK_END = "# --- relay-helper autostart END ---"

DEFAULT_SERIAL = "0"           # JZ02 USB adb on tcp:5039 server
DEFAULT_SERVER = "tcp:5039"    # isolated from SP970's default 5037

OFFLINE_DEFAULTS = [
    r"F:\wifi\jz02\BAK-analysis\work\system_root\etc\init.qcom.post_boot.sh",
    "/mnt/f/wifi/jz02/BAK-analysis/work/system_root/etc/init.qcom.post_boot.sh",
]

FILES = [
    ("cgi-bin/cmd", "cgi-bin/cmd", 0o755),
    ("cgi-bin/get", "cgi-bin/get", 0o755),
    ("start.sh", "start.sh", 0o755),
    ("ping.txt", "ping.txt", 0o644),
    ("crontab", "crontab", 0o644),
    ("bin/health.sh", "bin/health.sh", 0o755),
    ("bin/panel-watchdog.sh", "bin/panel-watchdog.sh", 0o755),
]

# --- smsrelay panel (8080) ---
PANEL_PKG = "com.sp970.smsrelay"
PANEL_APK = os.path.join(FORK_ROOT, "dist", "com.sp970.smsrelay-1.7.0.apk")
PANEL_CONF = os.path.join(FORK_ROOT, "config", "smsrelay.conf.jz02.example")
PANEL_CONF_DEVICE = "/sdcard/smsrelay.conf"
PANEL_TOKEN = "jz02-relay-2026"
PANEL_FWD_PORT = 18080


def find_adb(explicit=None):
    if explicit:
        return explicit
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    for p in [r"F:\wifi\sp970\adb\platform-tools\adb.exe",
              "/mnt/f/wifi/sp970/adb/platform-tools/adb.exe"]:
        if os.path.exists(p):
            return p
    return shutil.which("adb") or "adb"


class Dev:
    def __init__(self, adb, serial, server):
        self.adb, self.serial, self.server = adb, serial, server

    def base(self):
        return [self.adb, "-L", self.server]

    def run(self, args, timeout=60, raw=False):
        cmd = self.base() + ["-s", self.serial] + list(args)
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout)
        out = p.stdout if raw else p.stdout.decode("utf-8", "replace")
        return out, p.stderr.decode("utf-8", "replace"), p.returncode

    def sh(self, command, timeout=60, raw=False):
        return self.run(["shell", command], timeout=timeout, raw=raw)

    def connect(self):
        # USB serial (e.g. "0") needs no connect; network serial does.
        if ":" in self.serial and not self.serial.isdigit():
            p = subprocess.run(self.base() + ["connect", self.serial],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            print("[adb] " + (p.stdout + p.stderr).decode("utf-8", "replace").strip())
        out, _, _ = self.run(["devices"])
        if self.serial not in str(out):
            raise SystemExit("设备没连上：%s (server=%s)\n%s" % (self.serial, self.server, out))
        for _ in range(3):
            out, _, rc = self.run(["shell", "echo WARMUP"], timeout=20)
            if rc == 0 and "WARMUP" in str(out):
                break
        print("[adb] device %s online (%s)" % (self.serial, self.server))

    def push(self, local, remote, timeout=90):
        out, err, rc = self.run(["push", local, remote], timeout=timeout)
        if rc != 0:
            raise SystemExit("push 失败 %s -> %s: %s %s" % (local, remote, err, out))


def read_text(path):
    with open(path, "rb") as f:
        return f.read().replace(b"\r\n", b"\n").decode("utf-8", "replace")


def build_post_boot(text, snippet):
    """Idempotently remove any previous autostart block, then insert after shebang."""
    lines = text.replace("\r\n", "\n").splitlines(True)
    cleaned, skipping = [], False
    for line in lines:
        if line.startswith(MARK_BEGIN):
            skipping = True
            continue
        if skipping:
            if line.startswith(MARK_END):
                skipping = False
            continue
        cleaned.append(line)
    base = "".join(cleaned)
    nl = base.find("\n")
    pos = nl + 1 if nl >= 0 else 0
    if not base.startswith("#!"):
        raise SystemExit("开机脚本首行不是 shebang，拒绝注入（请人工确认）")
    final = base[:pos] + snippet + base[pos:]
    return final, final != text


def dry_run(offline_script):
    src = offline_script
    if not src:
        for p in OFFLINE_DEFAULTS:
            if os.path.exists(p):
                src = p
                break
    if not src or not os.path.exists(src):
        raise SystemExit("找不到离线开机脚本，用 --offline-script 指定")
    text = read_text(src)
    snippet = read_text(os.path.join(HERE, "post_boot.snippet"))
    if not snippet.endswith("\n"):
        snippet += "\n"
    final, changed = build_post_boot(text, snippet)
    again, changed2 = build_post_boot(final, snippet)

    os.makedirs(SNAPSHOTS, exist_ok=True)
    out = os.path.join(SNAPSHOTS, "init.qcom.post_boot.jz02.injected.sh")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(final)

    print("=== DRY RUN (完全离线，未连接设备) ===")
    print("输入脚本       : %s" % src)
    print("原始行数/字节  : %d / %d" % (len(text.splitlines()), len(text)))
    print("片段行数/字节  : %d / %d" % (len(snippet.splitlines()), len(snippet)))
    print("注入后行数/字节: %d / %d" % (len(final.splitlines()), len(final)))
    print("插入位置       : shebang 之后（第 2 行）")
    print("首行仍是 shebang: %s" % final.startswith("#!"))
    print("BEGIN 计数     : %d (应为 1)" % final.count(MARK_BEGIN))
    print("END 计数       : %d (应为 1)" % final.count(MARK_END))
    print("首次有改动     : %s" % changed)
    print("幂等复跑无变化 : %s" % (not changed2))
    print("已写出         : %s" % out)
    ok = (final.startswith("#!") and final.count(MARK_BEGIN) == 1
          and final.count(MARK_END) == 1 and changed and not changed2)
    print("结论           : %s" % ("OK - 注入逻辑正确" if ok else "FAIL - 需检查"))
    return 0 if ok else 1


def panel_api(dev, path, timeout=12):
    """经 adb forward 从宿主机访问面板（不依赖设备网络是否可达）。"""
    import base64
    import urllib.request
    fwd = "tcp:%d" % PANEL_FWD_PORT
    dev.run(["forward", fwd, "tcp:8080"], timeout=30)
    try:
        op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        auth = "Basic " + base64.b64encode(b"admin:admin").decode()
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (PANEL_FWD_PORT, path),
                                     headers={"Authorization": auth})
        with op.open(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    finally:
        dev.run(["forward", "--remove", fwd], timeout=30)


def deploy_panel(dev):
    """安装/启动 8080 面板并预置 JZ02 配置（幂等，不覆盖用户改过的配置）。"""
    print("\n== 7/7 smsrelay 面板（APK / 配置 / 解除 stopped） ==")
    out, _, _ = dev.sh("pm list packages %s" % PANEL_PKG)
    if PANEL_PKG not in out:
        if not os.path.exists(PANEL_APK):
            raise SystemExit("未安装 %s，且找不到 APK：%s" % (PANEL_PKG, PANEL_APK))
        o, e, rc = dev.run(["install", "-r", PANEL_APK], timeout=180)
        print("  install: " + (o + e).strip()[:160])
        if rc != 0 or "Success" not in (o + e):
            raise SystemExit("APK 安装失败")
    else:
        print("  APK 已安装，跳过 install")

    cur, _, _ = dev.sh("cat %s 2>/dev/null" % PANEL_CONF_DEVICE)
    if PANEL_TOKEN in cur and '"helper"' in cur:
        print("  /sdcard/smsrelay.conf 已是 JZ02 配置，保留（不覆盖用户改动）")
    else:
        if cur.strip():
            dev.sh("cp %s %s.pre-jz02" % (PANEL_CONF_DEVICE, PANEL_CONF_DEVICE))
            print("  原配置已备份为 .pre-jz02")
        dev.push(PANEL_CONF, PANEL_CONF_DEVICE)
        dev.sh("chmod 666 %s" % PANEL_CONF_DEVICE)
        print("  已预置 JZ02 配置（helper 顶层 / channels 非空）")

    # Android 4.4：新装包 stopped=true，收不到 BOOT_COMPLETED；显式启动一次解除。
    o, _, _ = dev.sh("am start -n %s/.MainActivity" % PANEL_PKG)
    print("  am start: " + o.strip()[:120])
    dev.sh("am startservice -n %s/.RelayService" % PANEL_PKG)
    import time
    for _ in range(10):
        time.sleep(2)
        ports, _, _ = dev.sh("netstat -ltn")
        if ":8080" in ports:
            break
    print("  面板启动完成")


def verify_panel(dev):
    print("\n== 校验：smsrelay 8080 面板 ==")
    ok = True

    def ck(name, cond, detail=""):
        nonlocal ok
        print(("  [OK]   " if cond else "  [FAIL] ") + name
              + ("  " + detail if detail else ""))
        if not cond:
            ok = False

    ports, _, _ = dev.sh("netstat -ltn")
    ck("8080 监听", ":8080" in ports)
    state, _, _ = dev.sh("pm dump %s 2>/dev/null | grep -i 'User 0:'" % PANEL_PKG)
    ck("包非 stopped", "stopped=false" in state, state.strip()[:110])
    conf, _, _ = dev.sh("cat %s 2>/dev/null" % PANEL_CONF_DEVICE)
    ck("配置含 JZ02 token", PANEL_TOKEN in conf)
    try:
        st = panel_api(dev, "/api/status")
        ck("/api/status", st.get("ok") is True
           and st.get("panel", {}).get("running") is True,
           "device=%s port=%s" % (st.get("device"), st.get("panel", {}).get("port")))
        hp = panel_api(dev, "/api/maint/helper")
        ck("helper root 通道", hp.get("ok") is True
           and "uid=0(root)" in hp.get("uid", "")
           and hp.get("token") == PANEL_TOKEN,
           "uid=%s token=%s" % (hp.get("uid", "")[:40], hp.get("token")))
        # /api/health 是附加诊断（经 helper 跑 shell 体检），低内存 / 开机窗口
        # 内可能超时，或暂报 SIM ABSENT、memOk=false（裸 MemFree < 20MB 阈值，
        # 但 cache 可回收）。不作为硬性断言，只打印供人工参考。
        try:
            hd = panel_api(dev, "/api/health", timeout=20)
            cks = hd.get("checks", {})
            print("  [INFO] /api/health healthy=%s memOk=%s issues=%s"
                  % (hd.get("healthy"), cks.get("memOk"), hd.get("issues")))
        except Exception as e:
            print("  [INFO] /api/health 暂不可用（非致命）: %s" % str(e)[:120])
    except Exception as e:
        ck("面板 HTTP API", False, str(e)[:160])
    return ok


def check_root(dev):
    out, err, rc = dev.sh("id")
    if "uid=0(root)" not in out:
        raise SystemExit(
            "adb shell 不是 root（%s）。\n"
            "请先用 MiKo 刷入 ja02-fullbak-hardened-rooted-aboot-v6.bin（v5 root adbd），\n"
            "参见 tools/jz02/flash-and-root.md" % out.strip())
    print("[root] uid=0(root) 已确认")


def deploy(dev):
    print("\n== 1/7 remount /system ==")
    out, _, rc = dev.run(["remount"], timeout=60)
    print("  " + (out + "").strip()[:120])
    dev.sh("mount -o remount,rw /system 2>/dev/null; mount | grep ' /system '")

    print("\n== 2/7 busybox -> /system/xbin ==")
    dev.sh("mkdir -p /system/xbin")
    dev.push(os.path.join(HERE, "busybox"), "/data/local/tmp/busybox-jz02")
    dev.sh("cp /data/local/tmp/busybox-jz02 %s; chmod 755 %s; rm -f /data/local/tmp/busybox-jz02" % (BB, BB))
    cnt, _, _ = dev.sh("%s --list | grep -c ." % BB)
    print("  applets = %s" % cnt.strip())
    if not cnt.strip().isdigit() or int(cnt.strip()) < 50:
        raise SystemExit("busybox 不可用（可能架构不匹配）")

    print("\n== 3/7 /system/etc/passwd（crond 需要 getpwnam(root)） ==")
    has, _, _ = dev.sh("[ -f /system/etc/passwd ] && echo YES || echo NO")
    if "YES" in has:
        print("  已存在，保留原样")
    else:
        dev.push(os.path.join(HELPER, "passwd"), "/system/etc/passwd")
        dev.sh("chmod 644 /system/etc/passwd")
        print("  已补齐")

    print("\n== 4/7 push relay-helper（JZ02 token） ==")
    dev.sh("mkdir -p %s/cgi-bin %s/bin /data/local/tmp/relay-helper" % (DEVICE_ROOT, DEVICE_ROOT))
    for rel, remote, mode in FILES:
        dev.push(os.path.join(HELPER, rel), "%s/%s" % (DEVICE_ROOT, remote))
        dev.sh("chmod %o %s/%s" % (mode, DEVICE_ROOT, remote))
        print("  %-18s -> %s (%o)" % (rel, remote, mode))
    dev.push(os.path.join(HERE, "token"), "%s/token" % DEVICE_ROOT)
    dev.sh("chmod 600 %s/token" % DEVICE_ROOT)
    print("  token(JZ02)      -> token (600)")

    print("\n== 5/7 注入 init.qcom.post_boot.sh（shebang 之后，幂等） ==")
    local = os.path.join(tempfile.gettempdir(), "jz02-post_boot.orig")
    dev.run(["pull", POST_BOOT, local], timeout=60)
    if not os.path.exists(local):
        raise SystemExit("读不到设备侧开机脚本")
    dev.push(local, "/data/local/tmp/jz02_post_boot.orig") if False else None
    text = read_text(local)
    snippet = read_text(os.path.join(HERE, "post_boot.snippet"))
    if not snippet.endswith("\n"):
        snippet += "\n"
    final, changed = build_post_boot(text, snippet)
    os.makedirs(SNAPSHOTS, exist_ok=True)
    with open(os.path.join(SNAPSHOTS, "init.qcom.post_boot.device.sh"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(final)
    if changed:
        staged = os.path.join(tempfile.gettempdir(), "jz02-post_boot.new")
        with open(staged, "w", encoding="utf-8", newline="\n") as f:
            f.write(final)
        dev.sh("[ -f %s.pre-helper ] || cp %s %s.pre-helper" % (POST_BOOT, POST_BOOT, POST_BOOT))
        dev.push(staged, "/data/local/tmp/jz02_post_boot.new")
        dev.sh("cat /data/local/tmp/jz02_post_boot.new > %s; chmod 755 %s; "
               "rm -f /data/local/tmp/jz02_post_boot.new" % (POST_BOOT, POST_BOOT))
        print("  已注入（原文件备份为 .pre-helper），快照存入 snapshots/")
    else:
        print("  已包含 autostart 块，无需改动")

    print("\n== 6/7 立即拉起 helper ==")
    dev.sh("for f in /data/local/tmp/relay-helper/start.pid "
           "/data/local/tmp/relay-helper/httpd.pid "
           "/data/local/tmp/relay-helper/crond.pid; do "
           "[ -f \"$f\" ] && kill $(cat \"$f\") 2>/dev/null; done; "
           "rm -f /data/local/tmp/relay-helper/*.pid; sleep 1")
    dev.sh("%s setsid /system/bin/sh %s/start.sh >/dev/null 2>&1 </dev/null & echo LAUNCHED"
           % (BB, DEVICE_ROOT))
    print("  已发出启动命令")
    deploy_panel(dev)


def verify(dev):
    print("\n== 校验 ==")
    token = read_text(os.path.join(HERE, "token")).strip()
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        print(("  [OK]   " if cond else "  [FAIL] ") + name + ("  " + detail if detail else ""))
        if not cond:
            ok = False

    ping, _, _ = dev.sh("%s wget -q -T 4 -O - http://127.0.0.1:8899/ping.txt" % BB, timeout=20)
    check("httpd /ping.txt", "pong" in ping, ping.strip()[:60])

    ident, _, _ = dev.sh("%s wget -q -T 8 -O - --post-data='id' "
                         "'http://127.0.0.1:8899/cgi-bin/cmd?t=%s'" % (BB, token), timeout=30)
    check("命令通道 uid=0", "uid=0(root)" in ident and "[exit=0]" in ident, ident.strip()[:120])

    bad, _, _ = dev.sh("%s wget -q -T 8 -O - --post-data='id' "
                       "'http://127.0.0.1:8899/cgi-bin/cmd?t=WRONG'" % BB, timeout=30)
    check("错误 token 被拒", "exit=77" in bad, bad.strip()[:80])

    ports, _, _ = dev.sh("netstat -ltn")
    lines = [l.strip() for l in ports.splitlines() if ":8899" in l]
    check("8899 只听回环", len(lines) > 0 and all("127.0.0.1:8899" in l for l in lines),
          " | ".join(lines)[:140])
    occupied = [l.strip() for l in ports.splitlines() if ":8080" in l]
    print("  [INFO] 8080 监听: %s" % (" | ".join(occupied)[:140] if occupied else "(空，JZ02 面板可占用)"))

    boot, _, _ = dev.sh("grep -c 'relay-helper autostart BEGIN' %s" % POST_BOOT)
    check("开机脚本已注入", boot.strip() == "1", "计数=" + boot.strip())

    proc, _, _ = dev.sh("p=$(cat /data/local/tmp/relay-helper/start.pid 2>/dev/null); "
                        "[ -n \"$p\" ] && [ -d /proc/$p ] && echo ALIVE || echo DEAD")
    check("看门狗在跑", "ALIVE" in proc, proc.strip()[:60])

    crond, _, _ = dev.sh("p=$(cat /data/local/tmp/relay-helper/crond.pid 2>/dev/null); "
                         "[ -n \"$p\" ] && [ -d /proc/$p ] && echo ALIVE || echo DEAD")
    check("crond 在跑", "ALIVE" in crond, crond.strip()[:60])
    return ok and verify_panel(dev)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb")
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--server", default=DEFAULT_SERVER)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--offline-script")
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--reboot", action="store_true")
    args = ap.parse_args()

    if args.dry_run:
        return dry_run(args.offline_script)

    dev = Dev(find_adb(args.adb), args.serial, args.server)
    dev.connect()
    check_root(dev)
    if not args.verify_only:
        deploy(dev)
    good = verify(dev)
    if not good:
        print("\n校验未全绿，先别重启。排查后重跑 --verify-only")
        return 1
    if args.reboot:
        print("\n== 热重启验证开机自启 ==")
        dev.sh("reboot")
        import time
        time.sleep(45)
        for _ in range(12):
            try:
                dev.connect()
                out, _, _ = dev.sh("getprop sys.boot_completed", timeout=15)
                if "1" in out:
                    break
            except Exception:
                pass
            time.sleep(10)
        dev.connect()
        reboot_ok = verify(dev)
        print("重启后校验：" + ("全绿" if reboot_ok else "有失败项"))
        if not reboot_ok:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
