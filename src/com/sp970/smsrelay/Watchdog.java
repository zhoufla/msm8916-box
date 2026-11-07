package com.sp970.smsrelay;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 面板存活守护（App 内第一道防线的「探活 + 重建」部分）。
 *
 * 旧版 5 分钟闹钟只做 startService()：服务还活着时只走 onStartCommand，
 * 完全不会检查 8080 是否还在 accept。一旦 ServerSocket / accept 线程失效，
 * 面板就会「假在线」——内部 running=true、外部连不上，而且没人重建。
 * 这里用免认证的 /api/ping 做真实 HTTP 探活，失败就 WebPanel.restart()。
 *
 * 端口文件 /sdcard/Download/smsrelay-port 给 helper（独立 root 进程）读：
 * 面板在 8080 被占用时会自动顺延到 8085，光看配置文件猜不到实际端口。
 * 文件不存在 = 面板被有意禁用（web.enabled=false）或 App 从未启动，
 * helper 见到后直接跳过，避免把「用户主动关面板」误判成故障去 reboot。
 */
public final class Watchdog {

    public static final String ACTION_TICK = "com.sp970.smsrelay.WATCHDOG_TICK";
    private static final String TAG = "watchdog";
    private static final String PORT_NAME = "smsrelay-port";
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final Object LOCK = new Object();

    private Watchdog() {
    }

    private static File portFile() {
        return new File(Environment.getExternalStorageDirectory(), "Download/" + PORT_NAME);
    }

    /** WebPanel 启动成功后调用；helper 的探活脚本以此为准。 */
    public static void publishPort(int port) {
        synchronized (LOCK) {
            try {
                File f = portFile();
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory()) dir.mkdirs();
                FileWriter w = new FileWriter(f, false);
                try {
                    w.write(String.valueOf(port));
                    w.write("\n");
                } finally {
                    w.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 面板被配置关闭时调用：helper 见到文件消失就不再做任何处置。 */
    public static void clearPort() {
        synchronized (LOCK) {
            try {
                portFile().delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 由 AlarmReceiver / WatchdogReceiver 在后台线程调用。 */
    public static void probeAndRepair(Context ctx) {
        // 等 startService 的 onCreate 先把面板拉起来，别和它抢 WebPanel.LOCK
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        synchronized (LOCK) {
            try {
                AppConfig cfg = AppConfig.load(ctx);
                if (!cfg.webEnabled) {
                    clearPort();
                    return;
                }
                int port = WebPanel.runningPort();
                if (port > 0 && probe(port)) return;
                Logs.add(TAG, "面板探活失败（port=" + port + "），执行重建");
                String r = WebPanel.restart(ctx);
                Logs.add(TAG, "重建结果：" + r + "，当前端口=" + WebPanel.runningPort());
            } catch (Throwable t) {
                Logs.add(TAG, "探活异常：" + t);
            }
        }
    }

    private static boolean probe(int port) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/api/ping");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(PROBE_TIMEOUT_MS);
            c.setReadTimeout(PROBE_TIMEOUT_MS);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
