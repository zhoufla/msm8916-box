package com.sp970.smsrelay;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DDNS 纯逻辑：地址筛选 / URL 拼装 / 应答解析。不碰网络与 Android API，
 * 桌面 run-tests.sh 直接可测；网络调用与状态持久化在 Ddns。
 */
public final class DdnsLogic {

    private DdnsLogic() {
    }

    /**
     * 从候选地址里挑全球单播 IPv6（2000::/3）：跳过链路本地（fe80）、ULA（fc/fd，
     * 内网不可路由）、回环与 IPv4；地址按传入顺序取第一个合格的。
     */
    public static String pickGlobalV6(java.util.List<String> addrs) {
        if (addrs == null) return "";
        for (int i = 0; i < addrs.size(); i++) {
            String s = addrs.get(i) == null ? "" : addrs.get(i).trim();
            int pct = s.indexOf('%');
            if (pct >= 0) s = s.substring(0, pct);
            if (s.length() == 0) continue;
            if (s.indexOf(':') < 0) continue;   // 只要 IPv6
            char c = s.charAt(0);
            if (c == '2' || c == '3') return s; // 2000::/3 全球单播
        }
        return "";
    }

    /**
     * DuckDNS 更新 URL：domains=子域（允许带 .duckdns.org 后缀，自动剥掉）；
     * v4/v6 有才带参（不带 ip 参数时 DuckDNS 保留原记录）。
     */
    public static String duckUrl(String domain, String token, String v4, String v6) {
        StringBuilder sb = new StringBuilder("https://www.duckdns.org/update?domains=");
        sb.append(urlenc(stripDuckSuffix(nz(domain))));
        sb.append("&token=").append(urlenc(nz(token)));
        if (nz(v4).length() > 0) sb.append("&ip=").append(urlenc(v4.trim()));
        if (nz(v6).length() > 0) sb.append("&ip6=").append(urlenc(v6.trim()));
        sb.append("&verbose=true");
        return sb.toString();
    }

    static String stripDuckSuffix(String d) {
        d = nz(d);
        if (d.toLowerCase().endsWith(".duckdns.org")) {
            d = d.substring(0, d.length() - ".duckdns.org".length());
        }
        return d;
    }

    /** DuckDNS 成功应答是 OK（verbose 时后面还跟地址文本），宽松匹配。 */
    public static boolean duckOk(String body) {
        if (body == null) return false;
        return body.trim().toUpperCase().startsWith("OK");
    }

    /** 上次推送对（"v4|v6"）；"|-" 表示全空。 */
    public static String pairKey(String v4, String v6) {
        return nz(v4) + "|" + nz(v6);
    }

    /** 变更才更新：新对至少有一个地址，且与上次不同。 */
    public static boolean ipChanged(String lastPair, String v4, String v6) {
        String p = pairKey(v4, v6);
        if (p.equals("|")) return false;
        return !p.equals(nz(lastPair));
    }

    // ---------- Cloudflare API v4 应答解析 ----------

    /** {"success":true,...}；网络层错误体也按此判。 */
    public static boolean cfOk(String body) {
        try {
            return new JSONObject(body).optBoolean("success", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** GET /zones?name= 与 GET /dns_records 列表应答的 result[0].id。 */
    public static String cfZoneId(String body) {
        return firstId(body);
    }

    public static String cfRecordId(String body) {
        return firstId(body);
    }

    private static String firstId(String body) {
        try {
            JSONArray a = new JSONObject(body).optJSONArray("result");
            if (a != null && a.length() > 0) {
                return a.getJSONObject(0).optString("id", "");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** errors[0] 拼一条可读信息（code + message）。 */
    public static String cfError(String body) {
        try {
            JSONArray a = new JSONObject(body).optJSONArray("errors");
            if (a != null && a.length() > 0) {
                JSONObject e = a.getJSONObject(0);
                String m = e.optString("message", "");
                int code = e.optInt("code", 0);
                return m.length() > 0 ? (code > 0 ? code + " " + m : m) : String.valueOf(e);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 记录类型门控：family=both/null/空/未知 → 都要；v4 → 仅 IPv4；v6 → 仅 IPv6。 */
    public static boolean wantV4(String family) { return !"v6".equals(nzf(family)); }

    public static boolean wantV6(String family) { return !"v4".equals(nzf(family)); }

    private static String nzf(String s) {
        if (s == null) return "both";
        String t = s.trim();
        return t.length() == 0 ? "both" : t;
    }

    /** 网卡名归一：空白裁边；仅字母数字与 . _ : - 且 ≤20 位，非法归空。 */
    public static String normIface(String s) {
        String f = s == null ? "" : s.trim();
        return f.matches("[A-Za-z0-9._:-]{1,20}") ? f : "";
    }

    /** 记录类型归一：v4 / v6 之外（含 null/空）一律 both。 */
    public static String normFamily(String s) {
        return "v4".equals(s) || "v6".equals(s) ? s : "both";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String urlenc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
