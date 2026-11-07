#!/usr/bin/env python3
"""部署 relay-helper 到 SP970（可重放）。

用法（主机侧，Windows / Linux 均可）：
    python tools/deploy-helper.py                 # 部署 + 立即拉起 + 校验
    python tools/deploy-helper.py --reboot       # 再加一次热重启，验证开机自启
    python tools/deploy-helper.py --verify-only  # 只跑校验，不改设备
    python tools/deploy-helper.py --serial 192.168.42.129:5555 --adb D:/adb/adb.exe

做的事：
  1. 把 busybox 从 /data/local/tmp 复制进 /system/xbin（/data 带 nosuid 且可能被清）
  2. push helper 文件到 /system/etc/relay-helper，设权限
  3. 幂等地把 post_boot.snippet 插到 init.qcom.post_boot.sh 的
     「setprop service.adb.tcp.port 5555」之前（长循环之后的代码永远跑不到）
  4. 把最终版开机脚本存回仓库 tools/relay-helper/init.qcom.post_boot.sh
  5. 立即拉起 helper，校验 ping / uid=0 / 只听回环
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HELPER = os.path.join(ROOT, "tools", "relay-helper")
SERIAL_DEFAULT = "192.168.42.129:5555"
DEVICE_ROOT = "/system/etc/relay-helper"
BB = "/system/xbin/busybox"
MARK_BEGIN = "# --- relay-helper autostart BEGIN"
MARK_END = "# --- relay-helper autostart END ---"

FILES = [
    ("cgi-bin/cmd", "cgi-bin/cmd", 0o755),
    ("cgi-bin/get", "cgi-bin/get", 0o755),
    ("start.sh", "start.sh", 0o755),
    ("ping.txt", "ping.txt", 0o644),
    ("token", "token", 0o600),
    ("crontab", "crontab", 0o644),
    ("bin/health.sh", "bin/health.sh", 0o755),
    ("bin/logsnap.sh", "bin/logsnap.sh", 0o755),
    ("bin/watchdog-8080.sh", "bin/watchdog-8080.sh", 0o755),
]


def find_adb(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get("ADB")
    if env:
        return env
    for p in [
        r"G:\PCSUITE\adb\adb.exe",
        r"F:\wifi\sp970\adb\platform-tools\adb.exe",
        r"D:\platform-tools\adb.exe",
    ]:
        if os.path.exists(p):
            return p
    return shutil.which("adb") or "adb"


class Dev:
    def __init__(self, adb, serial, server="tcp:5039"):
        self.adb = adb
        self.serial = serial
        self.server = server

    def base(self):
        # 主机上同时存在 v40 / v41 两个 adb，混用会互杀 server。
        # 统一走诊断脚本在用的 tcp:5039，避免 "server version doesn't match"。
        return [self.adb, "-L", self.server]

    def run(self, args, timeout=60, raw=False):
        cmd = self.base() + ["-s", self.serial] + list(args)
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout)
        out = p.stdout if raw else p.stdout.decode("utf-8", "replace")
        err = p.stderr.decode("utf-8", "replace")
        return out, err, p.returncode

    def sh(self, command, timeout=60, raw=False):
        return self.run(["shell", command], timeout=timeout, raw=raw)

    def connect(self):
        p = subprocess.run(self.base() + ["connect", self.serial],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        txt = (p.stdout + p.stderr).decode("utf-8", "replace").strip()
        print("[adb] " + txt)
        out, _, _ = self.run(["devices"])
        if self.serial not in str(out):
            raise SystemExit("设备没连上：%s" % self.serial)
        # adb server 刚被拉起时，第一条 shell 命令偶发返回空输出（实测），先热身
        for _ in range(3):
            out, _, rc = self.run(["shell", "echo WARMUP"], timeout=20)
            if rc == 0 and "WARMUP" in str(out):
                break


def stage_path(name):
    return os.path.join(tempfile.gettempdir(), "sp970-" + name)


def patch_post_boot(dev):
    """返回 (最终脚本文本, 是否改动)。幂等：已有块就先删再插。"""
    local = stage_path("post_boot.orig")
    out, err, rc = dev.run(["pull", "/system/etc/init.qcom.post_boot.sh", local], timeout=60)
    if rc != 0 or not os.path.exists(local):
        raise SystemExit("读不到开机脚本：%s %s %s" % (rc, err, out))
    with open(local, "rb") as f:
        raw = f.read()
    text = raw.replace(b"\r\n", b"\n").decode("utf-8", "replace")
    with open(os.path.join(HELPER, "post_boot.snippet"), "r", encoding="utf-8") as f:
        snippet = f.read()
    if not snippet.endswith("\n"):
        snippet += "\n"

    lines = text.splitlines(True)
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

    # JZ02 adaptation DONE
    # SP970: insert before the network-adb line (two long loops follow, so code
    # after them would never run). JZ02 (UFI / richal) has NO such anchor and
    # its 728-line post_boot is purely sequential -> insert after the shebang.
    idx = base.find("setprop service.adb.tcp.port 5555")
    if idx >= 0:
        line_start = base.rfind("\n", 0, idx) + 1
        print("[post_boot] SP970 anchor found")
    else:
        nl = base.find("\n")
        line_start = nl + 1 if nl >= 0 else 0
        if not base.startswith("#!"):
            raise SystemExit("开机脚本首行不是 shebang，拒绝注入（请人工确认）")
        print("[post_boot] SP970 anchor missing -> JZ02 mode: insert after shebang")
    final = base[:line_start] + snippet + base[line_start:]

    # 版本化：把设备侧「配置真源」存回仓库
    versioned = os.path.join(HELPER, "init.qcom.post_boot.sh")
    with open(versioned, "w", encoding="utf-8", newline="\n") as f:
        f.write(final)
    print("[repo] 已保存开机脚本快照 %s" % versioned)
    return final, final != text


def deploy(dev):
    print("\n== 1/5 busybox 落到 /system/xbin ==")
    out, err, rc = dev.sh("ls -l /data/local/tmp/busybox")
    if rc != 0 or "busybox" not in out:
        raise SystemExit("设备上没有 /data/local/tmp/busybox")
    dev.sh("cp /data/local/tmp/busybox %s; chmod 755 %s; %s --list > /dev/null && echo BB_OK"
           % (BB, BB, BB))
    print("  " + dev.sh("%s --list | grep -c httpd" % BB)[0].strip() + " (httpd applet 计数，应为 1)")

    # Android 原厂不带 /etc/passwd，bionic getpwnam("root") 会失败，
    # busybox crond 因此直接 "ignoring file 'root' (no such user)"。补一条最小记录。
    has, _, _ = dev.sh("[ -f /system/etc/passwd ] && echo YES || echo NO")
    if "YES" not in has:
        pw = os.path.join(HELPER, "passwd")
        dev.run(["push", pw, "/system/etc/passwd"], timeout=60)
        dev.sh("chmod 644 /system/etc/passwd")
        print("  已补 /system/etc/passwd（crond 需要 getpwnam(root)）")
    else:
        print("  /system/etc/passwd 已存在，保留原样")

    print("\n== 2/5 push helper 文件 ==")
    dev.sh("mkdir -p %s/cgi-bin %s/bin /data/local/tmp/relay-helper" % (DEVICE_ROOT, DEVICE_ROOT))
    for rel, remote, mode in FILES:
        src = os.path.join(HELPER, rel)
        dev.run(["push", src, "%s/%s" % (DEVICE_ROOT, remote)], timeout=90)
        dev.sh("chmod %o %s/%s" % (mode, DEVICE_ROOT, remote))
        print("  %-18s -> %s/%s (%o)" % (rel, DEVICE_ROOT, remote, mode))

    print("\n== 3/5 改 init.qcom.post_boot.sh（幂等） ==")
    final, changed = patch_post_boot(dev)
    if changed:
        staged = stage_path("post_boot.new")
        with open(staged, "w", encoding="utf-8", newline="\n") as f:
            f.write(final)
        dev.sh("cp /system/etc/init.qcom.post_boot.sh /system/etc/init.qcom.post_boot.sh.pre-helper")
        dev.run(["push", staged, "/data/local/tmp/post_boot.new"], timeout=60)
        dev.sh("cat /data/local/tmp/post_boot.new > /system/etc/init.qcom.post_boot.sh; "
               "chmod 755 /system/etc/init.qcom.post_boot.sh; "
               "rm -f /data/local/tmp/post_boot.new")
        print("  已插入 helper 启动块（原文件备份为 .pre-helper）")
    else:
        print("  开机脚本已包含 helper 启动块，无需改动")

    print("\n== 4/5 立即拉起 helper（先收掉旧实例） ==")
    dev.sh("for f in /data/local/tmp/relay-helper/start.pid "
           "/data/local/tmp/relay-helper/httpd.pid "
           "/data/local/tmp/relay-helper/crond.pid; do "
           "[ -f \"$f\" ] && kill $(cat \"$f\") 2>/dev/null; done; "
           "rm -f /data/local/tmp/relay-helper/start.pid "
           "/data/local/tmp/relay-helper/httpd.pid "
           "/data/local/tmp/relay-helper/crond.pid; sleep 1")
    # 设备 toolbox 没有 nohup / setsid 命令，只能用 busybox applet，并把三个 fd 全部重定向，
    # 否则后台进程会一直占着 adb shell 的 stdout，adb 命令不返回。
    dev.sh("%s setsid /system/bin/sh %s/start.sh >/dev/null 2>&1 </dev/null & echo LAUNCHED"
           % (BB, DEVICE_ROOT))
    print("  已发出启动命令")


def verify(dev):
    print("\n== 5/5 校验 ==")
    token = open(os.path.join(HELPER, "token"), "r", encoding="utf-8").read().strip()
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        print(("  [OK]   " if cond else "  [FAIL] ") + name + ("  " + detail if detail else ""))
        if not cond:
            ok = False

    ping, _, _ = dev.sh("%s wget -q -T 4 -O - http://127.0.0.1:8899/ping.txt" % BB, timeout=20)
    check("httpd 存活 /ping.txt", "pong" in ping)

    ident, _, _ = dev.sh(
        "%s wget -q -T 8 -O - --post-data='id' "
        "'http://127.0.0.1:8899/cgi-bin/cmd?t=%s'" % (BB, token), timeout=30)
    check("命令通道 id 返回 uid=0", "uid=0(root)" in ident and "[exit=0]" in ident,
          ident.strip()[:140])

    bad, _, _ = dev.sh(
        "%s wget -q -T 8 -O - --post-data='id' "
        "'http://127.0.0.1:8899/cgi-bin/cmd?t=WRONG'" % BB, timeout=30)
    check("错误 token 被拒绝", "exit=77" in bad, bad.strip()[:80])

    ports, _, _ = dev.sh("netstat -ltn")
    lines = [l.strip() for l in ports.splitlines() if ":8899" in l]
    check("8899 只在回环", len(lines) > 0 and all("127.0.0.1:8899" in l for l in lines),
          " | ".join(lines)[:160])

    boot, _, _ = dev.sh("grep -c 'relay-helper autostart BEGIN' /system/etc/init.qcom.post_boot.sh")
    check("开机脚本已注入", boot.strip() == "1", "计数=" + boot.strip())

    # 4.4 的 ps 只显示 "/system/bin/sh"，看不到脚本路径，改用 pidfile + /proc
    proc, _, _ = dev.sh("p=$(cat /data/local/tmp/relay-helper/start.pid 2>/dev/null); "
                        "[ -n \"$p\" ] && [ -d /proc/$p ] && echo ALIVE || echo DEAD")
    check("看门狗进程在跑", "ALIVE" in proc, proc.strip()[:60])

    crond, _, _ = dev.sh("p=$(cat /data/local/tmp/relay-helper/crond.pid 2>/dev/null); "
                         "[ -n \"$p\" ] && [ -d /proc/$p ] && echo ALIVE || echo DEAD")
    check("crond 在跑（定时任务）", "ALIVE" in crond, crond.strip()[:60])
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb")
    ap.add_argument("--serial", default=SERIAL_DEFAULT)
    ap.add_argument("--reboot", action="store_true", help="部署完成后热重启一次")
    ap.add_argument("--verify-only", action="store_true", help="只校验，不改设备")
    args = ap.parse_args()

    adb = find_adb(args.adb)
    dev = Dev(adb, args.serial)
    dev.connect()
    if not args.verify_only:
        deploy(dev)
    good = verify(dev)
    if not good:
        print("\n校验未全绿，先别重启。排查后再跑一次 --verify-only")
        return 1
    if args.reboot:
        print("\n== 热重启一次，验证开机自启 ==")
        dev.sh("reboot")
        print("  已发送 reboot；等 60s 后重新连接并校验")
        import time
        time.sleep(45)
        for _ in range(12):
            try:
                subprocess.run(dev.base() + ["connect", args.serial],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
                out, _, rc = dev.sh("getprop sys.boot_completed", timeout=15)
                if "1" in out:
                    break
            except Exception:
                pass
            time.sleep(10)
        dev.connect()
        good = verify(dev)
        print("\n重启后校验：" + ("全绿" if good else "有失败项"))
        return 0 if good else 1
    print("\n部署完成（未重启）。需要验证开机自启就加 --reboot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
