package com.sp970.smsrelay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.List;

/**
 * LTE 短信保活（移植自 SMSRelay8916 的 smsrelay-keepalive）。
 *
 * 为什么需要：
 *   本机 2015 年的 MPSS 基带在 LTE 空闲态（ECM-IDLE）会漏掉网络寻呼，
 *   手机看起来一切正常，但短信要等几分钟、甚至下一次主动上网才下来。
 *   解决办法是让一条移动数据 PDP 一直在线，并每 10 秒发一个极小的 ICMP 包，
 *   让基带始终停留在连接态。实测流量约 0.5 MB/天。
 *
 * 为什么不用「打开移动数据」：
 *   WiFi 才是默认上行；打开移动数据后 Android 4.4 仍会在 WiFi 在线时把
 *   mobile 默认网络断掉。这里走 ConnectivityManager 的 MOBILE_HIPRI 特性，
 *   在 WiFi 保持默认路由的同时额外拉起一条 rmnet 数据链路。
 *
 * 两个关键点（真机验证）：
 *   1. FeatureUser 默认 60 秒过期（ConnectivityService 读
 *      android.telephony.apn-restore，缺省 60000ms）。这里把它设成约 23 天，
 *      避免链路每分钟被拆一次。
 *   2. app uid 10016 没有 raw socket 权限，ping 必须经 relay-helper 以 root
 *      执行：busybox ping -I rmnet0 …（不能用 -I <源地址>，那样会走 wlan0）。
 */
public final class MobileKeepalive {

    private static final String TAG = "keepalive";
    private static final int TYPE_MOBILE = ConnectivityManager.TYPE_MOBILE;
    private static final String FEATURE = "enableHIPRI";
    private static final String PROP = "android.telephony.apn-restore";
    private static final String BB = "/system/xbin/busybox";

    /** 一条 FeatureUser 最长能延迟多久；int 上限约 24.8 天。 */
    private static final int MAX_RESTORE_MS = 2000000000;
    private static final long RECOVER_COOLDOWN_MS = 5L * 60 * 1000;
    private static final long DISABLED_POLL_MS = 30L * 1000;

    private static final IBinder TOKEN = new Binder();
    private static final Object LOCK = new Object();

    private static volatile boolean running;
    private static Thread worker;

    private static volatile long startedAt;
    private static volatile String phase = "未启动";
    private static volatile boolean hipri;
    private static volatile String ifaceIp = "";
    private static volatile boolean restoreSet;
    private static volatile boolean lastPingOk;
    private static volatile long lastPingAt;
    private static volatile long lastOkAt;
    private static volatile long lastRecoverAt;
    private static volatile long pings;
    private static volatile long oks;
    private static volatile int fails;
    private static volatile String lastError = "";
    private static volatile int lastReqRc = -99;
    private static volatile long lastReqLogAt;

    private static Method mStart;
    private static Method mStop;
    private static Object icm;

    private MobileKeepalive() {
    }

    /** 由 RelayService 启动；重复调用无副作用。 */
    public static synchronized void start(Context ctx) {
        if (worker != null && worker.isAlive()) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(app);
            }
        }, "mobile-keepalive");
        worker.setDaemon(true);
        worker.start();
    }

    /** 配置变更后立刻重新读取（不必等下一轮）。 */
    public static void wake() {
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    public static synchronized void stop(Context ctx) {
        running = false;
        wake();
        worker = null;
        try {
            // 主动释放，避免停止服务后 HIPRI 还挂到天荒地老
            if (restoreSet) stopFeature();
        } catch (Throwable ignored) {
        }
        restoreSet = false;
        phase = "已停止";
        hipri = false;
        ifaceIp = "";
    }

    /** 面板/接口展示用。 */
    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AppConfig cfg = AppConfig.load(ctx);
            o.put("enabled", cfg.kaEnabled);
            o.put("running", running);
            o.put("phase", phase);
            o.put("iface", cfg.kaIface);
            o.put("ip", ifaceIp);
            o.put("hipri", hipri);
            o.put("intervalSec", cfg.kaIntervalSec);
            o.put("target", cfg.kaTarget);
            o.put("restoreMs", cfg.kaRestoreMs);
            o.put("restoreSet", restoreSet);
            o.put("lastPingAt", lastPingAt);
            o.put("lastOkAt", lastOkAt);
            o.put("lastPingOk", lastPingOk);
            o.put("pings", pings);
            o.put("oks", oks);
            o.put("fails", fails);
            o.put("lastError", lastError);
            o.put("uptimeMs", running && startedAt > 0
                    ? SystemClock.elapsedRealtime() - startedAt : 0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * /api/keepalive 下拉框用：移动数据网卡候选（rmnet0/ccmni0/pdp0…），
     * 总是包含当前配置值，设备刚开机网卡还没建出来时也能显示。
     */
    public static JSONArray ifaces(Context ctx) {
        JSONArray a = new JSONArray();
        java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        String cur = null;
        try {
            cur = AppConfig.load(ctx).kaIface;
            if (cur != null && cur.trim().length() > 0) cur = cur.trim();
        } catch (Throwable ignored) {
        }
        try {
            java.util.Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                String n = ni.getName();
                if (n == null) continue;
                String l = n.toLowerCase();
                if (!(l.startsWith("rmnet") || l.startsWith("ccmni") || l.startsWith("pdp")
                        || l.startsWith("wwan") || l.startsWith("seth"))) continue;
                // 只列出已经起来或拿到 IPv4 的网卡，避免把 rmnet1..7 这类占位设备全塞进下拉框；
                // 当前配置值下面会无条件补进去。
                boolean up = false;
                boolean has4 = false;
                try {
                    up = ni.isUp();
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof Inet4Address) {
                            has4 = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!n.equals(cur) && !up && !has4) continue;
                set.add(n);
            }
        } catch (Throwable ignored) {
        }
        if (cur != null && cur.length() > 0) set.add(cur);
        if (set.isEmpty()) set.add("rmnet0");
        for (String s : set) a.put(s);
        return a;
    }

    // ---------- 线程主体 ----------

    private static void loop(Context app) {
        startedAt = SystemClock.elapsedRealtime();
        boolean wasEnabled = false;
        Logs.add(TAG, "保活线程启动");

        while (running) {
            AppConfig cfg = null;
            try {
                cfg = AppConfig.load(app);
            } catch (Throwable t) {
                lastError = "读配置失败: " + t;
            }

            boolean enabled = cfg != null && cfg.kaEnabled;
            if (!enabled) {
                if (wasEnabled) {
                    try {
                        stopFeature();
                    } catch (Throwable ignored) {
                    }
                    clearRestore(app);
                    restoreSet = false;
                    hipri = false;
                    ifaceIp = "";
                    lastError = "";
                    Logs.add(TAG, "保活已关闭");
                }
                wasEnabled = false;
                phase = "已关闭";
                pause(DISABLED_POLL_MS);
                continue;
            }

            if (!wasEnabled) {
                wasEnabled = true;
                startedAt = SystemClock.elapsedRealtime();
                fails = 0;
                pings = 0;
                oks = 0;
                lastError = "";
                phase = "运行";
                Logs.add(TAG, "保活启动：iface=" + cfg.kaIface
                        + " 目标=" + cfg.kaTarget
                        + " 间隔=" + cfg.kaIntervalSec + "s"
                        + " 过期=" + cfg.kaRestoreMs + "ms");
            }

            long begin = SystemClock.elapsedRealtime();
            try {
                if (!restoreSet) restoreSet = applyRestore(app, cfg.kaRestoreMs);

                boolean connected = hipriConnected(app);
                if (!connected) {
                    int rc = startFeature();
                    long now = SystemClock.elapsedRealtime();
                    if (rc == 0 || rc == 1) {
                        if (lastReqRc != rc) {
                            Logs.add(TAG, "HIPRI 请求已接受（返回 " + rc + "，等待链路建立）");
                        }
                        lastReqRc = rc;
                        lastReqLogAt = now;
                        // 刚请求完通常还没 CONNECTED，下一轮再看真实状态
                        connected = hipriConnected(app);
                    } else {
                        // 2 = APN_TYPE_NOT_AVAILABLE（开机/附着阶段常见），<0 = 失败
                        if (rc != lastReqRc || now - lastReqLogAt > 5L * 60 * 1000) {
                            Logs.add(TAG, "HIPRI 暂不可用（返回 " + rc + "），稍后重试");
                            lastReqLogAt = now;
                        }
                        lastReqRc = rc;
                        lastError = "HIPRI 暂不可用: " + rc;
                    }
                } else {
                    lastReqRc = -99;
                }
                hipri = connected;
                ifaceIp = ipv4Of(cfg.kaIface);

                if (connected) {
                    ping(app, cfg);
                } else {
                    lastError = "HIPRI 未连接（rmnet0 " + (ifaceIp.length() > 0 ? ifaceIp : "无地址") + "）";
                }
            } catch (Throwable t) {
                lastError = shortMsg(t);
            }

            phase = hipri ? "运行" : "重连中";
            long used = SystemClock.elapsedRealtime() - begin;
            long wait = Math.max(1000, cfg.kaIntervalSec * 1000L - used);
            pause(wait);
        }

        Logs.add(TAG, "保活线程退出");
    }

    /**
     * 把 apn-restore 设成很大的值。必须在第一次 startUsingNetworkFeature 之前生效，
     * 否则系统已经按 60 秒排好了过期消息（旧消息不会被新请求取消）。
     */
    private static boolean applyRestore(Context app, int ms) {
        int v = ms;
        if (v < 60000 || v > MAX_RESTORE_MS) v = MAX_RESTORE_MS;
        Root.Result r = Root.exec(app, "setprop " + PROP + " " + v
                + "; getprop " + PROP);
        if (r.error.length() > 0) {
            lastError = "设置 " + PROP + " 失败: " + r.error;
            return false;
        }
        boolean ok = r.out.trim().endsWith(String.valueOf(v));
        if (!ok) lastError = "设置 " + PROP + " 后读回不一致: " + r.out.trim();
        return ok;
    }

    /** 关闭保活时恢复默认 60s，别让其他 App 的 HIPRI 也跟着挂 23 天。 */
    private static void clearRestore(Context app) {
        try {
            Root.exec(app, "setprop " + PROP + " 60000");
        } catch (Throwable ignored) {
        }
    }

    private static void ping(Context app, AppConfig cfg) {
        String iface = safeName(cfg.kaIface, "rmnet0");
        String target = safeName(cfg.kaTarget, "223.5.5.5");
        String cmd = BB + " ping -I " + iface + " -c 1 -W 3 " + target;
        Root.Result r = Root.exec(app, cmd);

        pings++;
        lastPingAt = System.currentTimeMillis();

        // helper 本身不可用时不把账算到链路头上，等下一轮再试
        if (r.error.length() > 0) {
            lastError = "root helper 不可用: " + r.error;
            return;
        }

        boolean ok = r.exit == 0;
        lastPingOk = ok;
        if (ok) {
            if (fails >= 3) {
                Logs.add(TAG, "链路已恢复（此前连续 " + fails + " 次失败）");
                Alerts.event(app, cfg, "keepalive", false,
                        "LTE 链路已恢复（此前连续 " + fails + " 次 ping 失败）");
            }
            fails = 0;
            oks++;
            lastOkAt = lastPingAt;
            lastError = "";
            return;
        }

        fails++;
        String err = r.out.trim();
        if (err.length() == 0) err = "exit=" + r.exit;
        lastError = "ping 失败: " + (err.length() > 180 ? err.substring(0, 180) : err);
        if (fails == 3 || (fails > 3 && fails % 30 == 0)) {
            Logs.add(TAG, "连续 " + fails + " 次 ping 不通：" + lastError);
        }

        // 连续失败主动重建一次 HIPRI（参考脚本的 ifdown/ifup，这里换成停/启特性）
        long now = SystemClock.elapsedRealtime();
        if (fails >= 6 && now - lastRecoverAt > RECOVER_COOLDOWN_MS) {
            lastRecoverAt = now;
            int f = fails;
            Logs.add(TAG, "连续 " + fails + " 次失败，重建 HIPRI");
            try {
                stopFeature();
                pause(1500);
                int rc = startFeature();
                if (rc == 0 || rc == 1) {
                    fails = 0;
                    Alerts.event(app, cfg, "keepalive", true,
                            "LTE 链路连续 " + f + " 次 ping 失败，已重建 HIPRI");
                }
            } catch (Throwable t) {
                lastError = "重建 HIPRI 失败: " + shortMsg(t);
                Alerts.event(app, cfg, "keepalive", true,
                        "LTE 链路连续 " + f + " 次 ping 失败，重建 HIPRI 失败：" + shortMsg(t));
            }
        }
    }

    // ---------- 系统接口 ----------

    private static boolean hipriConnected(Context app) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_HIPRI);
            return ni != null && ni.isConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** app 能拿到 rmnet0 的 IPv4，用来展示；ping 一定要按网卡名走。 */
    private static String ipv4Of(String name) {
        String iface = safeName(name, "rmnet0");
        try {
            NetworkInterface ni = NetworkInterface.getByName(iface);
            if (ni == null) return "";
            List<InterfaceAddress> list = ni.getInterfaceAddresses();
            for (int i = 0; i < list.size(); i++) {
                InetAddress a = list.get(i).getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 通过隐藏的 IConnectivityManager 调 startUsingNetworkFeature。
     * 必须自己持有一个 Binder token：公开 API 每次 new Binder()，重复调用会在
     * system_server 里堆出一串 FeatureUser，而且没法精确 stop。
     */
    private static synchronized Object icm() throws Exception {
        if (icm != null) return icm;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class)
                .invoke(null, "connectivity");
        if (b == null) throw new IllegalStateException("connectivity 服务不存在");
        Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
        Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        Method start = svc.getClass().getMethod("startUsingNetworkFeature",
                int.class, String.class, IBinder.class);
        Method stop = svc.getClass().getMethod("stopUsingNetworkFeature",
                int.class, String.class);
        mStart = start;
        mStop = stop;
        icm = svc;
        return icm;
    }

    private static int startFeature() throws Exception {
        Object s = icm();
        Object r = mStart.invoke(s, TYPE_MOBILE, FEATURE, TOKEN);
        return r instanceof Integer ? (Integer) r : -1;
    }

    private static int stopFeature() throws Exception {
        Object s = icm();
        Object r = mStop.invoke(s, TYPE_MOBILE, FEATURE);
        return r instanceof Integer ? (Integer) r : -1;
    }

    // ---------- 小工具 ----------

    private static void pause(long ms) {
        synchronized (LOCK) {
            try {
                LOCK.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** 配置里的接口名/目标地址只允许安全字符，避免拼 shell 时被注入。 */
    private static String safeName(String s, String def) {
        if (s == null) return def;
        String v = s.trim();
        if (v.length() == 0 || v.length() > 64) return def;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '.' || c == '-' || c == '_'
                    || c == ':';
            if (!ok) return def;
        }
        return v;
    }

    private static String shortMsg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }
}
