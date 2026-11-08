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

    // 规则引擎（P0-A）：按数组顺序求值，未命中任何规则时按 rulesDefault 兜底
    // （all=投全部通道，向后兼容；drop=丢弃但入库）。规则经 Router.route() 求值。
    public List<Router.Rule> rules = new ArrayList<Router.Rule>();
    public String rulesDefault = "all";

    public boolean webEnabled = true;
    public int webPort = 8080;
    public String webUser = "admin";
    public String webPass = "admin";
    /** 面板访问令牌：非空时 Authorization: Bearer <token> / ?access_token= 可代替账号密码。 */
    public String webToken = "";
    /** CORS 允许的 Origin 白名单（空=不发 CORS 头，"*"=任意来源）。 */
    public List<String> webCors = new ArrayList<String>();

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

    // 系统收件箱镜像（JZ02 fork）：收到短信时把 Broadcast 事件写一份进 content://sms/inbox。
    // auto=没有默认短信 App 才写（SP970 上自动等于 off）；on=强制；off=关。
    public String mirrorInbox = InboxMirror.AUTO;

    // 维护段
    public boolean maintHealthEnabled = true;
    public int maintHealthIntervalMin = 30;
    // CPU 限频配置已移到 CpuFreq 的独立 SharedPreferences 键（不进 config JSON，
    // 防止 /api/config、helper 配置等保存路径整段重写导致「维持开关被改回」）

    // SIM 锁（B2 第 16 项）：ICCID 白名单。action = log（只记录）/ block（停止转发）
    public boolean simLockEnabled = false;
    public String simLockAction = "log";
    public String simLockList = "";

    // LTE 短信保活：WiFi 做默认上行时额外保持一条 MOBILE_HIPRI 链路，
    // 每 intervalSec 秒在 rmnet0 上发一个 ICMP 包，避免基带空闲态漏寻呼。
    // 代价约 0.5 MB/天，需要 SIM 有数据流量；不需要/流量紧张时置 false。
    public boolean kaEnabled = true;
    public int kaIntervalSec = 10;
    public String kaTarget = "223.5.5.5";
    public String kaIface = "rmnet0";
    // ConnectivityService$FeatureUser 的过期时间（android.telephony.apn-restore），
    // 缺省 60000ms，这里放到 int 上限附近（约 23 天）。
    public int kaRestoreMs = 2000000000;
    /** 告警推送（P0-C）：状态翻转才推 + remindH 提醒 + 恢复通知；channels 空=全部启用通道。 */
    public boolean alertEnabled = false;
    public List<String> alertChannels = new ArrayList<String>();
    public int alertRemindH = 6;
    public int alertIntervalMin = 5;
    public int alertTrafficGb = 0;
    public int alertBatteryPct = 0;
    public boolean alertKeepalive = true;
    /** DDNS 动态域名（P0-E）：变更才更新 + intervalMin 兜底；失败接告警推送。 */
    public boolean ddnsEnabled = false;
    public String ddnsProvider = "duckdns";
    public String ddnsDomain = "";
    public String ddnsToken = "";
    public String ddnsZone = "";
    public int ddnsIntervalMin = 10;
    public boolean ddnsInsecure = false;
    /** IPv6 优先网卡名（旧单条格式迁移源；新格式放 records[].iface） */
    public String ddnsIface = "";
    /** 记录类型：both（默认）/ v4 / v6（旧单条格式迁移源） */
    public String ddnsFamily = "both";
    /** 多条解析记录（v2 格式）：每条可选网卡与记录类型；服务商/Token/Zone 公共。 */
    public List<DdnsRecord> ddnsRecords = new ArrayList<DdnsRecord>();

    /** 一条 DDNS 解析记录：domain 记录名，iface 空=自动（蜂窝优先），family=v4|v6|both。 */
    public static class DdnsRecord {
        public String domain = "";
        public String iface = "";
        public String family = "both";

        public DdnsRecord() {
        }

        public DdnsRecord(String domain, String iface, String family) {
            this.domain = domain == null ? "" : domain.trim();
            this.iface = normIface(iface);
            this.family = normFamily(family);
        }

        public boolean wantV4() {
            return !"v6".equals(family);
        }

        public boolean wantV6() {
            return !"v4".equals(family);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("domain", domain);
                o.put("iface", iface);
                o.put("family", family);
            } catch (Throwable ignored) {
            }
            return o;
        }

        public static String normIface(String s) {
            return DdnsLogic.normIface(s);
        }

        public static String normFamily(String s) {
            return DdnsLogic.normFamily(s);
        }
    }

    public static AppConfig load(Context ctx) {
        AppConfig c = new AppConfig();
        String json = currentJson(ctx);
        if (json != null) c.parse(json);
        if (c.channels.isEmpty()) c.migrateLegacy(ctx);
        if (c.channels.isEmpty()) c.parse(sampleJson(ctx));
        return c;
    }

    /** 当前生效的原始 JSON 文本（面板展示/编辑用）。 */
    public static String effectiveJson(Context ctx) {
        String json = currentJson(ctx);
        if (json != null) return json;
        AppConfig c = new AppConfig();
        c.migrateLegacy(ctx);
        if (!c.channels.isEmpty()) return toJsonString(c.device, c.channels, c);
        return sampleJson(ctx);
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

    void parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String d = root.optString("device", "");
            if (d.length() > 0) device = d;

            String mi = root.optString("mirrorInbox", "").trim().toLowerCase();
            if ("on".equals(mi) || "true".equals(mi) || "1".equals(mi)) mirrorInbox = InboxMirror.ON;
            else if ("off".equals(mi) || "false".equals(mi) || "0".equals(mi)) mirrorInbox = InboxMirror.OFF;
            else if (mi.length() > 0) mirrorInbox = InboxMirror.AUTO;

            JSONObject web = root.optJSONObject("web");
            if (web != null) {
                webEnabled = web.optBoolean("enabled", true);
                int p = web.optInt("port", 8080);
                if (p > 0 && p < 65536) webPort = p;
                String u = web.optString("user", "");
                if (u.length() > 0) webUser = u;
                String pw = web.optString("pass", "");
                if (pw.length() > 0) webPass = pw;
                webToken = web.optString("token", "").trim();
                if (webToken.length() > 128) webToken = webToken.substring(0, 128);
                List<String> corsRaw = new ArrayList<String>();
                Object cv = web.opt("cors");
                if (cv instanceof JSONArray) {
                    JSONArray ca = (JSONArray) cv;
                    for (int i = 0; i < ca.length(); i++) corsRaw.add(String.valueOf(ca.opt(i)));
                } else if (cv instanceof String) {
                    for (String s : ((String) cv).split("[,，]")) corsRaw.add(s);
                }
                webCors = Http.normalizeCors(corsRaw);
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

            JSONObject ka = root.optJSONObject("keepalive");
            if (ka != null) {
                kaEnabled = ka.optBoolean("enabled", kaEnabled);
                int iv = ka.optInt("intervalSec", kaIntervalSec);
                if (iv >= 5 && iv <= 3600) kaIntervalSec = iv;
                String t = ka.optString("target", "");
                if (t.trim().length() > 0) kaTarget = t.trim();
                String n = ka.optString("iface", "");
                if (n.trim().length() > 0) kaIface = n.trim();
                int rm = ka.optInt("restoreMs", kaRestoreMs);
                if (rm >= 60000 && rm <= 2000000000) kaRestoreMs = rm;
            }

            JSONObject al = root.optJSONObject("alerts");
            if (al != null) {
                alertEnabled = al.optBoolean("enabled", alertEnabled);
                List<String> names = new ArrayList<String>();
                Object cv = al.opt("channels");
                if (cv instanceof JSONArray) {
                    JSONArray ac = (JSONArray) cv;
                    for (int i = 0; i < ac.length(); i++) {
                        String n = String.valueOf(ac.opt(i)).trim();
                        if (n.length() > 0 && !names.contains(n)) names.add(n);
                    }
                } else if (cv instanceof String) {
                    for (String n : ((String) cv).split("[,，;；\\s]+")) {
                        n = n.trim();
                        if (n.length() > 0 && !names.contains(n)) names.add(n);
                    }
                }
                alertChannels = names;
                int rh = al.optInt("remindH", alertRemindH);
                if (rh >= 1 && rh <= 168) alertRemindH = rh;
                int im = al.optInt("intervalMin", alertIntervalMin);
                if (im >= 1 && im <= 1440) alertIntervalMin = im;
                int tg = al.optInt("trafficGb", alertTrafficGb);
                if (tg >= 0 && tg <= 10000) alertTrafficGb = tg;
                int bp = al.optInt("batteryPct", alertBatteryPct);
                if (bp >= 0 && bp <= 100) alertBatteryPct = bp;
                alertKeepalive = al.optBoolean("keepalive", alertKeepalive);
            }

            JSONObject ddns = root.optJSONObject("ddns");
            if (ddns != null) {
                ddnsEnabled = ddns.optBoolean("enabled", ddnsEnabled);
                String dp = ddns.optString("provider", ddnsProvider);
                ddnsProvider = "cloudflare".equals(dp) ? "cloudflare" : "duckdns";
                ddnsDomain = ddns.optString("domain", "").trim();
                String dt = ddns.optString("token", "");
                if (dt.trim().length() > 0) ddnsToken = dt.trim();
                ddnsZone = ddns.optString("zone", "").trim();
                int di = ddns.optInt("intervalMin", ddnsIntervalMin);
                if (di >= 1 && di <= 1440) ddnsIntervalMin = di;
                ddnsInsecure = ddns.optBoolean("insecure", ddnsInsecure);
                ddnsIface = ddns.optString("iface", "").trim();
                if (!ddnsIface.matches("[A-Za-z0-9._:-]{1,20}")) ddnsIface = "";
                String df = ddns.optString("family", ddnsFamily);
                ddnsFamily = ("v4".equals(df) || "v6".equals(df)) ? df : "both";
                JSONArray drs = ddns.optJSONArray("records");
                if (drs != null) {
                    ddnsRecords = new ArrayList<DdnsRecord>();
                    for (int i = 0; i < drs.length() && ddnsRecords.size() < 20; i++) {
                        JSONObject ro = drs.optJSONObject(i);
                        if (ro == null) continue;
                        String dom = ro.optString("domain", "").trim();
                        if (dom.length() == 0) continue;
                        ddnsRecords.add(new DdnsRecord(dom, ro.optString("iface", ""),
                                ro.optString("family", "both")));
                    }
                } else if (ddnsDomain.length() > 0) {
                    // 旧单条格式迁移：domain/iface/family → records[0]
                    ddnsRecords = new ArrayList<DdnsRecord>();
                    ddnsRecords.add(new DdnsRecord(ddnsDomain, ddnsIface, ddnsFamily));
                }
            }

            JSONArray rr = root.optJSONArray("rules");
            if (rr != null) rules = Router.parseRules(rr);
            String rd = root.optString("rulesDefault", "");
            if ("all".equals(rd) || "drop".equals(rd)) rulesDefault = rd;

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
            web.put("token", meta == null ? "" : meta.webToken);
            JSONArray corsArr = new JSONArray();
            List<String> corsL = meta == null ? new ArrayList<String>() : meta.webCors;
            for (String s : corsL) corsArr.put(s);
            web.put("cors", corsArr);
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
            if (meta != null) root.put("mirrorInbox", meta.mirrorInbox);
            JSONObject mt = new JSONObject();
            mt.put("healthEnabled", meta == null || meta.maintHealthEnabled);
            mt.put("healthIntervalMin", meta == null ? 30 : meta.maintHealthIntervalMin);
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
            JSONObject ka = new JSONObject();
            ka.put("enabled", meta == null || meta.kaEnabled);
            ka.put("intervalSec", meta == null ? 10 : meta.kaIntervalSec);
            ka.put("target", meta == null ? "223.5.5.5" : meta.kaTarget);
            ka.put("iface", meta == null ? "rmnet0" : meta.kaIface);
            ka.put("restoreMs", meta == null ? 2000000000 : meta.kaRestoreMs);
            root.put("keepalive", ka);
            JSONObject al = new JSONObject();
            al.put("enabled", meta != null && meta.alertEnabled);
            JSONArray alch = new JSONArray();
            List<String> aln = meta == null ? new ArrayList<String>() : meta.alertChannels;
            for (int i = 0; i < aln.size(); i++) alch.put(aln.get(i));
            al.put("channels", alch);
            al.put("remindH", meta == null ? 6 : meta.alertRemindH);
            al.put("intervalMin", meta == null ? 5 : meta.alertIntervalMin);
            al.put("trafficGb", meta == null ? 0 : meta.alertTrafficGb);
            al.put("batteryPct", meta == null ? 0 : meta.alertBatteryPct);
            al.put("keepalive", meta == null || meta.alertKeepalive);
            root.put("alerts", al);
            JSONObject dd = new JSONObject();
            dd.put("enabled", meta != null && meta.ddnsEnabled);
            dd.put("provider", meta == null ? "duckdns" : meta.ddnsProvider);
            dd.put("domain", meta == null ? "" : meta.ddnsDomain);
            dd.put("token", meta == null ? "" : meta.ddnsToken);
            dd.put("zone", meta == null ? "" : meta.ddnsZone);
            dd.put("intervalMin", meta == null ? 10 : meta.ddnsIntervalMin);
            dd.put("insecure", meta != null && meta.ddnsInsecure);
            dd.put("iface", meta == null ? "" : meta.ddnsIface);
            dd.put("family", meta == null ? "both" : meta.ddnsFamily);
            JSONArray drs = new JSONArray();
            if (meta != null) {
                for (int i = 0; i < meta.ddnsRecords.size(); i++) {
                    drs.put(meta.ddnsRecords.get(i).toJson());
                }
            }
            dd.put("records", drs);
            root.put("ddns", dd);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < chs.size(); i++) arr.put(chs.get(i).toJson());
            root.put("channels", arr);
            if (meta != null) {
                JSONArray rl = new JSONArray();
                for (int i = 0; i < meta.rules.size(); i++) {
                    rl.put(Router.ruleToJson(meta.rules.get(i)));
                }
                root.put("rules", rl);
                root.put("rulesDefault", meta.rulesDefault);
            }
            return root.toString(2);
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String sampleJson() {
        // jz02 机型包构建时会内嵌 assets/model.conf（config/smsrelay.conf.jz02.example），
        // 覆盖下面的通用默认（helper token / mirrorInbox 不同）；sp970 包没有这个文件。
        // 无 Context 的调用方（writeSample 之外的旧路径）仍走内置默认。
        return builtinSampleJson();
    }

    /** 机型默认配置优先：jz02 包的 assets/model.conf > 通用内置默认。 */
    public static String sampleJson(Context ctx) {
        if (ctx != null) {
            try {
                java.io.InputStream in = ctx.getAssets().open("model.conf");
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                in.close();
                String s = buf.toString("UTF-8").trim();
                if (s.length() > 0 && s.charAt(0) == '{') return s;
            } catch (Throwable ignored) {
            }
        }
        return builtinSampleJson();
    }

    private static String builtinSampleJson() {
        return "{\n" +
                "  \"device\": \"" + Build.MODEL + "\",\n" +
                "  \"web\": {\"enabled\": true, \"port\": 8080, \"user\": \"admin\", \"pass\": \"admin\"},\n" +
                "  \"helper\": {\"url\": \"http://127.0.0.1:8899\", \"token\": \"sp970-relay-2026\"},\n" +
                "  \"mirrorInbox\": \"auto\",\n" +
                "  \"keepalive\": {\"enabled\": true, \"intervalSec\": 10, \"target\": \"223.5.5.5\", \"iface\": \"rmnet0\"},\n" +
                "  \"rulesDefault\": \"all\",\n" +
                "  \"rules\": [],\n" +
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
    public static void writeSample(Context ctx) {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), CONF_NAME);
            if (f.exists()) return;
            FileWriter w = new FileWriter(f);
            w.write("# 多通道配置示例，改完重启服务生效\n");
            w.write(sampleJson(ctx));
            w.write("\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
