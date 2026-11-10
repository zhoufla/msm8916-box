package com.sp970.smsrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

/**
 * DDNS 动态域名：把设备当前可达地址推给 DuckDNS / Cloudflare。
 *
 * 为什么不直接报本机 IP：
 * - 蜂窝上网拿到的是运营商 CGNAT 私网地址（100.64/10），公网不可达，报出去只会误导；
 * - station 模式报 NAT 出口 IPv4（api.ipify.org 探测）；
 * - IPv6 没有中间 NAT，直接报接口上的全球单播地址。
 *
 * v2 多记录：每条记录可选网卡（iface，空=自动）与记录类型（family=v4|v6|both）；
 * 服务商/Token/Zone 公共一份。每条记录独立比对、独立推送、独立记状态，
 * 每次更新都写日志；失败经 Alerts 通知（含恢复通知）。
 * 推送不进转发记录，绝不影响短信转发主链路。
 */
public final class Ddns {

    private static final String PREFS = "ddns";
    private static volatile long lastTick = 0L;
    private static volatile boolean lastFail = false;   // 给 Alerts 恢复事件用
    /** 「地址未变」心跳日志的节流间隔：地址通常几天不变，全量记会刷满环形日志。 */
    private static final long SKIP_LOG_MS = 6L * 3600 * 1000;
    private static volatile long lastSkipLogAt = 0L;

    private Ddns() {
    }

    /** 服务主循环低频驱动：到点才真正干活，内部自己起线程。 */
    public static void tick(Context app) {
        try {
            AppConfig cfg = AppConfig.load(app);
            if (!cfg.ddnsEnabled) return;
            long now = SystemClock.elapsedRealtime();
            if (lastTick > 0 && now - lastTick < cfg.ddnsIntervalMin * 60000L) return;
            lastTick = now;
            updateNow(app);
        } catch (Throwable t) {
            Logs.add("ddns", "tick 异常：" + str(t));
        }
    }

    /** 立即更新一次（面板按钮 / tick 调用），后台线程执行。 */
    public static void updateNow(final Context app) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    update(app);
                } catch (Throwable t) {
                    Logs.add("ddns", "更新异常：" + str(t));
                }
            }
        }, "ddns");
        t.setDaemon(true);
        t.start();
    }

    // ---------- 核心 ----------

    private static void update(Context app) throws Exception {
        AppConfig cfg = AppConfig.load(app);
        if (!cfg.ddnsEnabled) {
            Logs.add("ddns", "未启用，跳过");
            return;
        }
        if (cfg.ddnsToken.length() == 0) {
            Logs.add("ddns", "Token 未配置，跳过");
            return;
        }
        if (cfg.ddnsRecords.isEmpty()) {
            Logs.add("ddns", "没有解析记录，跳过");
            return;
        }
        String mode = wifiMode(app);

        // 公共探测一次：IPv4（station 才报 NAT 出口）；IPv6 按唯一 iface 分组枚举
        boolean needV4 = false;
        List<String> ifaceKeys = new ArrayList<String>();
        for (int i = 0; i < cfg.ddnsRecords.size(); i++) {
            AppConfig.DdnsRecord r = cfg.ddnsRecords.get(i);
            if (r.wantV4()) needV4 = true;
            if (r.wantV6() && !ifaceKeys.contains(r.iface)) ifaceKeys.add(r.iface);
        }
        String v4 = "";
        if (needV4) {
            if ("sta".equals(mode)) v4 = detectV4(cfg.ddnsInsecure);
            else Logs.add("ddns", "当前非 station 模式（" + mode + "），IPv4 是 CGNAT 不报");
        }
        HashMap<String, String> v6map = new HashMap<String, String>();
        for (int i = 0; i < ifaceKeys.size(); i++) {
            String k = ifaceKeys.get(i);
            v6map.put(k, detectV6(k));
        }

        // Cloudflare zone 只查一次
        String zoneId = "";
        String auth = "";
        if ("cloudflare".equals(cfg.ddnsProvider)) {
            auth = "Authorization: Bearer " + cfg.ddnsToken;
            Net.Resp zr = Net.requestAuto("GET",
                    "https://api.cloudflare.com/client/v4/zones?name=" + enc(cfg.ddnsZone),
                    new String[]{auth, "Content-Type: application/json"},
                    null, cfg.ddnsInsecure, "application/json");
            zoneId = DdnsLogic.cfZoneId(zr.body);
            if (zoneId.length() == 0) {
                Logs.add("ddns", "Cloudflare 找不到 zone " + cfg.ddnsZone
                        + "：" + DdnsLogic.cfError(zr.body));
            }
        }

        SharedPreferences p = prefs(app);
        int okCount = 0, failCount = 0;
        StringBuilder failMsg = new StringBuilder();

        for (int i = 0; i < cfg.ddnsRecords.size(); i++) {
            AppConfig.DdnsRecord rec = cfg.ddnsRecords.get(i);
            String label = rec.domain + (rec.iface.length() > 0 ? "@" + rec.iface : "") ;
            String v4r = rec.wantV4() ? v4 : "";
            String v6r = rec.wantV6() ? nz(v6map.get(rec.iface)) : "";

            if (v4r.length() == 0 && v6r.length() == 0) {
                Logs.add("ddns", label + "：没有可推送的地址，跳过");
                putRec(p, i, "lastError", "没有可推送的地址（蜂窝 IPv4 是 CGNAT 不报；IPv6 未分配）");
                failCount++;
                if (failMsg.length() > 0) failMsg.append("；");
                failMsg.append(label).append(" 无地址");
                continue;
            }

            String lastPair = p.getString("lastPair" + i,
                    i == 0 ? p.getString("lastPair", "") : "");
            if (!DdnsLogic.ipChanged(lastPair, v4r, v6r)) {
                putRec(p, i, "lastError", "");
                // 检查每 10 分钟一轮而地址通常几天不变：全量记会让「地址未变」
                // 占满面板环形日志（真机实测 67%），有用记录几小时就被冲掉。
                // 只保留 6 小时一次的心跳，证明 DDNS 线程还活着；当前地址面板可见。
                long now = SystemClock.elapsedRealtime();
                if (lastSkipLogAt == 0L || now - lastSkipLogAt >= SKIP_LOG_MS) {
                    lastSkipLogAt = now;
                    Logs.add("ddns", label + "：地址未变，跳过（" + lastPair + "）");
                }
                continue;
            }

            boolean ok;
            String detail;
            if ("cloudflare".equals(cfg.ddnsProvider)) {
                if (zoneId.length() == 0) {
                    ok = false;
                    detail = "zone 未找到";
                } else {
                    ok = true;
                    detail = "";
                    if (v4r.length() > 0) ok &= upsertRecord(cfg, auth, zoneId, rec.domain, "A", v4r);
                    if (v6r.length() > 0) ok &= upsertRecord(cfg, auth, zoneId, rec.domain, "AAAA", v6r);
                }
            } else {
                Net.Resp r = Net.getAuto(DdnsLogic.duckUrl(rec.domain, cfg.ddnsToken, v4r, v6r),
                        cfg.ddnsInsecure, "text/plain");
                ok = r.code == 200 && DdnsLogic.duckOk(r.body);
                detail = ok ? "" : "DuckDNS 应答 HTTP " + r.code + " " + firstLine(r.body);
            }

            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = p.edit();
            if (ok) {
                e.putString("lastPair" + i, DdnsLogic.pairKey(v4r, v6r));
                e.putString("lastV4" + i, v4r);
                e.putString("lastV6" + i, v6r);
                e.putLong("lastOkAt" + i, now);
                e.putString("lastError" + i, "");
                e.commit();
                okCount++;
                Logs.add("ddns", "已更新 " + label + " → " + DdnsLogic.pairKey(v4r, v6r));
            } else {
                e.putString("lastError" + i, detail);
                e.putLong("lastFailAt" + i, now);
                e.commit();
                failCount++;
                Logs.add("ddns", label + " 更新失败：" + detail);
                if (failMsg.length() > 0) failMsg.append("；");
                failMsg.append(label).append(" ").append(detail);
            }
        }

        // 汇总告警：有失败推一条；全部恢复再推一条
        if (failCount > 0) {
            Alerts.event(app, cfg, "ddns", true,
                    "DDNS 更新失败 " + failCount + "/" + cfg.ddnsRecords.size() + "：" + failMsg);
            lastFail = true;
        } else if (okCount > 0 && lastFail) {
            Alerts.event(app, cfg, "ddns", false, "DDNS 已恢复更新（" + okCount + " 条）");
            lastFail = false;
        } else if (okCount > 0) {
            lastFail = false;
        }
    }

    /** Cloudflare：记录有则 PATCH、无则 POST（TTL 300）。 */
    private static boolean upsertRecord(AppConfig cfg, String auth, String zoneId,
                                        String domain, String type, String ip) {
        try {
            String listUrl = "https://api.cloudflare.com/client/v4/zones/" + zoneId
                    + "/dns_records?type=" + type + "&name=" + enc(domain);
            Net.Resp lr = Net.requestAuto("GET", listUrl,
                    new String[]{auth, "Content-Type: application/json"},
                    null, cfg.ddnsInsecure, "application/json");
            String recId = DdnsLogic.cfRecordId(lr.body);
            String payload = new JSONObject()
                    .put("type", type)
                    .put("name", domain)
                    .put("content", ip)
                    .put("ttl", 300)
                    .put("proxied", false)
                    .toString();
            Net.Resp r;
            if (recId.length() > 0) {
                r = Net.requestAuto("PATCH",
                        "https://api.cloudflare.com/client/v4/zones/" + zoneId
                                + "/dns_records/" + recId,
                        new String[]{auth, "Content-Type: application/json"},
                        payload, cfg.ddnsInsecure, "application/json");
            } else {
                r = Net.requestAuto("POST",
                        "https://api.cloudflare.com/client/v4/zones/" + zoneId + "/dns_records",
                        new String[]{auth, "Content-Type: application/json"},
                        payload, cfg.ddnsInsecure, "application/json");
            }
            boolean ok = r.code >= 200 && r.code < 300 && DdnsLogic.cfOk(r.body);
            if (!ok) Logs.add("ddns", domain + " " + type + " 记录更新失败：" + DdnsLogic.cfError(r.body));
            return ok;
        } catch (Throwable t) {
            Logs.add("ddns", domain + " " + type + " 记录异常：" + str(t));
            return false;
        }
    }

    // ---------- 地址探测 ----------

    private static String wifiMode(Context app) {
        try {
            return WifiCtl.state(app).optString("mode", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** station 模式下的 NAT 出口 IPv4（api.ipify.org，纯文本）。 */
    private static String detectV4(boolean insecure) {
        try {
            Net.Resp r = Net.getAuto("https://api.ipify.org", insecure, "text/plain");
            String ip = r.body == null ? "" : r.body.trim();
            if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return ip;
            Logs.add("ddns", "出口 IPv4 应答不可信：" + firstLine(ip));
        } catch (Throwable t) {
            Logs.add("ddns", "探测出口 IPv4 失败：" + str(t));
        }
        return "";
    }

    /**
     * 全球单播 IPv6。iface 空：rmnet（蜂窝）优先，其次 wlan（station）；
     * iface 指定：只看这张网卡（选不到返回空，面板状态行会显示缺当前 v6）。
     */
    static String detectV6(String iface) {
        String f = iface == null ? "" : iface.trim();
        try {
            List<String> cell = new ArrayList<String>();
            List<String> wifi = new ArrayList<String>();
            List<String> named = new ArrayList<String>();
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                String name = ni.getName() == null ? "" : ni.getName();
                Enumeration<InetAddress> ea = ni.getInetAddresses();
                while (ea.hasMoreElements()) {
                    InetAddress a = ea.nextElement();
                    if (!(a instanceof Inet6Address) || a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if (f.length() > 0) {
                        if (name.equals(f)) named.add(s);
                    } else if (name.startsWith("rmnet") || name.startsWith("ccmni")) cell.add(s);
                    else wifi.add(s);
                }
            }
            if (f.length() > 0) return DdnsLogic.pickGlobalV6(named);
            String v6 = DdnsLogic.pickGlobalV6(cell);
            if (v6.length() == 0) v6 = DdnsLogic.pickGlobalV6(wifi);
            return v6;
        } catch (Throwable t) {
            Logs.add("ddns", "枚举网卡失败：" + str(t));
            return "";
        }
    }

    /** 指定网卡的 IPv4 地址（本地枚举，不联网；选不到返回空）。 */
    private static String ifaceV4(String name) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(name);
            if (ni == null) return "";
            Enumeration<InetAddress> ea = ni.getInetAddresses();
            while (ea.hasMoreElements()) {
                InetAddress a = ea.nextElement();
                if (a instanceof Inet6Address || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                String s = a.getHostAddress();
                if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return s;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ---------- 状态 ----------

    /** 面板 /api/ddns：公共配置 + records[]（每条含当前地址与上次推送状态）。 */
    public static JSONObject status(Context app) throws Exception {
        AppConfig cfg = AppConfig.load(app);
        SharedPreferences p = prefs(app);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("enabled", cfg.ddnsEnabled);
        o.put("provider", cfg.ddnsProvider);
        o.put("zone", cfg.ddnsZone);
        o.put("intervalMin", cfg.ddnsIntervalMin);
        o.put("curV6", detectV6(""));

        // 唯一 iface 分组枚举一次
        HashMap<String, String> v6map = new HashMap<String, String>();
        HashMap<String, String> v4map = new HashMap<String, String>();
        for (int i = 0; i < cfg.ddnsRecords.size(); i++) {
            AppConfig.DdnsRecord r = cfg.ddnsRecords.get(i);
            if (r.wantV6() && !v6map.containsKey(r.iface)) v6map.put(r.iface, detectV6(r.iface));
            if (r.iface.length() > 0 && !v4map.containsKey(r.iface)) {
                v4map.put(r.iface, ifaceV4(r.iface));
            }
        }

        JSONArray rs = new JSONArray();
        for (int i = 0; i < cfg.ddnsRecords.size(); i++) {
            AppConfig.DdnsRecord r = cfg.ddnsRecords.get(i);
            JSONObject ro = r.toJson();
            if (r.wantV6()) ro.put("curV6", nz(v6map.get(r.iface)));
            if (r.iface.length() > 0) ro.put("curV4", nz(v4map.get(r.iface)));
            ro.put("lastV4", p.getString("lastV4" + i, i == 0 ? p.getString("lastV4", "") : ""));
            ro.put("lastV6", p.getString("lastV6" + i, i == 0 ? p.getString("lastV6", "") : ""));
            ro.put("lastOkAt", p.getLong("lastOkAt" + i, i == 0 ? p.getLong("lastOkAt", 0) : 0));
            ro.put("lastError", p.getString("lastError" + i, i == 0 ? p.getString("lastError", "") : ""));
            rs.put(ro);
        }
        o.put("records", rs);
        return o;
    }

    private static void putRec(SharedPreferences p, int i, String key, String val) {
        try {
            p.edit().putString(key + i, val).commit();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = t.indexOf('\n');
        if (i > 0) t = t.substring(0, i).trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String str(Throwable t) {
        String m = String.valueOf(t.getMessage());
        String cn = t.getClass().getSimpleName();
        if (m == null || m.length() == 0) return cn;
        String s = cn + ": " + m;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
