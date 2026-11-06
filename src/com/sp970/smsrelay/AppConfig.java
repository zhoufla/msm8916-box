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
 * 多通道配置 + 面板配置。
 *
 * 优先级：/sdcard/smsrelay.conf > SharedPreferences > 内置示例。
 * conf 是 JSON：
 *   {"device":"158","web":{"enabled":true,"port":8080,"user":"admin","pass":"admin"},
 *    "channels":[{...},{...}]}
 * 同时兼容旧的单通道 key=value 写法。
 */
public final class AppConfig {

    public static final String PREFS = "relay";
    public static final String CONF_NAME = "smsrelay.conf";
    private static final String KEY_JSON = "config_json";

    public String device = Build.MODEL;
    public List<Channel> channels = new ArrayList<Channel>();

    public boolean webEnabled = true;
    public int webPort = 8080;
    public String webUser = "admin";
    public String webPass = "admin";

    // 在线升级：mode = off | github | manifest
    public String updateMode = "github";
    public String updateRepo = "zhoufla/msm8916-box";
    public String updateManifest = "";
    public boolean updateAuto = true;
    public int updateIntervalH = 24;
    public boolean updateInsecure = false;
    /** 机器自带 web 后台的 root 通道凭据 user:pass，留空则不使用。 */
    public String updateWebkey = "admin:admin";

    public static AppConfig load(Context ctx) {
        AppConfig c = new AppConfig();
        String json = currentJson(ctx);
        if (json != null) c.parse(json);
        if (c.channels.isEmpty()) c.migrateLegacy(ctx);
        if (c.channels.isEmpty()) c.parse(sampleJson());
        return c;
    }

    /** 当前生效的原始 JSON 文本（面板展示/编辑用）。 */
    public static String effectiveJson(Context ctx) {
        String json = currentJson(ctx);
        if (json != null) return json;
        AppConfig c = new AppConfig();
        c.migrateLegacy(ctx);
        if (!c.channels.isEmpty()) return toJsonString(c.device, c.channels, c);
        return sampleJson();
    }

    private static String currentJson(Context ctx) {
        String json = readConfFile();
        if (json == null) json = prefs(ctx).getString(KEY_JSON, null);
        return json;
    }

    /**
     * 保存配置。
     * 注意：/sdcard/smsrelay.conf 存在时优先级最高，只写 SharedPreferences 会被它覆盖，
     * 所以文件存在就一起写，否则面板上改了等于没改。
     */
    public static void saveEffective(Context ctx, String json) {
        saveJson(ctx, json);
        File f = new File(Environment.getExternalStorageDirectory(), CONF_NAME);
        if (!f.exists()) return;
        FileWriter w = null;
        try {
            w = new FileWriter(f);
            w.write("# 由面板写入，改完重启服务生效\n");
            w.write(json);
            w.write("\n");
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) {}
        }
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

            JSONObject web = root.optJSONObject("web");
            if (web != null) {
                webEnabled = web.optBoolean("enabled", true);
                int p = web.optInt("port", 8080);
                if (p > 0 && p < 65536) webPort = p;
                String u = web.optString("user", "");
                if (u.length() > 0) webUser = u;
                String pw = web.optString("pass", "");
                if (pw.length() > 0) webPass = pw;
            }

            JSONObject up = root.optJSONObject("update");
            if (up != null) {
                String m = up.optString("mode", "github");
                if ("off".equals(m) || "github".equals(m) || "manifest".equals(m)) updateMode = m;
                String rp = up.optString("repo", "");
                if (rp.length() > 0) updateRepo = rp;
                updateManifest = up.optString("manifest", updateManifest);
                updateAuto = up.optBoolean("auto", updateAuto);
                int ih = up.optInt("intervalH", updateIntervalH);
                if (ih >= 1 && ih <= 24 * 30) updateIntervalH = ih;
                updateInsecure = up.optBoolean("insecure", updateInsecure);
                if (up.has("webkey")) updateWebkey = up.optString("webkey", "");
            }

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
                saveJson(ctx, toJsonString(device, channels, this));
            }
            return;
        }
        Channel c = new Channel();
        c.name = "默认";
        c.type = sp.getString("type", "webhook");
        c.url = url;
        c.secret = sp.getString("secret", "");
        c.token = sp.getString("token", "");
        // 旧版的 prefix 不再使用：正文改由通道模板生成（默认三行：号码 / 内容 / 设备名）
        channels.add(c);
        saveJson(ctx, toJsonString(device, channels, this));
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

    public static String toJsonString(String device, List<Channel> chs, AppConfig meta) {
        try {
            JSONObject root = new JSONObject();
            root.put("device", device);
            JSONObject web = new JSONObject();
            web.put("enabled", meta == null || meta.webEnabled);
            web.put("port", meta == null ? 8080 : meta.webPort);
            web.put("user", meta == null ? "admin" : meta.webUser);
            web.put("pass", meta == null ? "admin" : meta.webPass);
            root.put("web", web);
            JSONObject up = new JSONObject();
            up.put("mode", meta == null ? "github" : meta.updateMode);
            up.put("repo", meta == null ? "zhoufla/msm8916-box" : meta.updateRepo);
            if (meta != null && meta.updateManifest != null && meta.updateManifest.length() > 0) {
                up.put("manifest", meta.updateManifest);
            }
            up.put("auto", meta == null || meta.updateAuto);
            up.put("intervalH", meta == null ? 24 : meta.updateIntervalH);
            if (meta != null && meta.updateInsecure) up.put("insecure", true);
            if (meta != null && meta.updateWebkey != null && meta.updateWebkey.length() > 0) {
                up.put("webkey", meta.updateWebkey);
            }
            root.put("update", up);
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
                "  \"web\": {\"enabled\": true, \"port\": 8080, \"user\": \"admin\", \"pass\": \"admin\"},\n" +
                "  \"channels\": [\n" +
                "    {\n" +
                "      \"name\": \"运维群\",\n" +
                "      \"type\": \"dingtalk\",\n" +
                "      \"url\": \"https://oapi.dingtalk.com/robot/send?access_token=请替换\",\n" +
                "      \"secret\": \"SEC请替换\",\n" +
                "      \"template\": \"{peer}\\n{body}\\n设备：{device}\",\n" +
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
