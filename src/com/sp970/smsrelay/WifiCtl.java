package com.sp970.smsrelay;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 热点控制。
 *
 * 4.4 的 setWifiApEnabled / isWifiApEnabled / getWifiApConfiguration 都是 hidden API，
 * 只能反射调用；不同厂商 ROM 行为有差异，所以每个调用都带兜底与明确错误返回。
 * 客户端列表走 /proc/net/arp（热点网段 192.168.42.x），不依赖 root。
 */
public final class WifiCtl {

    private static final String HOTSPOT_NET = "192.168.42.";

    private static WifiManager wm(Context ctx) {
        return (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    private static Method m(String name, Class<?>... args) throws Exception {
        Method m = WifiManager.class.getMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    // ---------- 状态 ----------

    public static JSONObject state(Context ctx) {
        JSONObject o = new JSONObject();
        WifiManager w = wm(ctx);
        try {
            o.put("ap", apOn(w));
            o.put("wifiOn", w != null && w.isWifiEnabled());
            WifiConfiguration c = apConfig(w);
            o.put("ssid", c == null ? "" : nz(c.SSID));
            o.put("secured", c != null && c.preSharedKey != null && c.preSharedKey.length() > 0);
            o.put("clients", clients());
            String ip = "";
            try {
                android.net.wifi.WifiManager ww = w;
                if (ww != null && ww.getDhcpInfo() != null) {
                    int d = ww.getDhcpInfo().ipAddress;
                    if (d != 0) {
                        ip = (d & 0xFF) + "." + ((d >> 8) & 0xFF) + "."
                                + ((d >> 16) & 0xFF) + "." + ((d >> 24) & 0xFF);
                    }
                }
            } catch (Throwable ignored) {
            }
            o.put("clientIp", ip);
            JSONArray ifs = wifiIfaces();
            o.put("ifaces", ifs);
            if (ifs.length() == 0 && !Boolean.TRUE.equals(o.opt("ap"))) {
                o.put("hint", "没有无线网卡（wlan0 不存在），热点由厂商守护进程管理或未启用");
            }
        } catch (Throwable t) {
            Logs.add("wifi", "state 失败: " + t);
        }
        return o;
    }

    private static Object apOn(WifiManager w) {
        try {
            Method m = m("isWifiApEnabled");
            Object r = m.invoke(w);
            return r instanceof Boolean ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static WifiConfiguration apConfig(WifiManager w) {
        try {
            Method m = m("getWifiApConfiguration");
            Object r = m.invoke(w);
            return r instanceof WifiConfiguration ? (WifiConfiguration) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 开关 / 改配置 ----------

    /**
     * @param action on | off | set
     * @return 给人看的结果描述
     */
    public static synchronized String apply(Context ctx, String action, String ssid, String pass) {
        WifiManager w = wm(ctx);
        if (w == null) return "WiFi 服务不可用";
        action = action == null ? "" : action;

        if ("off".equals(action)) {
            try {
                m("setWifiApEnabled").invoke(w, null, false);
                sleep(1200);
                Logs.add("wifi", "关闭热点");
                return "热点已关闭";
            } catch (Throwable t) {
                return "关闭失败: " + root(t);
            }
        }

        boolean on = "on".equals(action);
        boolean set = "set".equals(action);
        if (!on && !set) return "未知操作: " + action;

        try {
            WifiConfiguration cfg = null;
            if ((set || on) && ssid != null && ssid.trim().length() > 0) {
                cfg = buildConfig(ssid.trim(), pass);
            } else if (set) {
                return "SSID 不能为空";
            }

            // 开热点前必须关掉 station 模式，否则多数 ROM 直接返回 false
            if (w.isWifiEnabled()) {
                w.setWifiEnabled(false);
                sleep(900);
            }

            Method enable = m("setWifiApEnabled");
            Object r;
            try {
                r = enable.invoke(w, cfg, true);
                if (!Boolean.TRUE.equals(r) && cfg != null) {
                    // 部分 ROM 不接受新建的配置对象，退回「用系统已存的配置」
                    r = enable.invoke(w, null, true);
                }
            } catch (Throwable t1) {
                r = enable.invoke(w, null, true);
            }
            sleep(1800);

            boolean ok = Boolean.TRUE.equals(r);
            String ssidNow = "";
            WifiConfiguration c2 = apConfig(w);
            if (c2 != null) ssidNow = nz(c2.SSID);
            Logs.add("wifi", (set ? "设置并开启热点 " : "开启热点 ") + ssidNow + " → " + ok);
            if (!ok) return "系统拒绝开启热点（可能是 ROM 限制或射频占用）";
            return (set ? "热点已更新：" : "热点已开启") + (ssidNow.length() > 0 ? ssidNow : "");
        } catch (Throwable t) {
            String e = root(t);
            Logs.add("wifi", "apply 失败: " + e);
            return "操作失败: " + e;
        }
    }

    private static WifiConfiguration buildConfig(String ssid, String pass) {
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = ssid;
        if (pass == null || pass.length() == 0) {
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            cfg.preSharedKey = pass;
            cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            cfg.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            cfg.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            cfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            cfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        return cfg;
    }

    // ---------- 客户端 ----------

    /**
     * 热点客户端：读 /proc/net/arp，但必须按网卡过滤。
     * RNDIS/USB 网段同样是 192.168.42.x，不过滤会把插着 USB 的那台电脑当成 WiFi 客户端。
     */
    public static JSONArray clients() {
        JSONArray arr = new JSONArray();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line = r.readLine();          // 表头
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 6) continue;
                String ip = f[0];
                String mac = f[3];
                String dev = f[5];
                if (!ip.startsWith(HOTSPOT_NET)) continue;
                if ("00:00:00:00:00:00".equals(mac)) continue;
                if (!isWifiIface(dev)) continue;
                JSONObject o = new JSONObject();
                o.put("ip", ip);
                o.put("mac", mac);
                o.put("iface", dev);
                o.put("flag", f[2]);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return arr;
    }

    /** wlan0 / ap0 / softap 之类才算热点网卡，rndis0 / rmnet* / usb0 排除。 */
    private static boolean isWifiIface(String dev) {
        if (dev == null) return false;
        String d = dev.toLowerCase();
        if (d.startsWith("rndis") || d.startsWith("rmnet") || d.startsWith("usb")
                || d.startsWith("rev_") || d.equals("lo") || d.startsWith("dummy")) {
            return false;
        }
        return d.startsWith("wlan") || d.startsWith("ap") || d.startsWith("softap")
                || d.startsWith("swlan") || d.startsWith("p2p");
    }

    /** 当前存在哪些无线网卡，热点起不来时用来判断是没开还是没有模块。 */
    public static JSONArray wifiIfaces() {
        JSONArray arr = new JSONArray();
        File[] ns = new File("/sys/class/net").listFiles();
        if (ns == null) return arr;
        for (int i = 0; i < ns.length; i++) {
            String n = ns[i].getName().toLowerCase();
            if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("p2p")
                    || n.startsWith("softap")) {
                arr.put(ns[i].getName());
            }
        }
        return arr;
    }

    // ---------- 扫频 ----------

    public static JSONArray scan(Context ctx) {
        JSONArray arr = new JSONArray();
        WifiManager w = wm(ctx);
        if (w == null) return arr;
        try {
            if (!w.isWifiEnabled()) {
                Logs.add("wifi", wifiIfaces().length() == 0
                        ? "扫频跳过：本机没有无线网卡（wlan0 不存在）"
                        : "扫频跳过：station 模式未开启（热点开启时无法扫频）");
                return arr;
            }
            w.startScan();
            sleep(2000);
            List<ScanResult> list = w.getScanResults();
            if (list == null) return arr;
            List<ScanResult> copy = new ArrayList<ScanResult>(list);
            Collections.sort(copy, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return b.level - a.level;
                }
            });
            int n = Math.min(copy.size(), 40);
            for (int i = 0; i < n; i++) {
                ScanResult s = copy.get(i);
                JSONObject o = new JSONObject();
                o.put("ssid", nz(s.SSID));
                o.put("bssid", nz(s.BSSID));
                o.put("level", s.level);
                o.put("freq", s.frequency);
                o.put("channel", frequencyToChannel(s.frequency));
                o.put("cap", nz(s.capabilities));
                o.put("secured", nz(s.capabilities).indexOf("WPA") >= 0
                        || nz(s.capabilities).indexOf("WEP") >= 0);
                arr.put(o);
            }
            Logs.add("wifi", "扫频完成，共 " + copy.size() + " 个");
        } catch (Throwable t) {
            Logs.add("wifi", "扫频失败: " + t);
        }
        return arr;
    }

    private static int frequencyToChannel(int f) {
        if (f >= 2412 && f <= 2484) return f == 2484 ? 14 : (f - 2412) / 5 + 1;
        if (f >= 5170 && f <= 5825) return (f - 5000) / 5;
        return 0;
    }

    // ---------- 工具 ----------

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
