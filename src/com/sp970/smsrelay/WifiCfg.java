package com.sp970.smsrelay;

/**
 * WiFi 配置纯逻辑（不碰 android.*，离线单测覆盖）。
 *
 * 关键坑（SP970 实测）：这台 4.4 ROM 的 wifi JNI 给 supplicant 发 psk 时【不加双引号】
 * （标准 AOSP 4.4 的 android_net_wifi_Wifi.cpp 会包引号）。wpa_supplicant 收到不带引号的
 * psk 只认 64 位 hex，passphrase 直接报 "Invalid PSK 'xxx'"，WifiConfigStore 随即
 * "failed to set psk" 并把网络删掉（表现为「保存网络配置失败（系统拒绝）」）。
 * 所以带密码连接要先按标准方式裸试（兼容标准 ROM），失败后用 quotePsk 包引号重试。
 */
public final class WifiCfg {

    private WifiCfg() {
    }

    /** WifiConfiguration.SSID 里存的值常带首尾引号，去掉；null/空安全。 */
    public static String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 已存 SSID 与目标是否同一网络（自动去引号；target 不允许空）。 */
    public static boolean ssidEquals(String savedSsid, String target) {
        if (savedSsid == null || target == null) return false;
        String t = stripQuotes(target);
        if (t.length() == 0) return false;
        return stripQuotes(savedSsid).equals(t);
    }

    /**
     * psk 包双引号（passphrase 形式）；已包裹的原样返回。
     * 只处理长度 &gt;= 1 的普通口令；null/空原样返回（开放网络没有 psk）。
     */
    public static String quotePsk(String psk) {
        if (psk == null || psk.length() == 0) return psk;
        if (psk.charAt(0) == '"' && psk.charAt(psk.length() - 1) == '"' && psk.length() >= 2) {
            return psk;
        }
        return '"' + psk + '"';
    }
}
