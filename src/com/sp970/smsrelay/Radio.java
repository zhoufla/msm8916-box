package com.sp970.smsrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 移动网络 / 换卡救砖（B2 第 13–17 项）。
 *
 * 和 Maint 一样：这里只负责「拼一条 root 命令 → 解析输出」，特权动作全部经
 * Root.exec → 设备侧 relay-helper。app 仍是普通 uid 10075。
 *
 * 真机实测（SP970-B / 4.4.4）拿到的落地方式：
 *  - APN 读：sqlite3 -separator '|' 直读 telephony.db，比解析 content query 的
 *    "Row: N k=v, k=v" 稳（apn type 里本身带逗号，按逗号切会切坏）
 *  - APN 写：content insert/update/delete，走 provider 才会发变更通知，改完立刻生效
 *  - 首选 APN：content update --uri content://telephony/carriers/preferapn --bind apn_id:i:<id>
 *    （实测当前指向 _id=1602 China Mobile / cmnet）
 *  - 制式：settings put global preferred_network_mode；原厂值是 "22,1" 这种
 *    「模式,后缀」格式，所以写回时保留后缀，别把它抹掉
 *  - ICCID：固件没有 ril.iccid.* 属性，走 app 侧 TelephonyManager.getSimSerialNumber()
 *    （Manifest 已有 READ_PHONE_STATE），不用 root
 *  - 流量：/proc/net/dev 累加 rmnet、wlan，基线存 SharedPreferences，不继承 webkey 的黑盒基线
 */
public final class Radio {

    private static final String BB = "/system/xbin/busybox";
    private static final String DB =
            "/data/data/com.android.providers.telephony/databases/telephony.db";
    private static final String CARRIERS = "content://telephony/carriers";
    private static final String PREFER = "content://telephony/carriers/preferapn";

    private static final String PREFS = "radio";
    private static final String K_BASE_RX = "traffic_base_rx";
    private static final String K_BASE_TX = "traffic_base_tx";
    private static final String K_BASE_UP = "traffic_base_uptime";

    /** SIM 锁判定结果缓存，避免服务循环里反复读 ICCID。 */
    private static volatile String lastSimBlock = "";
    private static volatile boolean simBlocked = false;

    private Radio() {
    }

    // ---------- #13/#14/#15 状态 ----------

    private static final String STATE_CMD =
            "echo '##MODE'; settings get global preferred_network_mode; " +
            "echo '##MODE1'; settings get global preferred_network_mode1; " +
            "echo '##ROAM'; settings get global data_roaming; " +
            "echo '##MOBILE'; settings get global mobile_data; " +
            "echo '##NETTYPE'; getprop gsm.network.type; " +
            "echo '##OPER'; getprop gsm.operator.alpha; " +
            "echo '##NUMSIM'; getprop gsm.sim.operator.numeric; " +
            "echo '##SIM'; getprop gsm.sim.state; " +
            "echo '##PREF'; content query --uri " + PREFER + " --projection _id:name:apn 2>/dev/null; " +
            "echo '##ROUTE'; ip route 2>/dev/null; " +
            "echo '##UPTIME'; cat /proc/uptime";

    /** 面板「网络」页一次拿全：制式 / 数据 / 漫游 / SIM / 首选 APN / 默认路由。 */
    public static JSONObject state(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx, STATE_CMD);
            if (!r.ok() && r.error.length() > 0) throw new IllegalStateException(r.error);
            java.util.Map<String, List<String>> sec = sections(r.out);

            String mode = first(sec.get("MODE"));
            String mode1 = first(sec.get("MODE1"));
            o.put("ok", true);
            o.put("modeRaw", mode);
            o.put("mode", intIn(mode));
            o.put("modeSuffix", suffixOf(mode));
            o.put("mode1", "null".equals(mode1) ? "" : mode1);
            o.put("mode1Value", intIn(mode1));
            o.put("roaming", "1".equals(first(sec.get("ROAM"))));
            o.put("mobileData", "1".equals(first(sec.get("MOBILE"))));
            o.put("netType", first(sec.get("NETTYPE")));
            o.put("operator", first(sec.get("OPER")));
            o.put("simNumeric", first(sec.get("NUMSIM")));
            o.put("simState", first(sec.get("SIM")));
            // 双槽状态数组：gsm.sim.state 逗号分隔（本机 dsds = "READY,ABSENT"）。
            // 空/单值时保证至少有一项，前端按槽序展示。
            JSONArray slots = new JSONArray();
            for (String s : first(sec.get("SIM")).split(",")) {
                String v = s.trim();
                if (v.length() > 0) slots.put(v);
            }
            if (slots.length() == 0) slots.put("未知");
            o.put("simSlots", slots);
            o.put("route", join(sec.get("ROUTE")).trim());
            o.put("iccid", iccid(ctx));
            o.put("iccidRaw", iccidRaw(ctx));
            o.put("imsi", imsi(ctx));
            o.put("simLock", simLock(ctx));
            o.put("modeLabel", modeLabel(intIn(mode)));

            // 首选 APN
            String prefLine = first(sec.get("PREF"));
            o.put("preferredApnId", idOf(prefLine));
            o.put("preferredApn", kvOf(prefLine, "name"));
            o.put("preferredApnApn", kvOf(prefLine, "apn"));

            // 流量
            JSONObject tr = traffic(ctx);
            o.put("traffic", tr);
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /** 制式代号 → 人话。原厂用的是 22（TD/GSM/WCDMA 大组合）+ 后缀 1。 */
    public static String modeLabel(int mode) {
        switch (mode) {
            case 0: return "WCDMA 优先";
            case 1: return "仅 GSM（2G）";
            case 2: return "仅 WCDMA（3G）";
            case 3: return "GSM/WCDMA 自动";
            case 8: return "仅 LTE（4G）";
            case 9: return "GSM/WCDMA/LTE 自动";
            case 11: return "CDMA/EVDO/GSM/WCDMA";
            case 12: return "LTE/WCDMA";
            case 13: return "LTE/GSM/WCDMA";
            case 22: return "TD/GSM/WCDMA 全制式";
            case 23: return "LTE/TD/CDMA/GSM/WCDMA";
            default: return "制式 " + mode;
        }
    }

    // ---------- #13 APN ----------

    private static final String APN_COLS =
            "_id,name,apn,numeric,mcc,mnc,type,user,password,proxy,port,mmsc," +
            "current,preferred,protocol,roaming_protocol,carrier_enabled";

    public static JSONObject apns(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String numeric = simNumeric(ctx);
            String where = numeric.length() > 0 ? " where numeric='" + numeric + "'" : "";
            String cmd = "sqlite3 -separator '|' " + DB +
                    " \"select " + APN_COLS + " from carriers" + where +
                    " order by carrier_enabled desc, _id limit 200\" 2>&1";
            Root.Result r = Root.exec(ctx, cmd);
            if (!r.ok() && r.error.length() > 0) throw new IllegalStateException(r.error);

            JSONArray arr = new JSONArray();
            List<String> lines = splitLines(r.out);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.length() == 0) continue;
                String[] f = line.split("\\|", -1);
                if (f.length < 8) continue;
                JSONObject a = new JSONObject();
                a.put("id", num(f[0]));
                a.put("name", f[1]);
                a.put("apn", f[2]);
                a.put("numeric", f[3]);
                a.put("mcc", f[4]);
                a.put("mnc", f[5]);
                a.put("type", f[6]);
                a.put("user", f[7]);
                a.put("password", f.length > 8 ? f[8] : "");
                a.put("current", num(f.length > 12 ? f[12] : "0"));
                a.put("preferred", num(f.length > 13 ? f[13] : "0"));
                a.put("protocol", f.length > 14 ? f[14] : "");
                a.put("enabled", !"0".equals(f.length > 16 ? f[16] : "1"));
                arr.put(a);
            }
            o.put("ok", true);
            o.put("numeric", numeric);
            o.put("count", arr.length());
            o.put("list", arr);
            o.put("preferredApnId", preferredApnId(ctx));
            o.put("message", "共 " + arr.length() + " 条 APN");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /**
     * 新增 / 修改 APN。id=0 是新增。
     * 走 content provider 而不是直接写 sqlite，改完 TelephonyProvider 会发变更通知，
     * 不用重启 phone 进程就能在设置里看到。
     */
    public static JSONObject apnSave(Context ctx, JSONObject p) {
        JSONObject o = new JSONObject();
        try {
            long id = p.optLong("id", 0);
            String name = clean(p.optString("name", ""), 64);
            String apn = clean(p.optString("apn", ""), 64);
            if (name.length() == 0) throw new IllegalArgumentException("名称不能为空");
            if (apn.length() == 0) throw new IllegalArgumentException("APN 不能为空");

            String numeric = clean(p.optString("numeric", ""), 12);
            if (numeric.length() == 0) numeric = simNumeric(ctx);
            if (!numeric.matches("[0-9]{5,6}")) throw new IllegalArgumentException("numeric 必须是 5–6 位数字");
            String mcc = numeric.substring(0, 3);
            String mnc = numeric.substring(3);

            String type = clean(p.optString("type", "default,supl"), 64);
            String user = clean(p.optString("user", ""), 64);
            String password = clean(p.optString("password", ""), 64);
            String protocol = clean(p.optString("protocol", "IPV4V6"), 12);
            String roamingProtocol = clean(p.optString("roaming_protocol", protocol), 12);
            boolean enabled = p.optBoolean("enabled", true);

            StringBuilder c = new StringBuilder();
            c.append("content ").append(id > 0 ? "update" : "insert")
                    .append(" --uri ").append(CARRIERS);
            if (id > 0) c.append(" --where \"_id=").append(id).append("\"");
            bind(c, "name", "s", name);
            bind(c, "apn", "s", apn);
            bind(c, "numeric", "s", numeric);
            bind(c, "mcc", "s", mcc);
            bind(c, "mnc", "s", mnc);
            bind(c, "type", "s", type);
            bind(c, "user", "s", user);
            bind(c, "password", "s", password);
            bind(c, "protocol", "s", protocol);
            bind(c, "roaming_protocol", "s", roamingProtocol);
            bind(c, "carrier_enabled", "i", enabled ? "1" : "0");
            if (id <= 0) {
                bind(c, "current", "i", "0");
                bind(c, "preferred", "i", "0");
            }
            Root.Result r = Root.exec(ctx, c.toString() + " 2>&1");
            String detail = (r.out + " " + r.error).trim();
            if (detail.length() > 0 && !detail.matches("(?s).*(Row|update|insert|delete).*")) {
                throw new IllegalStateException("写入失败：" + detail);
            }

            long newId = id;
            if (newId <= 0) newId = findApnId(ctx, name);
            o.put("ok", true);
            o.put("id", newId);
            o.put("message", (id > 0 ? "已修改" : "已新增") + " APN：" + name + "（" + apn + "）");
            Logs.add("radio", o.optString("message"));
            if (newId <= 0) o.put("message", "写入完成，但没能回读 id");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    public static JSONObject apnDelete(Context ctx, long id) {
        JSONObject o = new JSONObject();
        try {
            if (id <= 0) throw new IllegalArgumentException("id 无效");
            long pref = preferredApnId(ctx);
            if (pref == id) throw new IllegalArgumentException("这条是当前首选 APN，先把首选切到别的 APN 再删");
            Root.Result r = Root.exec(ctx,
                    "content delete --uri " + CARRIERS + " --where \"_id=" + id + "\" 2>&1");
            String detail = (r.out + " " + r.error).trim();
            long left = countApn(ctx, id);
            o.put("ok", left == 0);
            o.put("message", left == 0 ? "已删除 APN #" + id
                    : ("删除失败" + (detail.length() > 0 ? "：" + detail : "")));
            Logs.add("radio", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /**
     * 切换首选 APN。切完默认把数据连接弹一下（disable → enable），
     * 否则有些 ROM 会一直挂在旧 APN 上，直到下次飞行模式。
     */
    public static JSONObject apnPrefer(Context ctx, long id, boolean reconnect) {
        JSONObject o = new JSONObject();
        try {
            if (id <= 0) throw new IllegalArgumentException("id 无效");
            Root.Result r = Root.exec(ctx,
                    "content update --uri " + PREFER + " --bind apn_id:i:" + id + " 2>&1");
            String detail = (r.out + " " + r.error).trim();
            long now = preferredApnId(ctx);
            if (now != id) {
                throw new IllegalStateException("切换未生效（当前首选 #" + now + "）"
                        + (detail.length() > 0 ? "：" + detail : ""));
            }
            String msg = "首选 APN 已切到 #" + id;
            if (reconnect) {
                Root.exec(ctx, "svc data disable; sleep 3; svc data enable; sleep 2; echo reconnected");
                msg += "，数据连接已重连";
            }
            o.put("ok", true);
            o.put("preferredApnId", now);
            o.put("message", msg);
            Logs.add("radio", msg);
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #14 网络制式 ----------

    /**
     * 改网络制式。原厂值是 "22,1"（模式,后缀），写回时保留后缀。
     * 4.4 的 Phone 对象在开机时读这个全局设置，运行中改不会立刻生效，所以要重启。
     */
    public static JSONObject setMode(Context ctx, int mode, boolean reboot) {
        JSONObject o = new JSONObject();
        try {
            if (mode < 0 || mode > 23) throw new IllegalArgumentException("制式代号要在 0–23");
            String raw = Root.exec(ctx, "settings get global preferred_network_mode").out.trim();
            if (raw.length() == 0) raw = "null";
            String suffix = suffixOf(raw);
            String value = mode + (suffix.length() > 0 ? "," + suffix : "");
            Root.Result r = Root.exec(ctx, "settings put global preferred_network_mode '" + value + "'");
            if (!r.ok()) throw new IllegalStateException("写入失败：" + brief(r));
            String back = Root.exec(ctx, "settings get global preferred_network_mode").out.trim();
            o.put("ok", true);
            o.put("modeRaw", back);
            o.put("mode", mode);
            o.put("modeLabel", modeLabel(mode));
            o.put("reboot", reboot);
            o.put("message", "已写入制式 " + mode + "（" + modeLabel(mode) + "），当前值 " + back
                    + "；4.4 要重启才生效");
            Logs.add("radio", "制式 → " + back);
            if (reboot) {
                Root.exec(ctx, "sleep 1; reboot");
                o.put("message", "已写入制式 " + mode + "，正在重启");
            }
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #15 数据 / 漫游 ----------

    public static JSONObject setData(Context ctx, boolean on) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx, "svc data " + (on ? "enable" : "disable")
                    + "; settings put global mobile_data " + (on ? "1" : "0")
                    + "; echo done");
            if (!r.ok()) throw new IllegalStateException(brief(r));
            String now = Root.exec(ctx, "settings get global mobile_data").out.trim();
            boolean isOn = "1".equals(now);
            o.put("ok", isOn == on);
            o.put("mobileData", isOn);
            o.put("message", (isOn == on ? "" : "状态未变，") + "移动数据" + (isOn ? "已开启" : "已关闭"));
            Logs.add("radio", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    public static JSONObject setRoaming(Context ctx, boolean on) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx,
                    "settings put global data_roaming " + (on ? "1" : "0") + "; echo done");
            if (!r.ok()) throw new IllegalStateException(brief(r));
            String now = Root.exec(ctx, "settings get global data_roaming").out.trim();
            o.put("ok", true);
            o.put("roaming", "1".equals(now));
            o.put("message", "数据漫游已" + ("1".equals(now) ? "开启" : "关闭")
                    + "；部分 ROM 要重新附着网络才生效");
            Logs.add("radio", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #16 SIM 锁 / ICCID ----------

    /**
     * 固件没有 ril.iccid.* 属性，只能问 TelephonyManager。
     * 注意：这台 ROM 读出来带 BCD 填充位（实测 898600682226f0488598），
     * 中间夹着 'f'，所以统一只保留数字再比较 —— 真实 ICCID 本来就是纯数字。
     */
    public static String iccid(Context ctx) {
        return normIccid(iccidRaw(ctx));
    }

    /** 原始字符串，排障时看填充位用。 */
    public static String iccidRaw(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager)
                    ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String s = tm.getSimSerialNumber();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 只留数字：去掉 ROM 塞进来的 f 填充位，也让末 4 位比较不会错位。 */
    static String normIccid(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    public static String imsi(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager)
                    ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String s = tm.getSubscriberId();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前 ICCID 是否在白名单里；锁没开时一律 allowed。 */
    public static JSONObject simLock(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AppConfig cfg = AppConfig.load(ctx);
            String iccid = iccid(ctx);
            String last4 = iccid.length() > 4 ? iccid.substring(iccid.length() - 4) : iccid;
            boolean locked = cfg.simLockEnabled;
            List<String> list = cfg.iccids();
            boolean allowed = !locked || matches(iccid, last4, list);
            o.put("enabled", locked);
            o.put("ok", true);
            o.put("allowed", allowed);
            o.put("iccid", iccid);
            o.put("last4", last4);
            o.put("whitelist", new JSONArray(list));
            o.put("action", cfg.simLockAction);
            o.put("blocked", simBlocked);
            o.put("message", !locked ? "SIM 锁未启用"
                    : (allowed ? "当前 SIM 在白名单内" : "当前 SIM 不在白名单（" + last4 + "）"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /**
     * 白名单匹配：整串 ICCID 或末 4 位都算。末 4 位是为了换卡补卡后
     * 卡号重印时仍然好认，代价是精确度下降 —— 单人维护场景够用。
     * 两边都按「只留数字」归一化，免得 ROM 的 f 填充位把比较搞挂。
     */
    private static boolean matches(String iccid, String last4, List<String> list) {
        String me = normIccid(iccid);
        String myTail = me.length() >= 4 ? me.substring(me.length() - 4) : me;
        for (int i = 0; i < list.size(); i++) {
            String e = normIccid(list.get(i));
            if (e.length() == 0) continue;
            if (e.equals(me)) return true;
            if (e.length() <= 4 && e.equals(myTail)) return true;
            if (me.endsWith(e)) return true;
        }
        return false;
    }

    /**
     * 服务循环里调用：锁开了但卡不对，按 action 处理。
     * log = 只记日志 + 面板告警；block = 停止转发（消息仍然入库，换回原卡能补发）。
     */
    public static boolean enforceSimLock(Context ctx) {
        try {
            AppConfig cfg = AppConfig.load(ctx);
            if (!cfg.simLockEnabled) {
                simBlocked = false;
                lastSimBlock = "";
                return false;
            }
            String iccid = iccid(ctx);
            if (iccid.length() == 0) return simBlocked;   // 读不到卡就别误伤
            String last4 = iccid.length() > 4 ? iccid.substring(iccid.length() - 4) : iccid;
            boolean allowed = matches(iccid, last4, cfg.iccids());
            if (allowed) {
                simBlocked = false;
                lastSimBlock = "";
                return false;
            }
            boolean block = "block".equals(cfg.simLockAction);
            simBlocked = block;
            if (!iccid.equals(lastSimBlock)) {
                lastSimBlock = iccid;
                Logs.add("radio", "SIM 锁命中：卡 " + last4 + " 不在白名单，action="
                        + cfg.simLockAction);
            }
            return block;
        } catch (Throwable t) {
            return simBlocked;
        }
    }

    // ---------- #17 流量统计 ----------

    /** /proc/net/dev 里所有 rmnet、wlan、rndis 的收发累计 + 自清零以来的增量。 */
    public static JSONObject traffic(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx, "cat /proc/net/dev; echo '##UP'; cat /proc/uptime");
            String[] parts = r.out.split("##UP");
            long rx = 0, tx = 0;
            JSONArray ifs = new JSONArray();
            List<String> lines = splitLines(parts[0]);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int c = line.indexOf(':');
                if (c < 0) continue;
                String name = line.substring(0, c).trim();
                if (!isWan(name)) continue;
                String[] f = line.substring(c + 1).trim().split("\\s+");
                if (f.length < 9) continue;
                long irx = lng(f[0]), itx = lng(f[8]);
                rx += irx;
                tx += itx;
                JSONObject io = new JSONObject();
                io.put("name", name);
                io.put("rx", irx);
                io.put("tx", itx);
                ifs.put(io);
            }
            long up = 0;
            if (parts.length > 1) {
                try {
                    up = (long) Double.parseDouble(parts[1].trim().split("\\s+")[0]);
                } catch (Throwable ignored) {
                }
            }
            SharedPreferences sp = prefs(ctx);
            long brx = sp.getLong(K_BASE_RX, -1);
            long btx = sp.getLong(K_BASE_TX, -1);
            long bup = sp.getLong(K_BASE_UP, 0);
            o.put("ok", true);
            o.put("rx", rx);
            o.put("tx", tx);
            o.put("uptimeSec", up);
            o.put("ifaces", ifs);
            o.put("baseRx", brx);
            o.put("baseTx", btx);
            o.put("baseUptime", bup);
            o.put("sinceRx", brx < 0 ? -1 : Math.max(0, rx - brx));
            o.put("sinceTx", btx < 0 ? -1 : Math.max(0, tx - btx));
            o.put("sinceSec", brx < 0 ? -1 : Math.max(0, up - bup));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /** 清零 = 把当前累计值存成基线（不碰系统的统计文件，也不搬 webkey 的黑盒基线）。 */
    public static JSONObject trafficClear(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONObject t = traffic(ctx);
            if (!t.optBoolean("ok")) throw new IllegalStateException(t.optString("message", "读取失败"));
            prefs(ctx).edit()
                    .putLong(K_BASE_RX, t.optLong("rx"))
                    .putLong(K_BASE_TX, t.optLong("tx"))
                    .putLong(K_BASE_UP, t.optLong("uptimeSec"))
                    .commit();
            o.put("ok", true);
            o.put("message", "流量计数已清零（基线 rx=" + t.optLong("rx") + " tx=" + t.optLong("tx") + "）");
            Logs.add("radio", "流量计数清零");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- 内部 ----------

    private static boolean isWan(String n) {
        return n.startsWith("rmnet") || n.startsWith("rev_rmnet")
                || n.startsWith("wlan") || n.startsWith("rndis")
                || n.startsWith("ccmni") || n.startsWith("pdp");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String simNumeric(Context ctx) {
        try {
            Root.Result r = Root.exec(ctx,
                    "getprop gsm.sim.operator.numeric; getprop gsm.operator.numeric");
            List<String> l = splitLines(r.out);
            for (int i = 0; i < l.size(); i++) {
                String s = l.get(i).trim();
                if (s.matches("[0-9]{5,6}")) return s;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static long preferredApnId(Context ctx) {
        try {
            Root.Result r = Root.exec(ctx,
                    "content query --uri " + PREFER + " --projection _id 2>/dev/null");
            return idOf(first(splitLines(r.out)));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long findApnId(Context ctx, String name) {
        try {
            long id = 0;
            Root.Result r = Root.exec(ctx, "sqlite3 " + DB +
                    " \"select max(_id) from carriers where name='" + name.replace("'", "''") + "'\"");
            String s = r.out.trim();
            if (s.matches("[0-9]+")) id = Long.parseLong(s);
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long countApn(Context ctx, long id) {
        try {
            Root.Result r = Root.exec(ctx,
                    "sqlite3 " + DB + " \"select count(*) from carriers where _id=" + id + "\"");
            String s = r.out.trim();
            return s.matches("[0-9]+") ? Long.parseLong(s) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void bind(StringBuilder c, String col, String type, String val) {
        c.append(" --bind ").append(col).append(':').append(type).append(':')
                .append("'").append(val.replace("'", "'\\''")).append("'");
    }

    /** 只允许安全字符，并截断；防的是把引号/分号带进 shell。 */
    private static String clean(String s, int max) {
        if (s == null) return "";
        String v = s.replace("\n", " ").replace("\r", " ").replace("'", "").trim();
        if (v.length() > max) v = v.substring(0, max);
        return v;
    }

    private static java.util.Map<String, List<String>> sections(String out) {
        java.util.Map<String, List<String>> m = new java.util.HashMap<String, List<String>>();
        String cur = "";
        List<String> lines = splitLines(out);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("##")) {
                cur = line.substring(2).trim();
                m.put(cur, new ArrayList<String>());
            } else {
                List<String> l = m.get(cur);
                if (l == null) {
                    l = new ArrayList<String>();
                    m.put(cur, l);
                }
                if (line.trim().length() > 0) l.add(line);
            }
        }
        return m;
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] a = s.replace("\r", "").split("\n");
        for (int i = 0; i < a.length; i++) out.add(a[i]);
        return out;
    }

    private static String first(List<String> in) {
        if (in == null || in.isEmpty()) return "";
        return in.get(0).trim();
    }

    private static String join(List<String> in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.size(); i++) sb.append(in.get(i)).append('\n');
        return sb.toString();
    }

    /** "22,1" → "1"；没有后缀回空串。 */
    private static String suffixOf(String raw) {
        if (raw == null) return "";
        int c = raw.indexOf(',');
        if (c < 0) return "";
        return raw.substring(c + 1).trim();
    }

    private static int intIn(String s) {
        if (s == null) return -1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else break;
        }
        if (sb.length() == 0) return -1;
        try {
            return Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long num(String s) {
        String v = s == null ? "" : s.trim();
        return v.matches("-?[0-9]+") ? Long.parseLong(v) : 0;
    }

    private static long lng(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** "Row: 0 _id=1602, name=China Mobile, ..." → 1602 */
    private static long idOf(String line) {
        if (line == null) return 0;
        int i = line.indexOf("_id=");
        if (i < 0) return 0;
        int j = i + 4;
        StringBuilder sb = new StringBuilder();
        while (j < line.length() && Character.isDigit(line.charAt(j))) sb.append(line.charAt(j++));
        return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
    }

    private static String kvOf(String line, String key) {
        if (line == null) return "";
        int i = line.indexOf(key + "=");
        if (i < 0) return "";
        int j = i + key.length() + 1;
        int end = line.indexOf(',', j);
        if (end < 0) end = line.length();
        return line.substring(j, end).trim();
    }

    private static String brief(Root.Result r) {
        String s = (r.error.length() > 0 ? r.error : r.out).trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String str(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
    }

    /** 给体检/健康接口复用：一行摘要。 */
    public static String summary(Context ctx) {
        try {
            JSONObject s = state(ctx);
            return "mode=" + s.optString("modeRaw") + " data=" + s.optInt("mobileData")
                    + " sim=" + s.optString("simState") + " apn=" + s.optString("preferredApn");
        } catch (Throwable t) {
            return "";
        }
    }
}
