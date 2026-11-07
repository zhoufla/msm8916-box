package com.sp970.smsrelay;

import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * 面板「运行日志」：内存环形缓冲 + logcat + 落盘轮转。
 *
 * 为什么要落盘：本机内核没有 ramoops/pstore，/proc/last_kmsg 与 /sys/fs/pstore
 * 都不存在，而原来 Logs 只是内存缓冲——一旦硬挂或拔电，卡死前最后几分钟发生
 * 了什么就永远查不到（2026-11-07 事故复盘的直接教训）。落盘文件放在
 * /sdcard/Download，重刷 /data 不会带走，断电后也还在。
 *
 * 每行同时带墙钟与 uptime：设备 RTC 断电会归零、开机后 NITZ 校时会把系统时间
 * 往前跳一大截（实测约 34 小时），只有 uptime 是可靠时间轴。
 *
 * 写盘是同步的短操作（append 一行后立即关流），全程吞异常并节流降级，
 * 绝不能因为 IO 失败拖慢短信投递或面板请求。
 */
public final class Logs {

    public static final String LOG_NAME = "smsrelay-run.log";
    private static final int MAX = 200;
    private static final long MAX_BYTES = 256L * 1024;
    private static final int MAX_FILES = 3;
    private static final long RETRY_MS = 60L * 1000;

    private static final LinkedList<JSONObject> BUF = new LinkedList<JSONObject>();
    private static final SimpleDateFormat HMS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
    private static final Object FILE_LOCK = new Object();
    private static volatile long diskFailUntil;

    private Logs() {
    }

    /** 落盘文件位置（备份、排查、外部采集共用）。 */
    public static File file() {
        return new File(Environment.getExternalStorageDirectory(), "Download/" + LOG_NAME);
    }

    public static void add(String tag, String msg) {
        String m = msg == null ? "" : msg;
        String t = tag == null ? "" : tag;
        Log.i("SmsRelay", "[" + t + "] " + m);

        String stamp;
        try {
            stamp = HMS.format(new Date());
        } catch (Throwable ignored) {
            stamp = "??-?? ??:??:??";
        }
        JSONObject o = new JSONObject();
        try {
            o.put("t", stamp);
            o.put("tag", t);
            o.put("msg", m.length() > 400 ? m.substring(0, 400) : m);
        } catch (Throwable ignored) {
        }
        synchronized (BUF) {
            BUF.addFirst(o);
            while (BUF.size() > MAX) BUF.removeLast();
        }

        String body = m.length() > 400 ? m.substring(0, 400) : m;
        appendDisk(stamp + " up=" + (SystemClock.elapsedRealtime() / 1000L)
                + "s [" + t + "] " + oneLine(body));
    }

    private static String oneLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
    }

    private static void appendDisk(String line) {
        long now = SystemClock.elapsedRealtime();
        if (now < diskFailUntil) return;
        synchronized (FILE_LOCK) {
            try {
                File f = file();
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    diskFailUntil = now + RETRY_MS;
                    return;
                }
                if (f.exists() && f.length() > MAX_BYTES) rotate(f);
                FileWriter w = new FileWriter(f, true);
                try {
                    w.write(line);
                    w.write("\n");
                } finally {
                    w.close();
                }
            } catch (Throwable t) {
                // sdcard 未挂载/只读时别每条日志都抛异常，1 分钟后再试
                diskFailUntil = now + RETRY_MS;
                Log.w("SmsRelay", "日志落盘失败，1 分钟后重试: " + t);
            }
        }
    }

    /** 主文件 → .1 → .2 → .3，最老一份丢弃；重启后按时间倒序拼接即可读。 */
    private static void rotate(File f) {
        File old = new File(f.getPath() + "." + MAX_FILES);
        if (old.exists()) old.delete();
        for (int i = MAX_FILES - 1; i >= 1; i--) {
            File src = new File(f.getPath() + "." + i);
            if (src.exists()) src.renameTo(new File(f.getPath() + "." + (i + 1)));
        }
        f.renameTo(new File(f.getPath() + ".1"));
    }

    public static JSONArray json(int limit) {
        JSONArray arr = new JSONArray();
        synchronized (BUF) {
            int n = Math.min(limit, BUF.size());
            for (int i = 0; i < n; i++) arr.put(BUF.get(i));
        }
        return arr;
    }
}
