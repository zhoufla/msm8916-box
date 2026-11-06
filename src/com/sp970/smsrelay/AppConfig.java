package com.sp970.smsrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 多通道配置。
 *
 * 优先级：/sdcard/smsrelay.conf > SharedPreferences > 内置示例。
 * conf 是 JSON：{"device":"SP970","channels":[{...},{...}]}
 * 同时兼容旧的单通道 key=value 写法。
 */
public final class AppConfig {

    public static final String PREFS = "relay";
    public static final String CONF_NAME = "smsrelay.conf";
    private static final String KEY_JSON = "config_json";

    public String device = Build.MODEL;
    public List<Channel> channels = new ArrayList<Channel>();

    public static AppConfig load(Context ctx) {
        AppConfig c = new AppConfig();
        String json = readConfFile();
        if (json == null) json = prefs(ctx).getString(KEY_JSON, null);
        if (json != null) c.parse(json);
        if (c.channels.isEmpty()) c.migrateLegacy(ctx);
        if (c.channels.isEmpty()) c.parse(sampleJson());
        return c;
    }

    /** 界面展示用：当前生效的原始 JSON。 */
    public static String effectiveJson(Context ctx) {
        String json = readConfFile();
        if (json == null) json = prefs(ctx).getString(KEY_JSON, null);
        if (json == null) {
            AppConfig c = new AppConfig();
            c.migrateLegacy(ctx);
            if (!c.channels.isEmpty()) return toJsonString(c.device, c.channels);
            return sampleJson();
        }
        return json;
    }

    public static void saveJson(Context ctx, String json) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putString(KEY_JSON, json);
        e.commit();
    }

    public Channel find(String name) {
        for (int i = 0; i < channels.size(); i++) {
            Channel c = channels.get(i);
            if (c.name != null && c.name.equals(name)) return c;
        }
        return null;
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < channels.size(); i++) out.add(channels.get(i).name);
        return out;
    }

    public int activeCount() {
        int n = 0;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).valid()) n++;
        return n;
    }

    // ---------- 内部 ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String d = root.optString("device", "");
            if (d.length() > 0) device = d;
            JSONArray arr = root.optJSONArray("channels");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Channel c = Channel.fromJson(o);
                if (c.name == null || c.name.length() == 0) c.name = "ch" + (i + 1);
                channels.add(c);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 兼容最初的单通道 key=value 配置。 */
    private void migrateLegacy(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String url = sp.getString("url", "");
        if (url == null || !url.startsWith("http")) {
            if (readLegacyFileUrl() != null) {
                Channel c = new Channel();
                c.name = "默认";
                c.type = "webhook";
                c.url = readLegacyFileUrl();
                channels.add(c);
                saveJson(ctx, toJsonString(device, channels));
            }
            return;
        }
        Channel c = new Channel();
        c.name = "默认";
        c.type = sp.getString("type", "webhook");
        c.url = url;
        c.secret = sp.getString("secret", "");
        c.token = sp.getString("token", "");
        c.prefix = sp.getString("prefix", "【短信】");
        channels.add(c);
        saveJson(ctx, toJsonString(device, channels));
    }

    private static String readLegacyFileUrl() {
        BufferedReader r = null;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), CONF_NAME);
            if (!f.exists()) return null;
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("url=")) return line.substring(4).trim();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String readConfFile() {
        BufferedReader r = null;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), CONF_NAME);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("#")) continue;
                sb.append(line).append('\n');
            }
            String s = sb.toString().trim();
            return s.startsWith("{") ? s : null;   // 非 JSON 的旧格式交给 migrateLegacy
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    public static String toJsonString(String device, List<Channel> chs) {
        try {
            JSONObject root = new JSONObject();
            root.put("device", device);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < chs.size(); i++) arr.put(chs.get(i).toJson());
            root.put("channels", arr);
            return root.toString(2);
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String sampleJson() {
        return "{\n" +
                "  \"device\": \"" + Build.MODEL + "\",\n" +
                "  \"channels\": [\n" +
                "    {\n" +
                "      \"name\": \"运维群\",\n" +
                "      \"type\": \"dingtalk\",\n" +
                "      \"url\": \"https://oapi.dingtalk.com/robot/send?access_token=请替换\",\n" +
                "      \"secret\": \"SEC请替换\",\n" +
                "      \"prefix\": \"【短信】\",\n" +
                "      \"enabled\": false\n" +
                "    },\n" +
                "    {\n" +
                "      \"name\": \"自建服务\",\n" +
                "      \"type\": \"webhook\",\n" +
                "      \"url\": \"http://192.168.1.10:8088/api/sms\",\n" +
                "      \"token\": \"\",\n" +
                "      \"enabled\": false\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    /** 首次部署时生成一份示例配置，避免设备上什么都没有。 */
    public static void writeSample() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), CONF_NAME);
            if (f.exists()) return;
            FileWriter w = new FileWriter(f);
            w.write("# 多通道配置示例，改完重启服务生效\n");
            w.write(sampleJson());
            w.write("\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
