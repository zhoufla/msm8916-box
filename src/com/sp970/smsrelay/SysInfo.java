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

    private static volatile int asu = -1;          // 0..31，99/负数表示未知
    private static volatile int dbm = 0;
    private static volatile int level = 0;         // 0..4
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
                        int a = ss.getGsmSignalStrength();
                        if (a >= 0 && a <= 31) {
                            asu = a;
                            dbm = -113 + 2 * a;
                            level = a <= 1 ? 0 : a <= 4 ? 1 : a <= 8 ? 2 : a <= 12 ? 3 : 4;
                        } else {
                            asu = -1;
                            dbm = 0;
                            level = 0;
                        }
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
            o.put("adbd", prop("persist.sys.usb.config"));

            long upMs = SystemClock.elapsedRealtime();
            o.put("uptimeMs", upMs);
            o.put("uptime", uptimeText(upMs));

            o.put("battery", battery(ctx));
            o.put("signal", signal(ctx));
            o.put("operator", operator);
            o.put("service", serviceState);
            o.put("netType", netType(ctx));
            o.put("sim", simState(ctx));
            o.put("storage", storage());
            o.put("traffic", traffic());
            o.put("memory", memory());
            o.put("nets", nets());
        } catch (Throwable t) {
            Logs.add("sys", "snapshot 失败: " + t);
        }
        return o;
    }

    private static JSONObject battery(Context ctx) {
        JSONObject b = new JSONObject();
        try {
            Intent i = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return b;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            int volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            b.put("percent", scale > 0 ? level * 100 / scale : -1);
            b.put("voltage", volt);
            b.put("temp", temp / 10.0);
            b.put("status", status == BatteryManager.BATTERY_STATUS_CHARGING ? "充电中"
                    : status == BatteryManager.BATTERY_STATUS_FULL ? "已充满"
                    : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "放电中"
                    : status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "未充电" : "未知");
            b.put("plugged", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                    : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" : "电池");
        } catch (Throwable ignored) {
        }
        return b;
    }

    private static JSONObject signal(Context ctx) {
        JSONObject s = new JSONObject();
        try {
            s.put("asu", asu);
            s.put("dbm", dbm);
            s.put("level", level);
            s.put("text", asu < 0 ? "未知"
                    : level >= 4 ? "很强" : level == 3 ? "良好" : level == 2 ? "一般"
                    : level == 1 ? "较弱" : "很弱");
            // 没拿到蜂窝信号时补一个 WiFi RSSI，面板上至少有个数
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) {
                    s.put("wifiRssi", wm.getConnectionInfo().getRssi());
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return s;
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
