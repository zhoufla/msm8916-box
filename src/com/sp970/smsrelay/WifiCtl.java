package com.sp970.smsrelay;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * WiFi / 热点控制。
 *
 * 关键事实（本机实测，SP970_B / 4.4.4 / QCA pronto）：
 *  1. wlan0 是「按需加载」的——WiFi 与热点都关着时 wlan 模块不 insmod，
 *     /sys/class/net 里根本没有 wlan0（只剩 rndis0/rmnet*）。
 *     所以「看不到 wlan0」不等于「没有无线网卡」，判断要看 wlan.ko 是否存在。
 *  2. 单射频，station 与热点不能同时开。驱动只有一个模式开关
 *     /sys/module/wlan/parameters/con_mode（0=STA、1=AP）。实测：热点开着时开 station 被框架
 *     直接拒绝；station 开着时开热点能成功，但 station 会被强制关掉。
 *     所以开热点前必须先把 station 关掉，开 station 前必须先关热点。
 *  3. setWifiApEnabled / isWifiApEnabled / getWifiApConfiguration 在 4.4 都是 hidden API，
 *     只能反射。注意 setWifiApEnabled 的签名是 (WifiConfiguration, boolean)——
 *     反射取方法必须带上参数类型，否则 NoSuchMethodException（v1.1 就踩过这个坑）。
 */
public final class WifiCtl {

    /** 单射频机器上热点占用射频时的统一提示（面板直接 toast 出去，别只说「系统拒绝」）。 */
    private static final String STA_BUSY_AP =
            "热点正在占用无线射频，这台机器只有一颗射频、WiFi 和热点不能同时开；请先关掉热点再开 WiFi";


    private static WifiManager wm(Context ctx) {
        return (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    private static Method m(String name, Class<?>... args) throws Exception {
        Method m = WifiManager.class.getMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    /** setWifiApEnabled(WifiConfiguration, boolean) —— 参数类型不能省。 */
    private static Method apEnableMethod() throws Exception {
        return m("setWifiApEnabled", WifiConfiguration.class, boolean.class);
    }

    // ---------- 状态 ----------

    public static JSONObject state(Context ctx) {
        JSONObject o = new JSONObject();
        WifiManager w = wm(ctx);
        try {
            boolean ap = Boolean.TRUE.equals(apOn(w));
            boolean sta = w != null && w.isWifiEnabled();
            boolean driver = driverLoaded();
            boolean hw = hardwarePresent();

            o.put("ap", apOn(w));
            o.put("wifiOn", sta);
            o.put("driver", driver);
            o.put("hardware", hw);
            // 当前模式：ap / sta / off，面板用它做互斥页签渲染
            o.put("mode", ap ? "ap" : (sta ? "sta" : "off"));

            WifiConfiguration c = apConfig(w);
            // buildConfig 现在写带引号的 SSID（supplicant 规范格式），展示时去掉引号
            o.put("ssid", c == null ? "" : WifiCfg.stripQuotes(nz(c.SSID)));
            o.put("secured", c != null && c.preSharedKey != null && c.preSharedKey.length() > 0);

            JSONArray ifs = wifiIfaces();
            o.put("ifaces", ifs);
            o.put("clients", clients());
            o.put("station", station(w, ifs));

            String ip = "";
            for (int i = 0; i < ifs.length(); i++) {
                String n = ifs.getJSONObject(i).optString("name", "");
                String a = ifs.getJSONObject(i).optString("ip", "");
                if (a.length() > 0 && n.startsWith("wlan")) { ip = a; break; }
            }
            o.put("apIp", ip);
            o.put("clientIp", ip);

            String hint = hint(ap, sta, driver, hw, ifs.length());
            if (hint.length() > 0) o.put("hint", hint);
        } catch (Throwable t) {
            Logs.add("wifi", "state 失败: " + t);
        }
        return o;
    }

    /** 一句话说明当前为什么不能干活，比「没扫到」有用。 */
    private static String hint(boolean ap, boolean sta, boolean driver, boolean hw, int ifaces) {
        if (!hw) {
            return "本机没有 WiFi 硬件（找不到 wlan.ko，wifi.interface 为空），热点/扫频不可用";
        }
        if (!driver) {
            return "WiFi 关闭中，wlan 模块未加载（4.4 按需 insmod，wlan0 消失属正常）——"
                    + "打开 station 模式或热点后自动加载";
        }
        if (ap) return "热点已开启（单射频，此时无法扫频；要扫描请先关热点）";
        if (!sta) return "WiFi 已关闭；打开 station 模式后可以扫频/连网";
        if (ifaces == 0) return "驱动已加载但网卡尚未就绪，稍等几秒再刷新";
        return "";
    }

    private static Object apOn(WifiManager w) {
        if (w == null) return null;
        try {
            Method m = m("isWifiApEnabled");
            Object r = m.invoke(w);
            return r instanceof Boolean ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static WifiConfiguration apConfig(WifiManager w) {
        if (w == null) return null;
        try {
            Method m = m("getWifiApConfiguration");
            Object r = m.invoke(w);
            return r instanceof WifiConfiguration ? (WifiConfiguration) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** station（连路由器）那一侧的信息；未连接时 connected=false。 */
    private static JSONObject station(WifiManager w, JSONArray ifs) {
        JSONObject s = new JSONObject();
        try {
            s.put("connected", false);
            WifiInfo wi = w == null ? null : w.getConnectionInfo();
            if (wi != null) {
                String ssid = nz(wi.getSSID());
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                boolean known = ssid.length() > 0 && !"<unknown ssid>".equals(ssid)
                        && !"0x".equals(ssid);
                int rssi = wi.getRssi();
                boolean rssiOk = rssi <= 0 && rssi > -127;    // -127/-200 是 4.4 的「未知」
                s.put("ssid", known ? ssid : "");
                s.put("bssid", nz(wi.getBSSID()));
                s.put("rssi", rssiOk ? rssi : 0);
                s.put("level", rssiOk ? WifiManager.calculateSignalLevel(rssi, 5) : 0);
                s.put("linkSpeed", wi.getLinkSpeed() > 0 ? wi.getLinkSpeed() : 0);
                s.put("netId", wi.getNetworkId());
                // 这里只放 station 自己拿到的地址；热点模式下的 wlan0 地址在 ifaces 里，
                // 混进来会让「已连网络」看起来连着 192.168.0.1
                s.put("ip", known ? intToIp(wi.getIpAddress()) : "");
                s.put("connected", known && wi.getNetworkId() >= 0);
            }
            for (int i = 0; i < ifs.length(); i++) {
                JSONObject n = ifs.getJSONObject(i);
                if (n.optString("name", "").startsWith("wlan")) {
                    s.put("mac", n.optString("mac", ""));
                    break;
                }
            }
        } catch (Throwable t) {
            Logs.add("wifi", "station 读取失败: " + t);
        }
        return s;
    }

    private static String intToIp(int d) {
        if (d == 0) return "";
        return (d & 0xFF) + "." + ((d >> 8) & 0xFF) + "." + ((d >> 16) & 0xFF) + "."
                + ((d >> 24) & 0xFF);
    }

    // ---------- 开关 / 改配置 ----------

    /**
     * @param action on | off | set（兼容老面板） | ap_on | ap_off | ap_set | sta_on | sta_off
     * @return {ok, message, state}
     */
    public static synchronized JSONObject apply(Context ctx, String action,
                                                String ssid, String pass) {
        WifiManager w = wm(ctx);
        if (w == null) return result(ctx, w, false, "WiFi 服务不可用");
        action = action == null ? "" : action.trim();

        if ("sta_on".equals(action) || "sta_off".equals(action)) {
            boolean on = "sta_on".equals(action);
            try {
                // 单射频：热点占着 wlan0 时框架会直接拒绝开 station，先说清楚原因，别只报「系统拒绝」
                if (on && Boolean.TRUE.equals(apOn(w))) {
                    Logs.add("wifi", "拒绝开 station：热点正在占用射频");
                    return result(ctx, w, false, STA_BUSY_AP);
                }
                boolean cur = w.isWifiEnabled();
                if (cur == on) {
                    return result(ctx, w, true, on ? "WiFi 已处于开启状态" : "WiFi 已处于关闭状态");
                }
                w.setWifiEnabled(on);
                sleep(on ? 2600 : 1400);
                boolean now = w.isWifiEnabled();
                Logs.add("wifi", (on ? "开启" : "关闭") + " station 模式 now=" + now);
                if (now != on) {
                    // 竞态兜底：这期间热点又起来了
                    if (on && Boolean.TRUE.equals(apOn(w))) {
                        return result(ctx, w, false, STA_BUSY_AP);
                    }
                    return result(ctx, w, false, (on ? "开启" : "关闭") + " WiFi 失败：系统拒绝");
                }
                String msg = on
                        ? (driverLoaded() ? "WiFi 已开启，可以扫描周边网络"
                                          : "WiFi 已开启，驱动加载中，稍等几秒")
                        : "WiFi 已关闭（wlan 模块随之卸载，wlan0 消失属正常）";
                return result(ctx, w, true, msg);
            } catch (Throwable t) {
                return result(ctx, w, false, "操作失败: " + root(t));
            }
        }

        if ("sta_connect".equals(action) || "sta_disconnect".equals(action)) {
            try {
                if (Boolean.TRUE.equals(apOn(w))) {
                    return result(ctx, w, false, STA_BUSY_AP);
                }
                // 连接动作允许自动把 WiFi 打开（面板上点「连接」不该被要求先点「开启」）
                if (!w.isWifiEnabled()) {
                    w.setWifiEnabled(true);
                    sleep(2600);
                    if (!w.isWifiEnabled()) {
                        return result(ctx, w, false, "WiFi 开启失败：系统拒绝");
                    }
                }
                if ("sta_disconnect".equals(action)) {
                    boolean r = w.disconnect();
                    sleep(900);
                    Logs.add("wifi", "断开 station → " + r);
                    return result(ctx, w, true,
                            r ? "已断开当前 WiFi 连接" : "断开命令被系统拒绝（可能本就未连接）");
                }
                if (ssid == null || ssid.trim().length() == 0) {
                    return result(ctx, w, false, "SSID 不能为空");
                }
                String target = ssid.trim();
                Logs.add("wifi", "sta_connect: " + target);

                // 1. 先翻已存配置：同 SSID 直接启用，避免每次连接都堆一条新配置
                int netId = -1;
                java.util.List<WifiConfiguration> saved = w.getConfiguredNetworks();
                if (saved != null) {
                    for (int i = 0; i < saved.size(); i++) {
                        WifiConfiguration c = saved.get(i);
                        if (c == null || c.SSID == null) continue;
                        if (WifiCfg.ssidEquals(c.SSID, target)) { netId = c.networkId; break; }
                    }
                }
                // 2. 没有已存配置（或带密码重连）就新建；同 SSID 已存但带了新密码时先删旧的
                if (pass != null && pass.length() > 0 && netId >= 0) {
                    w.removeNetwork(netId);
                    netId = -1;
                }
                if (netId < 0) {
                    netId = w.addNetwork(buildConfig(target, pass));
                    if (netId < 0 && pass != null && pass.length() > 0) {
                        // 本机 ROM 的 wifi JNI 给 supplicant 发 psk 时不包双引号（标准 4.4 会包），
                        // supplicant 把 passphrase 当 64-hex 校验 → "Invalid PSK" → 框架删网络。
                        // 先裸试兼容标准 ROM，失败后手动包引号再试（WifiCfg 注释有完整分析）。
                        Logs.add("wifi", "addNetwork 失败，psk 包引号重试（本机 ROM 不自动加引号）");
                        netId = w.addNetwork(buildConfig(target, WifiCfg.quotePsk(pass)));
                    }
                    if (netId < 0) {
                        return result(ctx, w, false,
                                "保存网络配置失败（系统拒绝）：密码可能有特殊字符或不被本机支持（WPA 密码应为 8–63 位）");
                    }
                }
                if (!w.enableNetwork(netId, true)) {
                    return result(ctx, w, false, "启用网络失败（系统拒绝）");
                }
                boolean re = w.reconnect();
                Logs.add("wifi", "reconnect → " + re + " netId=" + netId);

                // 3. 轮询等连接：SSID 对上且拿到 IP 才算成功，最长 ~18s
                String curSsid;
                for (int i = 0; i < 12; i++) {
                    sleep(1500);
                    WifiInfo wi = w.getConnectionInfo();
                    if (wi != null) {
                        curSsid = WifiCfg.stripQuotes(nz(wi.getSSID()));
                        if (target.equals(curSsid) && wi.getIpAddress() != 0) {
                            String ip = intToIp(wi.getIpAddress());
                            Logs.add("wifi", "已连接 " + target + " ip=" + ip);
                            return result(ctx, w, true,
                                    "已连接 " + target + "（IP " + ip + "）");
                        }
                    }
                }
                return result(ctx, w, false,
                        "未能连接到 " + target + "（密码错误 / 信号太弱 / 路由器未放行）；"
                        + "当前状态见下方连接信息");
            } catch (Throwable t) {
                return result(ctx, w, false, "连接失败: " + root(t));
            }
        }

        if ("off".equals(action) || "ap_off".equals(action)) {
            try {
                apEnableMethod().invoke(w, null, false);
                sleep(1500);
                boolean still = Boolean.TRUE.equals(apOn(w));
                Logs.add("wifi", "关闭热点 → ap=" + still);
                return result(ctx, w, !still, still ? "热点关闭失败：系统仍报告开启" : "热点已关闭");
            } catch (Throwable t) {
                return result(ctx, w, false, "关闭失败: " + root(t));
            }
        }

        boolean on = "on".equals(action) || "ap_on".equals(action);
        boolean set = "set".equals(action) || "ap_set".equals(action);
        if (!on && !set) return result(ctx, w, false, "未知操作: " + action);

        try {
            WifiConfiguration cfg = null;
            if (ssid != null && ssid.trim().length() > 0) {
                cfg = buildConfig(ssid.trim(), pass);
            } else if (set) {
                return result(ctx, w, false, "SSID 不能为空");
            }

            // 开热点前必须关掉 station 模式，否则多数 ROM 直接返回 false
            boolean staWasOn = w.isWifiEnabled();
            if (staWasOn) {
                w.setWifiEnabled(false);
                sleep(1000);
            }

            Method enable = apEnableMethod();
            // 热点已经开着时，直接再 enable 会被框架忽略（配置不会更新），必须先关一次
            if (Boolean.TRUE.equals(apOn(w))) {
                try {
                    enable.invoke(w, null, false);
                    sleep(1400);
                } catch (Throwable t0) {
                    Logs.add("wifi", "先关热点失败（继续尝试开启）: " + root(t0));
                }
            }

            Object r;
            try {
                r = enable.invoke(w, cfg, true);
                if (!Boolean.TRUE.equals(r) && cfg != null) {
                    // 部分 ROM 不接受新建的配置对象，退回「用系统已存的配置」
                    r = enable.invoke(w, null, true);
                }
            } catch (Throwable t1) {
                Logs.add("wifi", "带配置开热点失败，退回系统配置: " + root(t1));
                r = enable.invoke(w, null, true);
            }
            sleep(2200);

            boolean ok = Boolean.TRUE.equals(r) && Boolean.TRUE.equals(apOn(w));
            sleep(600);
            String ssidNow = "";
            WifiConfiguration c2 = apConfig(w);
            if (c2 != null) ssidNow = nz(c2.SSID);
            Logs.add("wifi", (set ? "设置并开启热点 " : "开启热点 ") + ssidNow + " → " + ok);
            if (!ok) {
                return result(ctx, w, false, "系统拒绝开启热点（可能是 ROM 限制或射频被占用）");
            }
            return result(ctx, w, true,
                    (set ? "热点已更新：" : "热点已开启")
                            + (ssidNow.length() > 0 ? ssidNow : "")
                            + (staWasOn ? "（已自动关闭 WiFi：单射频不能与热点同时使用）" : ""));
        } catch (Throwable t) {
            String e = root(t);
            Logs.add("wifi", "apply 失败: " + e);
            return result(ctx, w, false, "操作失败: " + e);
        }
    }

    private static JSONObject result(Context ctx, WifiManager w, boolean ok, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("message", msg);
            o.put("state", state(ctx));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static WifiConfiguration buildConfig(String ssid, String pass) {
        WifiConfiguration cfg = new WifiConfiguration();
        // SSID 必须带引号（WifiConfiguration 规范格式），不带引号 supplicant 不认
        cfg.SSID = WifiCfg.stripQuotes(ssid).length() == 0 ? ssid
                : "\"" + WifiCfg.stripQuotes(ssid) + "\"";
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
     * 热点客户端：读 /proc/net/arp。
     *
     * 两个坑（本机实测）：
     *  1. 热点网段**不固定**——这台是 192.168.0.1/24，而 RNDIS 是 192.168.42.x。
     *     所以不能按写死网段过滤，只能按网卡过滤（rndis0/rmnet* 一律排除）。
     *  2. 热点自己（192.168.0.1）也可能出现在 ARP 里，要按本机地址排掉。
     */
    public static JSONArray clients() {
        JSONArray arr = new JSONArray();
        JSONArray own = wifiIfaces();
        List<String> ownIps = new ArrayList<String>();
        for (int i = 0; i < own.length(); i++) {
            String ip = own.optJSONObject(i) == null ? "" : own.optJSONObject(i).optString("ip", "");
            if (ip.length() > 0) ownIps.add(ip);
        }
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
                if ("00:00:00:00:00:00".equals(mac)) continue;
                if (!isWifiIface(dev)) continue;
                if (ownIps.contains(ip)) continue;       // 热点自己的地址
                if (!arpComplete(f[2])) continue;        // 0x0/0x4 是未解析或仅静态的残留
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

    /** ARP 标志位：0x2 表示已解析（ATF_COM），0x0 是未完成的残留条目。 */
    private static boolean arpComplete(String flag) {
        try {
            String f = flag.toLowerCase().replace("0x", "");
            return (Integer.parseInt(f, 16) & 0x2) != 0;
        } catch (Throwable t) {
            return true;                                  // 解析不了就放行，宁多勿漏
        }
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

    /** 当前存在哪些无线网卡（含地址/MAC），热点起不来时用来判断是没开还是没模块。 */
    public static JSONArray wifiIfaces() {
        JSONArray arr = new JSONArray();
        try {
            List<NetworkInterface> all =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (int i = 0; i < all.size(); i++) {
                NetworkInterface ni = all.get(i);
                if (ni == null) continue;
                String n = ni.getName().toLowerCase();
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("p2p")
                        || n.startsWith("softap"))) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", ni.getName());
                o.put("mac", macOf(ni));
                o.put("up", ni.isUp());
                o.put("ip", ipv4Of(ni));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    private static String ipv4Of(NetworkInterface ni) {
        try {
            List<InetAddress> as = Collections.list(ni.getInetAddresses());
            for (int i = 0; i < as.size(); i++) {
                InetAddress a = as.get(i);
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String macOf(NetworkInterface ni) {
        try {
            byte[] b = ni.getHardwareAddress();
            if (b == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                if (i > 0) sb.append(':');
                int v = b[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 驱动是否**已加载**（≠ 硬件是否存在）。
     * 关掉 WiFi 后 4.4 会 rmmod wlan，网卡随之消失，这时这里是 false。
     */
    public static boolean driverLoaded() {
        if (wifiIfaces().length() > 0) return true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/modules"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("wlan ")) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 这台机器到底有没有 WiFi 硬件：只要有 wlan 模块文件就算有（加载与否不影响）。 */
    public static boolean hardwarePresent() {
        try {
            if (new File("/system/lib/modules/wlan.ko").exists()) return true;
            if (new File("/system/lib/modules/pronto/pronto_wlan.ko").exists()) return true;
        } catch (Throwable ignored) {
        }
        return !nz(SysInfo.prop("wifi.interface")).isEmpty();
    }

    // ---------- 扫频 ----------

    /**
     * 扫频。返回 {ok, list, hint}：
     * 单射频机型里热点开着就扫不了，这时给的是明确原因而不是空列表。
     */
    public static JSONObject scan(Context ctx) {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        WifiManager w = wm(ctx);
        try {
            o.put("list", arr);
            if (w == null) {
                o.put("ok", false);
                o.put("error", "WiFi 服务不可用");
                return o;
            }
            if (Boolean.TRUE.equals(apOn(w))) {
                Logs.add("wifi", "扫频跳过：热点开着（单射频）");
                o.put("ok", true);
                o.put("hint", "热点开启时无法扫频（单射频），请先关热点再扫描");
                return o;
            }
            if (!w.isWifiEnabled()) {
                w.setWifiEnabled(true);
                sleep(2600);
                if (!w.isWifiEnabled()) {
                    Logs.add("wifi", "扫频跳过：station 未开启且自动开启失败");
                    o.put("ok", true);
                    o.put("hint", "WiFi 未开启，自动开启失败，请手动打开 station 模式");
                    return o;
                }
                o.put("autoEnabled", true);
            }
            w.startScan();
            sleep(2200);
            List<ScanResult> list = w.getScanResults();
            List<ScanResult> copy = list == null
                    ? new ArrayList<ScanResult>() : new ArrayList<ScanResult>(list);
            Collections.sort(copy, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return b.level - a.level;
                }
            });
            int n = Math.min(copy.size(), 40);
            for (int i = 0; i < n; i++) {
                ScanResult s = copy.get(i);
                JSONObject e = new JSONObject();
                e.put("ssid", nz(s.SSID));
                e.put("bssid", nz(s.BSSID));
                e.put("level", s.level);
                e.put("freq", s.frequency);
                e.put("channel", frequencyToChannel(s.frequency));
                e.put("cap", nz(s.capabilities));
                e.put("secured", nz(s.capabilities).indexOf("WPA") >= 0
                        || nz(s.capabilities).indexOf("WEP") >= 0);
                arr.put(e);
            }
            o.put("ok", true);
            Logs.add("wifi", "扫频完成，共 " + copy.size() + " 个");
            if (copy.size() == 0) o.put("hint", "扫到 0 个，周围可能确实没有可用网络");
        } catch (Throwable t) {
            Logs.add("wifi", "扫频失败: " + t);
            try {
                o.put("ok", false);
                o.put("error", "扫频失败: " + t);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static int frequencyToChannel(int f) {
        if (f >= 2412 && f <= 2484) return f == 2484 ? 14 : (f - 2412) / 5 + 1;
        if (f >= 5170 && f <= 5825) return (f - 5000) / 5;
        return 0;
    }

    // ---------- 无线高级项（B2 第 19 项） ----------
    //
    // 真机实测（SP970-B / 4.4.4 / QCA pronto + 裁剪版 hostapd）：
    //  - hostapd 的 ctrl socket 在 /data/misc/wifi/hostapd/wlan0，必须用 -p 指定目录，
    //    否则 hostapd_cli 走默认路径连不上，会被误判成「热点没开」
    //  - 这个 hostapd 构建只有 mib / sta / all_sta / deauthenticate / disassociate /
    //    sa_query / wps_* / get_config —— 没有 set、没有 reload、没有 status
    //    （`set channel 11` 会回 OK，但那是未识别命令的空应答，不能当成功）
    //  - AP 模式下 iwpriv 换了一套 ioctl：hideSSID / setMaxAssoc / setAutoChannel /
    //    setClearAcl 可用；setMaxTxPower、setsapchannels 全是 Invalid command
    //
    // 所以这里只做四件**真能生效**的事；改信道 / 改功率 / MAC 白名单明确不做 ——
    // 框架每次开热点都会重写 hostapd.conf，改了会被覆盖，做了等于骗人。
    private static final String HAPD = "/system/bin/hostapd_cli";
    private static final String HAPD_DIR = "/data/misc/wifi/hostapd";

    private static final String HAPD_CMD = HAPD + " -i wlan0 -p " + HAPD_DIR + " ";

    /** 无线详情：热点配置（只读）、关联客户端、驱动侧限制。 */
    public static JSONObject advInfo(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String cmd =
                    "echo '##SOCK'; ls " + HAPD_DIR + "/wlan0 2>/dev/null; " +
                    "echo '##CONMODE'; cat /sys/module/wlan/parameters/con_mode 2>/dev/null; " +
                    "echo '##MAXASSOC'; iwpriv wlan0 getMaxAssoc 2>&1; " +
                    "echo '##AUTOCH'; iwpriv wlan0 getAutoChannel 2>&1; " +
                    "echo '##GETCFG'; " + HAPD_CMD + "get_config 2>&1; " +
                    "echo '##STA'; " + HAPD_CMD + "all_sta 2>&1; " +
                    "echo '##END'";
            Root.Result r = Root.exec(ctx, cmd);
            if (r.error.length() > 0) throw new IllegalStateException(r.error);

            String out = r.out;
            boolean sock = sec(out, "SOCK").trim().length() > 0;
            o.put("ok", true);
            o.put("hostapdOk", sock);
            o.put("conMode", sec(out, "CONMODE").trim());
            o.put("maxAssoc", digits(sec(out, "MAXASSOC")));
            o.put("autoChannel", digits(sec(out, "AUTOCH")));

            String cfg = sec(out, "GETCFG").trim();
            String[][] kv = new String[][]{{"ssid", "ssid"}, {"bssid", "bssid"},
                    {"key_mgmt", "keyMgmt"}, {"channel", "channel"}, {"hw_mode", "hwMode"}};
            JSONObject conf = new JSONObject();
            for (int i = 0; i < kv.length; i++) {
                String v = kvOf(cfg, kv[i][0]);
                if (v.length() > 0) conf.put(kv[i][1], v);
            }
            o.put("config", conf);

            // all_sta：每个客户端一段，第一行是 MAC
            JSONArray clients = new JSONArray();
            String sta = sec(out, "STA");
            String[] lines = sta.replace("\r", "").split("\n");
            JSONObject cur = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.length() == 0) continue;
                if (macOf(line).equals(line.toLowerCase()) && line.indexOf('=') < 0) {
                    cur = new JSONObject();
                    cur.put("mac", line.toLowerCase());
                    clients.put(cur);
                } else if (cur != null && line.indexOf('=') > 0) {
                    int e = line.indexOf('=');
                    String k = line.substring(0, e).trim();
                    String v = line.substring(e + 1).trim();
                    if (k.equals("rssi") || k.equals("aid") || k.equals("flags")) cur.put(k, v);
                }
            }
            o.put("clients", clients);
            o.put("hint", sock ? "" : "热点没开：hostapd 没有控制 socket，先开热点再来");
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("message", root(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /**
     * 无线高级动作（都要求热点正在运行）：
     *  hide     <0|1>  隐藏 SSID（iwpriv hideSSID）
     *  maxassoc <1-32> 最大关联客户端数（iwpriv setMaxAssoc）
     *  autoch   <0|1>  驱动自动选信道开关（iwpriv setAutoChannel）
     *  kick     <mac>  踢掉一个客户端（hostapd_cli deauthenticate）
     */
    public static JSONObject advApply(Context ctx, String action, String arg, String arg2) {
        JSONObject o = new JSONObject();
        action = action == null ? "" : action.trim();
        try {
            if ("kick".equals(action)) {
                String mac = macOf(arg);
                if (mac.length() == 0) throw new IllegalArgumentException("MAC 格式不对");
                requireAp(ctx);
                Root.Result r = Root.exec(ctx, HAPD_CMD + "deauthenticate " + mac + " 2>&1");
                boolean ok = r.out.indexOf("OK") >= 0;
                Logs.add("wifi", "踢客户端 " + mac + " → " + (ok ? "OK" : r.out.trim()));
                o.put("ok", ok);
                o.put("message", ok ? ("已踢掉 " + mac + "（它会自动重连一次）")
                        : ("踢除失败：" + r.out.trim()));
                return o;
            }

            if ("hide".equals(action)) {
                // arg=1 是隐藏（对应 hostapd 的 ignore_broadcast_ssid=1），0 是正常广播
                boolean hide = "1".equals(arg == null ? "" : arg.trim());
                requireAp(ctx);
                Root.Result r = Root.exec(ctx, "iwpriv wlan0 hideSSID " + (hide ? 1 : 0) + " 2>&1");
                Logs.add("wifi", "隐藏 SSID → " + hide);
                return ok(o, r, hide ? "SSID 已隐藏（要手动输名字才能连）" : "SSID 已恢复广播",
                        r.out.indexOf("Invalid") >= 0);
            }

            if ("maxassoc".equals(action)) {
                int n = Integer.parseInt(arg == null ? "" : arg.trim());
                if (n < 1 || n > 32) throw new IllegalArgumentException("最大关联数要在 1–32");
                requireAp(ctx);
                Root.Result r = Root.exec(ctx, "iwpriv wlan0 setMaxAssoc " + n + " 2>&1");
                String now = digits(Root.exec(ctx, "iwpriv wlan0 getMaxAssoc 2>&1").out);
                boolean good = now.equals(String.valueOf(n));
                o.put("ok", good);
                o.put("maxAssoc", now);
                o.put("message", good ? ("最大关联数已设为 " + n)
                        : ("设置未生效，当前 " + now + (r.out.trim().length() > 0 ? "：" + r.out.trim() : "")));
                Logs.add("wifi", "最大关联数 → " + now);
                return o;
            }

            if ("autoch".equals(action)) {
                boolean on = !"0".equals(arg == null ? "" : arg.trim());
                requireAp(ctx);
                Root.Result r = Root.exec(ctx, "iwpriv wlan0 setAutoChannel " + (on ? 1 : 0) + " 2>&1");
                String now = digits(Root.exec(ctx, "iwpriv wlan0 getAutoChannel 2>&1").out);
                o.put("ok", r.ok());
                o.put("autoChannel", now);
                o.put("message", "驱动自动选信道已" + (on ? "开启" : "关闭") + "（当前 " + now + "）");
                Logs.add("wifi", "自动信道 → " + on);
                return o;
            }

            throw new IllegalArgumentException("未知无线动作：" + action);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("message", root(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static JSONObject ok(JSONObject o, Root.Result r, String msg, boolean bad) {
        try {
            o.put("ok", !bad && r.error.length() == 0);
            o.put("message", bad ? "这台固件的驱动不支持这个命令：" + r.out.trim() : msg);
            if (r.out.trim().length() > 0) o.put("detail", clip(r.out.trim(), 300));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 热点没开就别往下走：socket 不在，hostapd_cli 一律连不上。 */
    private static void requireAp(Context ctx) {
        Root.Result r = Root.exec(ctx, "ls " + HAPD_DIR + "/wlan0 2>/dev/null");
        if (r.out.trim().length() == 0) {
            throw new IllegalStateException("热点没开：hostapd 没有控制 socket，先开热点再试");
        }
    }

    private static String sec(String out, String name) {
        String tag = "##" + name;
        int i = out.indexOf(tag + "\n");
        if (i < 0) return "";
        int start = i + tag.length() + 1;
        int end = out.indexOf("\n##", start);
        if (end < 0) end = out.length();
        return out.substring(start, end);
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String kvOf(String block, String key) {
        String[] lines = block.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return "";
    }

    /** 从 "wlan0 getMaxAssoc:10" 里取数字。 */
    private static String digits(String s) {
        if (s == null) return "";
        int c = s.lastIndexOf(':');
        String v = (c >= 0 ? s.substring(c + 1) : s).trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else break;
        }
        return sb.length() > 0 ? sb.toString() : v;
    }

    /** 从任意一行里抠出第一个 MAC 地址，非法回空串。 */
    private static String macOf(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matcher(s);
        return m.find() ? m.group(0).toLowerCase() : "";
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
