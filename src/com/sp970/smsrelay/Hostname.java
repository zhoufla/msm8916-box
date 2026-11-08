package com.sp970.smsrelay;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/**
 * 设备名 → 系统主机名（DHCP option 12 / mDNS 用）。
 *
 * Android 默认的 net.hostname 是 android-&lt;ANDROID_ID&gt;，路由器客户端列表里所有盒子
 * 都长一样，看不出是哪台。面板改设备名后经 root helper 同步写 net.hostname，
 * 再断开重连一次 WiFi，让 DHCP 用新名字重新注册。
 *
 * 主机名只保留 [A-Za-z0-9-]（中文等字符无法作为合法 DNS label），清洗后为空时
 * 退回机型名；属性写入失败会在后台重试，不阻塞配置保存/服务启动。
 */
public final class Hostname {

    static final String PROP = "net.hostname";
    private static final int MAX_LEN = 63;
    /** 首次立即试，失败后隔 10s / 30s 再试（开机时 helper 可能还没起来）。 */
    private static final long[] RETRY_MS = {0, 10000, 30000};
    /** 最新一次要求的名字：旧的重试线程发现它已过期就退出，避免覆盖刚改的新名字。 */
    private static volatile String latest = "";

    private Hostname() {
    }

    /** 后台同步：写属性 + 必要时重连 WiFi，不挡 HTTP 响应/服务启动。 */
    public static void sync(final Context ctx, final String device) {
        final Context app = ctx.getApplicationContext();
        final String name = sanitize(device);
        latest = name;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < RETRY_MS.length; i++) {
                    if (RETRY_MS[i] > 0) {
                        try {
                            Thread.sleep(RETRY_MS[i]);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    int r = apply(app, name);
                    if (r == 0) return;       // 已经是新名字
                    if (r == 1) {
                        reconnect(app);
                        return;
                    }
                }
            }
        }, "smsrelay-hostname");
        t.setDaemon(true);
        t.start();
    }

    /** 设备名 → 合法主机名：只留 ASCII 字母/数字/连字符，最长 63；空则退回机型。 */
    static String sanitize(String device) {
        String s = clean(device);
        if (s.length() == 0) s = clean(Build.MODEL);
        return s.length() == 0 ? "smsrelay" : s;
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_LEN; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '-' || c == ' ' || c == '_' || c == '.'
                    || c == '/' || c == '\\' || c == ':') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** @return 0=已是新名字，1=写入成功，-1=失败。 */
    private static int apply(Context ctx, String name) {
        if (!name.equals(latest)) return 0;   // 已有更新的设备名，交给新线程
        String cur = SysInfo.prop(PROP);
        if (name.equals(cur)) return 0;
        Root.Result r = Root.exec(ctx, "setprop " + PROP + " " + name);
        if (!r.ok()) {
            Logs.add("host", "设置主机名失败（exit=" + r.exit + " " + r.error + "）");
            return -1;
        }
        Logs.add("host", "主机名 " + (cur.length() == 0 ? "(空)" : cur) + " → " + name);
        return 1;
    }

    /** 只重连 station：断开再连才会重新发 DHCP，路由器才能看到新的 hostname。 */
    private static void reconnect(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return;
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() < 0) return;  // 没连路由器，下次连上自会带新名字
            wm.disconnect();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            boolean ok = wm.reconnect();
            Logs.add("host", "重连 WiFi 刷新 DHCP 主机名 → " + ok);
        } catch (Throwable t) {
            Logs.add("host", "重连 WiFi 失败: " + t);
        }
    }
}
