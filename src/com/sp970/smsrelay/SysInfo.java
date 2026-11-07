package com.sp970.smsrelay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 系统信息：型号/基带/信号/电量/存储/流量/uptime/网卡。
 *
 * 信号强度没有公开接口，靠 RelayService 启动时注册的 PhoneStateListener 缓存最后一次读数，
 * 面板来查时直接返回缓存（不额外耗电）。
 */
public final class SysInfo {

    private static volatile int asu = -1;          // 0..31(gsm) / 0..97(lte)，负数表示未知
    private static volatile int dbm = 0;
    private static volatile int level = 0;         // 0..4
    private static volatile int rsrp = 0;          // LTE 专用，dBm
    private static volatile int rsrq = 0;          // LTE 质量，dB
    private static volatile int rssnr = 0;         // LTE 信噪比，1/10 dB
    private static volatile int lteSs = -1;        // LTE 0..63 刻度
    private static volatile String tech = "";      // gsm / lte / gsm|lte
    private static volatile String operator = "";
    private static volatile String serviceState = "";
    private static String propClassOk = null;
    private static Method propGet;
    private static PhoneStateListener listener;

    /** RelayService 在主线程调用（PhoneStateListener 需要当前线程有 Looper）。 */
    public static synchronized void listen(Context ctx) {
        if (listener != null) return;
        try {
            final TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            listener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength ss) {
                    if (ss == null) return;
                    try {
                        applySignal(ss);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onServiceStateChanged(ServiceState s) {
                    try {
                        operator = s.getOperatorAlphaLong();
                        int st = s.getState();
                        serviceState = st == ServiceState.STATE_IN_SERVICE ? "已注册"
                                : st == ServiceState.STATE_EMERGENCY_ONLY ? "仅紧急呼叫"
                                : st == ServiceState.STATE_POWER_OFF ? "飞行模式" : "无服务";
                    } catch (Throwable ignored) {
                    }
                }
            };
            tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                    | PhoneStateListener.LISTEN_SERVICE_STATE);
            Logs.add("sys", "信号监听已注册");
        } catch (Throwable t) {
            Logs.add("sys", "信号监听注册失败: " + t);
        }
    }

    /**
     * 取一次信号读数。
     *
     * Android 4.4 的 SignalStrength 只有 GSM 的公开取值，而纯 LTE 网络下
     * {@link SignalStrength#getGsmSignalStrength()} 恒为 99（未知），真实读数在 LTE 的 rsrp 上。
     * 本机实测原文（字段顺序 4.x 起稳定）：
     * <pre>
     * SignalStrength: 99 0 -120 -160 -120 -1 -1 25 -96 -15 0 2147483647 2147483647 gsm|lte
     *                 ↑gsm ↑ber ↑cdmaDbm ...  ↑lteSs ↑rsrp ↑rsrq ↑rssnr ↑cqi ↑ta  ↑制式
     * </pre>
     * 所以这里解析 toString()，不碰隐藏 API。最后一段是制式，直接拿来标 gsm/lte。
     */
    static void applySignal(SignalStrength ss) {
        String s = ss.toString();
        int i = s.indexOf(':');
        if (i < 0) return;
        String rest = s.substring(i + 1).trim();
        String[] p = rest.split("\\s+");

        // 末尾的 "gsm|lte" 是制式标记，不是数字
        for (int k = 0; k < p.length; k++) {
            String t = p[k].toLowerCase();
            if (t.indexOf("lte") >= 0 || t.indexOf("gsm") >= 0) {
                tech = p[k];
                String[] q = new String[k];
                System.arraycopy(p, 0, q, 0, k);
                p = q;
                break;
            }
        }

        int gsm = tok(p, 0);       // GSM asu，99 = 未知
        int cdmaDbm = tok(p, 2);   // 2G/3G CDMA
        int cdmaEcio = tok(p, 3);
        int lteS = tok(p, 7);      // LTE 0..63
        int r = tok(p, 8);         // LTE rsrp
        int q = tok(p, 9);         // LTE rsrq
        int sn = tok(p, 10);       // LTE rssnr

        lteSs = lteS;
        rsrq = q;
        rssnr = sn;

        if (r >= -140 && r <= -44) {                // 有 LTE 读数时以它为准
            rsrp = r;
            asu = r + 140;                          // 0~97
            dbm = r;
            level = r >= -85 ? 4 : r >= -95 ? 3 : r >= -105 ? 2
                    : r >= -115 ? 1 : 0;
            return;
        }
        rsrp = 0;
        if (gsm >= 0 && gsm <= 31) {                // 2G / 3G 用 GSM 刻度
            asu = gsm;
            dbm = -113 + 2 * gsm;
            level = gsm <= 1 ? 0 : gsm <= 4 ? 1 : gsm <= 8 ? 2 : gsm <= 12 ? 3 : 4;
            return;
        }
        if (cdmaDbm < 0 && cdmaDbm > -120) {        // 电信 2G / 3G
            dbm = cdmaDbm;
            asu = Math.max(0, Math.min(16, (cdmaDbm + 113) / 2));
            level = cdmaDbm >= -75 ? 4 : cdmaDbm >= -85 ? 3 : cdmaDbm >= -95 ? 2
                    : cdmaDbm >= -100 ? 1 : 0;
            return;
        }
        if (lteS >= 0 && lteS <= 63) {              // 只有 LTE 刻度没有 rsrp 的 ROM
            lteSs = lteS;
            level = lteS >= 25 ? 4 : lteS >= 18 ? 3 : lteS >= 12 ? 2 : lteS >= 5 ? 1 : 0;
            asu = -1;
            dbm = 0;
            return;
        }
        asu = -1;
        dbm = 0;
        level = 0;
    }

    /** 第 i 个数字字段（高版本可能带小数）；取不到返回 Integer.MIN_VALUE。 */
    private static int tok(String[] p, int i) {
        if (i >= p.length) return Integer.MIN_VALUE;
        try {
            double d = Double.parseDouble(p[i]);
            if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) d;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    public static synchronized void unlisten(Context ctx) {
        if (listener == null) return;
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) tm.listen(listener, PhoneStateListener.LISTEN_NONE);
        } catch (Throwable ignored) {
        }
        listener = null;
    }

    /** 面板概览用的轻量读数，不构造整份快照。 */
    public static int batteryPercent(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return scale > 0 ? level * 100 / scale : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int signalLevel() {
        return level;
    }

    public static JSONObject snapshot(Context ctx, AppConfig cfg) {
        JSONObject o = new JSONObject();
        try {
            o.put("device", cfg == null ? "" : cfg.device);
            o.put("model", Build.MODEL);
            o.put("brand", Build.BRAND);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("build", Build.DISPLAY);
            o.put("baseband", prop("gsm.version.baseband"));
            o.put("serial", prop("ro.serialno"));
            o.put("board", prop("ro.board.platform"));
            o.put("usb", usbMode());

            long upMs = SystemClock.elapsedRealtime();
            o.put("uptimeMs", upMs);
            o.put("uptime", uptimeText(upMs));

            // 这台是 USB 直供、没有电池，电量/充电状态没有意义，不再采集
            o.put("signal", signal(ctx));
            o.put("operator", operator);
            o.put("service", serviceState);
            o.put("netType", netType(ctx));
            o.put("sim", simState(ctx));
            o.put("simOperator", simOperator(ctx));
            o.put("roaming", roaming(ctx));
            o.put("storage", storage());
            o.put("traffic", traffic());
            o.put("memory", memory());
            o.put("cpu", cpu());
            o.put("temps", temps());
            o.put("mem", meminfo());
            o.put("nets", nets());
            o.put("keepalive", MobileKeepalive.status(ctx));
        } catch (Throwable t) {
            Logs.add("sys", "snapshot 失败: " + t);
        }
        return o;
    }

    /**
     * 当前 USB 模式。
     *
     * 不能读 persist.sys.usb.config：那是「出厂默认组合名」（本机为
     * charging,none,adb，dumpsys usb 里显示成 Default Functions），
     * 既不是内核挂上的 function 列表，也不代表现在插着 USB adb。
     * sys.usb.state / Current Functions 同样不可靠——原厂 init.qcom.usb.rc:618
     * 的 rndis,adb 规则漏写 adb 却把 state 伪报成 rndis,adb。
     * 只有 /sys/class/android_usb/android0/functions（Kernel function list）
     * 反映内核实际挂载的组合，如 rndis 或 rndis,adb。
     */
    private static String usbMode() {
        String fn = readFirstLine("/sys/class/android_usb/android0/functions");
        if (fn != null && fn.length() > 0) return fn;
        String cfg = prop("sys.usb.config");
        if (cfg.length() > 0) return cfg;
        return prop("persist.sys.usb.config");
    }

    /** 读小文本文件第一行；不存在/读不了返回 null。 */
    private static String readFirstLine(String path) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new FileReader(path));
            String line = r.readLine();
            r.close();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * CPU 负载：/proc/loadavg + 在线核数/当前频率（/sys/devices/system/cpu）。
     * 注意这台的核是 mpdecision 按负载热插拔的，coresOnline 是瞬时值不是固定值。
     */
    private static JSONObject cpu() {
        JSONObject o = new JSONObject();
        try {
            String la = readFirstLine("/proc/loadavg");
            if (la != null) {
                String[] f = la.trim().split("\\s+");
                if (f.length >= 3) {
                    o.put("load1", f[0]);
                    o.put("load5", f[1]);
                    o.put("load15", f[2]);
                }
                if (f.length >= 4 && f[3].contains("/")) o.put("procs", f[3]);
            }
            int online = 0, total = 0;
            long freqKhz = -1;
            for (int i = 0; i < 8; i++) {
                if (!new File("/sys/devices/system/cpu/cpu" + i).exists()) break;
                total++;
                String on = readFirstLine("/sys/devices/system/cpu/cpu" + i + "/online");
                if (on == null || "1".equals(on)) online++;   // cpu0 通常没有 online 节点，视为常在
                if (freqKhz < 0) {
                    String f = readFirstLine(
                            "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                    try { freqKhz = f == null ? -1 : Long.parseLong(f); } catch (Throwable ignored) {}
                }
            }
            o.put("coresTotal", total);
            o.put("coresOnline", online);
            if (freqKhz > 0) o.put("freqKhz", freqKhz);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 温度：/sys/class/thermal/thermal_zone*。
     * msm8916 温区 type 归类：tsens 开头 → cpu（多个分区取最高），modem 或 pa → modem，
     * pm8916 开头 → pmic，其余按原 type 名保留。
     * 单位坑（本机实测）：tsens 报摄氏度（42=42℃），pm8916/bms 报毫摄氏度（34001=34.0℃），
     * 按量级区分（<1000 当摄氏度，>=1000 当毫摄氏度）。
     * 没有电池的机器 bms/battery 温区是电量计残留，直接跳过；读数 <=0 或 >=110 视为无效。
     */
    private static JSONObject temps() {
        JSONObject o = new JSONObject();
        try {
            File[] zs = new File("/sys/class/thermal").listFiles();
            if (zs == null) return o;
            java.util.Arrays.sort(zs);
            for (int i = 0; i < zs.length; i++) {
                if (!zs[i].getName().startsWith("thermal_zone")) continue;
                String type = readFirstLine(zs[i] + "/type");
                String temp = readFirstLine(zs[i] + "/temp");
                if (type == null || temp == null || type.length() == 0) continue;
                String tl = type.toLowerCase();
                // 无电池：bms/battery 温区（电量计）没有参考意义
                if (tl.equals("bms") || tl.startsWith("battery") || tl.startsWith("bat_")) continue;
                long raw;
                try { raw = Long.parseLong(temp.trim()); } catch (Throwable t) { continue; }
                double v = raw >= 1000 ? raw / 1000.0 : (double) raw;
                if (v <= 0 || v >= 110) continue;
                v = Math.round(v * 10) / 10.0;
                String key;
                if (tl.startsWith("tsens")) key = "cpu";
                else if (tl.startsWith("modem") || tl.equals("pa")) key = "modem";
                else if (tl.startsWith("pm8916")) key = "pmic";
                else key = type;
                if (!o.has(key)) { o.put(key, v); continue; }
                // tsens 有多个分区（cpu0-4/gpu/wlan…），CPU 取最高更有参考价值
                if ("cpu".equals(key)) {
                    try { o.put(key, Math.max(o.getDouble(key), v)); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 整机内存（MB）：/proc/meminfo。
     * 内核 3.10 没有 MemAvailable，用 MemTotal - MemFree - Buffers - Cached 近似。
     */
    private static JSONObject meminfo() {
        JSONObject o = new JSONObject();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            long total = -1, free = -1, buffers = 0, cached = 0, avail = -1;
            while ((line = r.readLine()) != null) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String k = line.substring(0, c).trim();
                String v = line.substring(c + 1).trim().split("\\s+")[0];
                long n;
                try { n = Long.parseLong(v); } catch (Throwable t) { continue; }
                if ("MemTotal".equals(k)) total = n;
                else if ("MemFree".equals(k)) free = n;
                else if ("Buffers".equals(k)) buffers = n;
                else if ("Cached".equals(k)) cached = n;
                else if ("MemAvailable".equals(k)) avail = n;
            }
            r.close();
            if (total >= 0) o.put("totalMb", total / 1024);
            long used = -1;
            if (avail >= 0 && total >= 0) {
                used = total - avail;
                o.put("availMb", avail / 1024);
            } else if (free >= 0 && total >= 0) {
                used = total - free - buffers - cached;
                o.put("availMb", (free + buffers + cached) / 1024);
            }
            if (used >= 0) o.put("usedMb", used / 1024);
        } catch (Throwable ignored) {
        }
        return o;
    }


    private static JSONObject signal(Context ctx) {
        JSONObject s = new JSONObject();
        try {
            s.put("asu", asu);
            s.put("dbm", dbm);
            s.put("level", level);
            s.put("tech", tech);
            if (rsrp != 0) s.put("rsrp", rsrp);
            if (rsrq != 0) s.put("rsrq", rsrq);
            if (rssnr != 0) s.put("rssnr", rssnr);
            if (lteSs >= 0) s.put("lteSs", lteSs);
            s.put("text", asu < 0 ? "未知"
                    : level >= 4 ? "很强" : level == 3 ? "良好" : level == 2 ? "一般"
                    : level == 1 ? "较弱" : "很弱");
            // WiFi 那一侧的 RSSI，蜂窝和 WiFi 分开看才不糊涂
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled() && wm.getConnectionInfo() != null) {
                    s.put("wifiRssi", wm.getConnectionInfo().getRssi());
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static String simOperator(Context ctx) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String num = tm.getSimOperator();               // 46000
            String name = tm.getSimOperatorName();          // CMCC
            if (num == null) num = "";
            if (name == null) name = "";
            if (num.length() == 0) return name;
            return name.length() == 0 ? num : name + " " + num;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean roaming(Context ctx) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.isNetworkRoaming();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String netType(Context ctx) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            switch (tm.getNetworkType()) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE: return "2G";
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G";
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_1xRTT: return "CDMA";
                case 13: return "LTE";   // NETWORK_TYPE_LTE
                default: return "未知";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static String simState(Context ctx) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            switch (tm.getSimState()) {
                case TelephonyManager.SIM_STATE_READY: return "就绪";
                case TelephonyManager.SIM_STATE_ABSENT: return "无卡";
                case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "需 PIN";
                case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "需 PUK";
                case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "网络锁";
                default: return "未知";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONArray storage() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(part("内部 /data", "/data"));
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) arr.put(part("外置 " + ext.getAbsolutePath(), ext.getAbsolutePath()));
        } catch (Throwable ignored) {
        }
        return arr;
    }

    private static JSONObject part(String name, String path) throws Exception {
        JSONObject o = new JSONObject();
        StatFs fs = new StatFs(path);
        long block = fs.getBlockSize();
        long total = fs.getBlockCountLong() * block;
        long free = fs.getAvailableBlocksLong() * block;
        o.put("name", name);
        o.put("total", total);
        o.put("free", free);
        o.put("used", total - free);
        o.put("percent", total > 0 ? (int) ((total - free) * 100 / total) : 0);
        return o;
    }

    private static JSONObject traffic() {
        JSONObject o = new JSONObject();
        try {
            o.put("rx", TrafficStats.getTotalRxBytes());
            o.put("tx", TrafficStats.getTotalTxBytes());
            o.put("mobileRx", TrafficStats.getMobileRxBytes());
            o.put("mobileTx", TrafficStats.getMobileTxBytes());
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject memory() {
        JSONObject o = new JSONObject();
        try {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory();
            long free = r.freeMemory();
            o.put("heapTotal", total);
            o.put("heapUsed", total - free);
            o.put("procRss", procRss());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 本进程 RSS（KB），读 /proc/self/status。 */
    private static long procRss() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/status"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    r.close();
                    String v = line.substring(6).trim().split(" ")[0];
                    return Long.parseLong(v);
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static JSONArray nets() {
        JSONArray arr = new JSONArray();
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (int i = 0; i < ifs.size(); i++) {
                NetworkInterface ni = ifs.get(i);
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                String ip = "";
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (int j = 0; j < addrs.size(); j++) {
                    InetAddress a = addrs.get(j);
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        ip = a.getHostAddress();
                        break;
                    }
                }
                if (ip.length() == 0) continue;
                JSONObject o = new JSONObject();
                o.put("name", ni.getName());
                o.put("ip", ip);
                o.put("mac", mac(ni));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    /** 面板提示/局域网访问用：优先返回热点网段地址。 */
    public static String primaryIp() {
        try {
            JSONArray a = nets();
            String fallback = "";
            for (int i = 0; i < a.length(); i++) {
                String ip = a.getJSONObject(i).optString("ip", "");
                if (ip.length() == 0) continue;
                if (ip.startsWith("192.168.42.")) return ip;
                if (fallback.length() == 0) fallback = ip;
            }
            return fallback.length() > 0 ? fallback : "192.168.42.129";
        } catch (Throwable t) {
            return "192.168.42.129";
        }
    }

    private static String mac(NetworkInterface ni) {
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
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读 system property（反射 android.os.SystemProperties）。 */
    public static String prop(String key) {
        try {
            if (propClassOk == null) {
                Class<?> c = Class.forName("android.os.SystemProperties");
                propGet = c.getMethod("get", String.class, String.class);
                propClassOk = "1";
            }
            if (propGet == null) return "";
            Object v = propGet.invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            propClassOk = "0";
            return "";
        }
    }

    public static String uptimeText(long ms) {
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return d + "天" + h + "小时";
        if (h > 0) return h + "小时" + m + "分";
        return m + "分钟";
    }
}
