package com.sp970.smsrelay;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** 面板「运行日志」：内存环形缓冲，同时打 logcat。 */
public final class Logs {

    private static final int MAX = 200;
    private static final LinkedList<JSONObject> BUF = new LinkedList<JSONObject>();
    private static final SimpleDateFormat HMS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    public static void add(String tag, String msg) {
        String m = msg == null ? "" : msg;
        Log.i("SmsRelay", "[" + tag + "] " + m);
        JSONObject o = new JSONObject();
        try {
            o.put("t", HMS.format(new Date()));
            o.put("tag", tag == null ? "" : tag);
            o.put("msg", m.length() > 400 ? m.substring(0, 400) : m);
        } catch (Throwable ignored) {
        }
        synchronized (BUF) {
            BUF.addFirst(o);
            while (BUF.size() > MAX) BUF.removeLast();
        }
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
