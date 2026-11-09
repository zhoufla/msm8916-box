#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JZ02 射频/短信链路快照与前后对比（只读，走 8080 面板）。

用途：QPST 改 modem NV 的 Preferred Band（启用 GSM 900/1800）前后各采一次，
判断设备是否真的离开 LTE、CS 域是否仍在服务、短信回路有没有被拖坏。

    python3 tools/jz02/rf-check.py snapshot --save before.json
    # ... QPST 写入 NV -> 整机 reboot -> 等 2 分钟 ...
    python3 tools/jz02/rf-check.py snapshot --save after.json
    python3 tools/jz02/rf-check.py compare before.json after.json
    python3 tools/jz02/rf-check.py watch --count 12 --interval 5

默认 192.168.10.174:8080 / admin:admin，与 panel-e2e.sh 一致；
可用 --host / --auth 或环境变量 RELAY_BASE / AUTH 覆盖。
全程只读：不改 settings、不改属性、不重启设备。
"""
import argparse
import base64
import json
import os
import re
import sys
import time
import urllib.request

RAT = {
    0: "UNKNOWN", 1: "GPRS", 2: "EDGE", 3: "UMTS", 4: "IS95A", 5: "IS95B",
    6: "1xRTT", 7: "EVDO_0", 8: "EVDO_A", 9: "HSDPA", 10: "HSUPA",
    11: "HSPA", 12: "EVDO_B", 13: "EHRPD", 14: "LTE", 15: "HSPAP",
    16: "GSM", 17: "TD_SCDMA", 18: "IWLAN", 19: "LTE_CA",
}
VOICE_STATE = {0: "IN_SERVICE", 1: "OUT_OF_SERVICE", 2: "EMERGENCY_ONLY", 3: "POWER_OFF"}
NETMODE = {
    0: "WCDMA_PREF", 1: "GSM_ONLY", 2: "WCDMA_ONLY", 3: "GSM_UMTS_AUTO",
    4: "CDMA", 5: "CDMA_NO_EVDO", 6: "EVDO_NO_CDMA", 7: "GLOBAL",
    8: "LTE_CDMA_EVDO", 9: "LTE_GSM_WCDMA", 10: "LTE_ALL",
    11: "LTE_ONLY", 12: "LTE_WCDMA",
}

SHELL = (
    "echo ---mode---; settings get global preferred_network_mode; "
    "echo ---defaultnet---; getprop ro.telephony.default_network; "
    "echo ---nettype---; getprop gsm.network.type; "
    "echo ---sim---; getprop gsm.sim.state; "
    # 46002 是 SIM 的 HPLMN(IMSI 前 5 位)，不是注册网络；两者必须分开采，
    # 否则会把「卡不同」误读成「驻留网络不同」（2026-11-09）。
    "echo ---simnumeric---; getprop gsm.sim.operator.numeric; "
    "echo ---netnumeric---; getprop gsm.operator.numeric; "
    "echo ---roaming---; getprop gsm.operator.isroaming; "
    "echo ---simslot---; getprop persist.jz02.sim.slot; "
    "echo ---defaultapp---; settings get secure sms_default_application; "
    "echo ---service---; dumpsys telephony.registry | grep mServiceState; "
    "echo ---voice---; logcat -d -b radio | grep VOICE_REGISTRATION_STATE; "
    "echo ---data---; logcat -d -b radio | grep DATA_REGISTRATION_STATE; "
    "echo ---cells---; logcat -d -b radio | grep getAllCellInfo; "
    "echo ---sms---; content query --uri content://sms "
    "--projection _id:date:address --sort 'date DESC'"
)


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

    def shell(self, cmd, timeout=40):
        req = urllib.request.Request(
            self.base + "/api/maint/cmd",
            data=json.dumps({"cmd": cmd}).encode(),
            headers=self.header,
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
        if not d.get("ok"):
            raise RuntimeError("面板命令失败: %s" % d.get("message"))
        return d.get("out") or ""


def sections(out):
    """把 echo ---key--- 分段命令的输出切成 dict。"""
    res, key = {}, None
    for line in out.splitlines():
        m = re.match(r"^---([a-z]+)---$", line.strip())
        if m:
            key = m.group(1)
            res[key] = []
        elif key:
            res[key].append(line.strip())
    return {k: [x for x in v if x] for k, v in res.items()}


def parse_service(line):
    """mServiceState=0 0 home CMCC CMCC 46000 LTE LTE ..."""
    if not line or "mServiceState=" not in line:
        return {}
    parts = line.split("mServiceState=", 1)[1].split()
    if len(parts) < 8:
        return {"raw": line}
    try:
        v, d = int(parts[0]), int(parts[1])
    except ValueError:
        return {"raw": line}
    return {
        "voice_state": v,
        "voice_state_text": VOICE_STATE.get(v, str(v)),
        "data_state": d,
        "data_state_text": VOICE_STATE.get(d, str(d)),
        "operator": parts[5] if len(parts) > 5 else "",
        "voice_rat": parts[6],
        "data_rat": parts[7],
    }


def parse_reg(lines, tag):
    """从 < VOICE_REGISTRATION_STATE {1, 33d4, 03ab4541, 14, ...} 取 rat。"""
    hits = [l for l in lines if "<" in l and tag in l]
    if not hits:
        return {}
    body = hits[-1].split("{", 1)[1].split("}", 1)[0] if "{" in hits[-1] else ""
    fields = [x.strip() for x in body.split(",")]
    if len(fields) < 4:
        return {"raw": hits[-1]}
    try:
        rat = int(fields[3])
    except ValueError:
        return {"raw": hits[-1]}
    return {"reg_state": fields[0], "rat": rat, "rat_text": RAT.get(rat, str(rat)), "raw": hits[-1]}


def parse_cells(lines):
    """从 GsmSST.getAllCellInfo() 的 X size=N list=[...] 拆出服务小区/邻区。

    CellInfoLte:{mRegistered=YES ... mPci=135 ...} CellSignalStrengthLte:
    ss=18 rsrp=-116 rsrq=-18 ...
    """
    hits = [l for l in lines if "mRegistered=" in l]
    if not hits:
        return []
    line = hits[-1]
    out = []
    for block in line.split("CellInfo")[1:]:
        m = re.search(r"mRegistered=(YES|NO)", block)
        pci = re.search(r"mPci=(-?\d+)", block)
        rsrp = re.search(r"rsrp=(-?\d+)", block)
        rsrq = re.search(r"rsrq=(-?\d+)", block)
        if not (m and pci):
            continue
        out.append({
            "registered": m.group(1) == "YES",
            "pci": int(pci.group(1)),
            "rsrp": int(rsrp.group(1)) if rsrp else None,
            "rsrq": int(rsrq.group(1)) if rsrq else None,
        })
    return out


def snapshot(p):
    sec = sections(p.shell(SHELL))
    svc = parse_service((sec.get("service") or [""])[0])
    voice = parse_reg(sec.get("voice") or [], "VOICE_REGISTRATION_STATE")
    data = parse_reg(sec.get("data") or [], "DATA_REGISTRATION_STATE")
    mode_raw = (sec.get("mode") or [""])[0]
    try:
        mode = int(mode_raw)
    except ValueError:
        mode = mode_raw
    sms_rows = [x for x in (sec.get("sms") or []) if x.startswith("Row:")]

    try:
        sy = p.get("/api/sys")
    except Exception:
        sy = {}
    try:
        rd = p.get("/api/radio")
    except Exception:
        rd = {}
    try:
        st = p.get("/api/status")
    except Exception:
        st = {}
    try:
        ck = p.get("/api/maint/checkup")
    except Exception:
        ck = {}

    return {
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "mode": mode,
        "mode_text": NETMODE.get(mode, str(mode)) if isinstance(mode, int) else str(mode),
        "default_network": (sec.get("defaultnet") or [""])[0],
        "gsm_network_type": (sec.get("nettype") or [""])[0],
        "sim_state": (sec.get("sim") or [""])[0],
        "sms_default_app": (sec.get("defaultapp") or [""])[0],
        "service": svc,
        "voice_reg": voice,
        "data_reg": data,
        "signal": sy.get("signal") or {},
        # 只存 IMSI 的 MCC+MNC 与长度，不把完整 IMSI 落盘。
        "subscriber": {
            "imsi_hplmn": (rd.get("imsi") or "")[:5],
            "imsi_len": len(rd.get("imsi") or ""),
            "sim_numeric": rd.get("simNumeric"),
            "registered_plmn": (sec.get("netnumeric") or [""])[0],
            "roaming": (sec.get("roaming") or [""])[0],
            "iccid": rd.get("iccid"),
            "sim_slot": (sec.get("simslot") or [""])[0],
        },
        "cells": parse_cells(sec.get("cells") or []),
        "radio": {
            "mode": rd.get("mode"),
            "netType": rd.get("netType"),
            "simState": rd.get("simState"),
            "simSlots": rd.get("simSlots"),
        },
        "queue": st.get("queue") or {},
        "keepalive_ok": (st.get("keepalive") or {}).get("lastPingOk"),
        "checkup": {"healthy": ck.get("healthy"), "checks": ck.get("checks"), "netType": ck.get("netType")},
        "sms_rows": len(sms_rows),
        "sms_latest": sms_rows[0] if sms_rows else "",
    }


def brief(s):
    svc = s.get("service") or {}
    sig = s.get("signal") or {}
    vr = (s.get("voice_reg") or {}).get("rat_text")
    return "%s voice=%s/%s data=%s rat=%s/%s log_rat=%s rsrp=%s level=%s" % (
        s.get("ts"), svc.get("voice_state_text"), svc.get("data_state_text"),
        svc.get("data_state_text"), svc.get("voice_rat"), svc.get("data_rat"),
        vr or "-", sig.get("rsrp"), sig.get("level"),
    )


def show(s):
    print("[快照 %s]" % s.get("ts"))
    print("  network mode      : %s (%s)  default_network=%s" % (
        s.get("mode"), s.get("mode_text"), s.get("default_network")))
    print("  gsm.network.type  : %s   sim=%s   sms_default=%s" % (
        s.get("gsm_network_type"), s.get("sim_state"), s.get("sms_default_app")))
    sub = s.get("subscriber") or {}
    print("  SIM HPLMN / 注册网 : %s (IMSI %s..., slot=%s) / %s  roaming=%s  iccid=%s"
          "  ← 两者不是一回事，别混读" % (
              sub.get("sim_numeric"), sub.get("imsi_hplmn"), sub.get("sim_slot"),
              sub.get("registered_plmn"), sub.get("roaming"), sub.get("iccid")))
    cells = s.get("cells") or []
    if cells:
        txt = "  ".join("%sPCI%s:%s/%s" % (
            "S" if c["registered"] else "N", c["pci"], c["rsrp"], c["rsrq"]) for c in cells)
        print("  cells (S=服务/N=邻): %s  [rsrp/rsrq]" % txt)
    svc = s.get("service") or {}
    print("  mServiceState     : voice=%s  data=%s  rat=%s/%s  operator=%s" % (
        svc.get("voice_state_text"), svc.get("data_state_text"),
        svc.get("voice_rat"), svc.get("data_rat"), svc.get("operator")))
    print("  RIL VOICE_REG     : %s" % json.dumps(s.get("voice_reg"), ensure_ascii=False))
    print("  RIL DATA_REG      : %s" % json.dumps(s.get("data_reg"), ensure_ascii=False))
    print("  signal            : %s" % json.dumps(s.get("signal"), ensure_ascii=False))
    print("  panel radio       : %s" % json.dumps(s.get("radio"), ensure_ascii=False))
    print("  queue / keepalive : %s / %s" % (json.dumps(s.get("queue"), ensure_ascii=False), s.get("keepalive_ok")))
    print("  checkup           : healthy=%s netType=%s checks=%s" % (
        (s.get("checkup") or {}).get("healthy"), (s.get("checkup") or {}).get("netType"),
        (s.get("checkup") or {}).get("checks")))
    print("  sms db rows       : %d  latest=%s" % (s.get("sms_rows") or 0, s.get("sms_latest")))


def row(name, a, b, note=""):
    mark = "  " if a == b else "* "
    print("  %s%-22s %-28s -> %-28s %s" % (mark, name, str(a)[:28], str(b)[:28], note))


def compare(before, after):
    b, a = before, after
    bs, as_ = b.get("service") or {}, a.get("service") or {}
    bsig, asig = b.get("signal") or {}, a.get("signal") or {}
    bsub, asub = b.get("subscriber") or {}, a.get("subscriber") or {}

    def serving(s):
        for c in (s.get("cells") or []):
            if c.get("registered"):
                return "PCI%s %s/%s" % (c["pci"], c["rsrp"], c["rsrq"])
        return ""

    print("=== 改前 %s  ->  改后 %s ===" % (b.get("ts"), a.get("ts")))
    row("SIM HPLMN (卡)", bsub.get("sim_numeric") or "-", asub.get("sim_numeric") or "-",
        "跟卡走，换卡后应变化；'-' 表示旧版快照无此字段")
    row("registered PLMN", bsub.get("registered_plmn") or "-", asub.get("registered_plmn") or "-",
        "跟网络走，通常恒 46000")
    row("network mode", b.get("mode_text"), a.get("mode_text"))
    row("gsm.network.type", b.get("gsm_network_type"), a.get("gsm_network_type"))
    row("voice state", bs.get("voice_state_text"), as_.get("voice_state_text"), "必须 IN_SERVICE，否则短信 NO_SERVICE")
    row("data state", bs.get("data_state_text"), as_.get("data_state_text"))
    row("voice RAT", bs.get("voice_rat"), as_.get("voice_rat"), "变成非 LTE 才算离开 LTE")
    row("data RAT", bs.get("data_rat"), as_.get("data_rat"))
    row("RIL voice rat", (b.get("voice_reg") or {}).get("rat_text"), (a.get("voice_reg") or {}).get("rat_text"))
    row("RSRP", bsig.get("rsrp"), asig.get("rsrp"))
    row("RSRQ / SINR", bsig.get("rsrq"), asig.get("rsrq"))
    row("serving cell", serving(b), serving(a), "S 小区；PCI 变化说明发生重选")
    row("level / text", "%s/%s" % (bsig.get("level"), bsig.get("text")), "%s/%s" % (asig.get("level"), asig.get("text")))
    row("checkup healthy", (b.get("checkup") or {}).get("healthy"), (a.get("checkup") or {}).get("healthy"))
    row("queue failed", (b.get("queue") or {}).get("failed"), (a.get("queue") or {}).get("failed"), "不应增加")
    row("sms_default_app", b.get("sms_default_app"), a.get("sms_default_app"), "应为 com.sp970.smsrelay")
    print()
    verdict = []
    vr = as_.get("voice_rat")
    if vr and vr != "LTE":
        verdict.append("OK: 话音域已离开 LTE（%s），NV 频段改动生效" % vr)
    else:
        verdict.append("注意: 话音域仍驻留 LTE，NV 写入可能未生效或当地无 2G/3G 覆盖；复查 QPST 读回值")
    if as_.get("voice_state") != 0:
        verdict.append("严重: 话音域 %s，短信会 RESULT_ERROR_NO_SERVICE，建议立刻回滚 NV" % as_.get("voice_state_text"))
    else:
        verdict.append("OK: 话音域 IN_SERVICE，短信链路可用")
    if (a.get("checkup") or {}).get("healthy") is False:
        verdict.append("严重: checkup 不健康，逐项看 checks")
    for v in verdict:
        print("  " + v)


def main():
    ap = argparse.ArgumentParser(description="JZ02 射频/短信链路只读快照与对比")
    ap.add_argument("action", choices=["snapshot", "compare", "watch"])
    ap.add_argument("files", nargs="*", help="compare 用的两份 json")
    ap.add_argument("--host", default=os.environ.get("RELAY_BASE", "192.168.10.174").split(":")[0])
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--auth", default=os.environ.get("AUTH", "admin:admin"))
    ap.add_argument("--save", help="snapshot 另存为 JSON")
    ap.add_argument("--count", type=int, default=6, help="watch 采样次数")
    ap.add_argument("--interval", type=int, default=5, help="watch 间隔秒数")
    a = ap.parse_args()

    if a.action == "compare":
        if len(a.files) != 2:
            print("用法: rf-check.py compare before.json after.json")
            return 2
        with open(a.files[0], encoding="utf-8") as f:
            b = json.load(f)
        with open(a.files[1], encoding="utf-8") as f:
            c = json.load(f)
        compare(b, c)
        return 0

    p = Panel(a.host, a.port, a.auth)
    if a.action == "watch":
        print("=== 连续采样 %d 次，间隔 %ds（Ctrl-C 停止）===" % (a.count, a.interval))
        for i in range(a.count):
            try:
                print("  %02d %s" % (i + 1, brief(snapshot(p))))
            except Exception as e:
                print("  %02d ERR %s" % (i + 1, e))
            if i + 1 < a.count:
                time.sleep(a.interval)
        return 0

    s = snapshot(p)
    show(s)
    if a.save:
        with open(a.save, "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False, indent=2)
        print("\n已保存: %s" % a.save)
    return 0


if __name__ == "__main__":
    sys.exit(main())
