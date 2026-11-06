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

    // root 执行通道（设备侧 relay-helper，只听 127.0.0.1:8899）
    // 取代了原来的 update.webkey：一套凭据、单端口，不需要 Digest，也不需要 su。
    public String helperUrl = "http://127.0.0.1:8899";
    public String helperToken = "sp970-relay-2026";

    // 维护段
    public boolean maintHealthEnabled = true;
    public int maintHealthIntervalMin = 30;

    // CPU 限频：0 = 不限制/用当前值；enabled 时服务循环周期校正（对抗 thermal/厂商脚本）
    public boolean cpuLimitEnabled = false;
    public long cpuMaxKhz = 0;
    public long cpuMinKhz = 0;

    // SIM 锁（B2 第 16 项）：ICCID 白名单。action = log（只记录）/ block（停止转发）
    public boolean simLockEnabled = false;
    public String simLockAction = "log";
    public String simLockList = "";

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
        } catch (Throwable t) {
            // 文件优先级高于 prefs，写失败 = 下次读回旧值（面板上像「改了又弹回去」），必须留痕
            Logs.add("config", "conf 文件写入失败: " + t);
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

    /** SIM 白名单：逗号/空格/分号分隔都认，整串 ICCID 或末 4 位都行。 */
    public List<String> iccids() {
        List<String> out = new ArrayList<String>();
        if (simLockList == null || simLockList.trim().length() == 0) return out;
        String[] parts = simLockList.split("[,，;；\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0 && !out.contains(s)) out.add(s);
        }
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
                // 旧版 update.webkey 已废弃：不再读取，残留字段直接忽略
            }

            JSONObject hp = root.optJSONObject("helper");
            if (hp != null) {
                String u = hp.optString("url", "");
                if (u.startsWith("http://127.0.0.1:")) helperUrl = u;
                String t = hp.optString("token", "");
                if (t.length() > 0) helperToken = t;
            }

            JSONObject mt = root.optJSONObject("maint");
            if (mt != null) {
                maintHealthEnabled = mt.optBoolean("healthEnabled", maintHealthEnabled);
                int g = mt.optInt("healthIntervalMin", maintHealthIntervalMin);
                if (g >= 1 && g <= 24 * 60) maintHealthIntervalMin = g;
                cpuLimitEnabled = mt.optBoolean("cpuLimitEnabled", cpuLimitEnabled);
                long cm = mt.optLong("cpuMaxKhz", cpuMaxKhz);
                if (cm >= 0) cpuMaxKhz = cm;
                long cn = mt.optLong("cpuMinKhz", cpuMinKhz);
                if (cn >= 0) cpuMinKhz = cn;
            }

            JSONObject sl = root.optJSONObject("simLock");
            if (sl != null) {
                simLockEnabled = sl.optBoolean("enabled", simLockEnabled);
                String a = sl.optString("action", simLockAction);
                if ("log".equals(a) || "block".equals(a)) simLockAction = a;
                JSONArray wl = sl.optJSONArray("list");
                if (wl != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < wl.length(); i++) {
                        String s = wl.optString(i, "").trim();
                        if (s.length() == 0) continue;
                        if (sb.length() > 0) sb.append(',');
                        sb.append(s);
                    }
                    simLockList = sb.toString();
                } else {
                    String raw = sl.optString("list", "");
                    if (raw.length() > 0) simLockList = raw;
                }
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
            root.put("update", up);
            JSONObject hp = new JSONObject();
            hp.put("url", meta == null ? "http://127.0.0.1:8899" : meta.helperUrl);
            hp.put("token", meta == null ? "sp970-relay-2026" : meta.helperToken);
            root.put("helper", hp);
            JSONObject mt = new JSONObject();
            mt.put("healthEnabled", meta == null || meta.maintHealthEnabled);
            mt.put("healthIntervalMin", meta == null ? 30 : meta.maintHealthIntervalMin);
            mt.put("cpuLimitEnabled", meta != null && meta.cpuLimitEnabled);
            mt.put("cpuMaxKhz", meta == null ? 0 : meta.cpuMaxKhz);
            mt.put("cpuMinKhz", meta == null ? 0 : meta.cpuMinKhz);
            root.put("maint", mt);
            JSONObject sl = new JSONObject();
            sl.put("enabled", meta != null && meta.simLockEnabled);
            sl.put("action", meta == null ? "log" : meta.simLockAction);
            JSONArray wl = new JSONArray();
            if (meta != null) {
                List<String> ids = meta.iccids();
                for (int i = 0; i < ids.size(); i++) wl.put(ids.get(i));
            }
            sl.put("list", wl);
            root.put("simLock", sl);
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
