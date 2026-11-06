package com.sp970.smsrelay;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 内嵌 Web 面板：HTTP 服务 + 静态页面 + JSON API。
 * 默认 8080 端口，HTTP Basic 鉴权（账号密码写在配置的 web 段）。
 */
public final class WebPanel {

    private static final Object LOCK = new Object();
    private static volatile WebPanel instance;
    private static final Map<String, byte[]> ASSETS = new HashMap<String, byte[]>();

    /** 请求都转给当前实例，方便改端口后原地重启。 */
    private static final Http.Handler FORWARD = new Http.Handler() {
        @Override
        public Http.Resp serve(Http.Req req) throws Exception {
            WebPanel w = instance;
            if (w == null) return Http.Resp.error(503, "面板未就绪");
            return w.route(req);
        }
    };

    private final Context app;
    private final Http http;
    private final String user;
    private final String pass;
    private Db db;

    private WebPanel(Context app, Http http, String user, String pass) {
        this.app = app;
        this.http = http;
        this.user = user;
        this.pass = pass;
    }

    /** 面板常驻，复用同一个 SQLiteOpenHelper，别按请求新建连接。 */
    private Db db() {
        if (db == null) {
            synchronized (this) {
                if (db == null) db = new Db(app);
            }
        }
        return db;
    }

    // ---------- 生命周期 ----------

    /** @return 给日志看的结果描述 */
    public static String start(Context ctx) {
        synchronized (LOCK) {
            if (instance != null) return "面板已在 " + instance.port() + " 端口运行";
            AppConfig cfg = AppConfig.load(ctx);
            if (!cfg.webEnabled) {
                Logs.add("panel", "配置里 web.enabled=false，面板不启动");
                return "面板已禁用";
            }
            int base = cfg.webPort > 0 ? cfg.webPort : 8080;
            for (int i = 0; i < 6; i++) {
                int p = base + i;
                Http h = new Http(p, FORWARD);
                if (h.start()) {
                    instance = new WebPanel(ctx.getApplicationContext(), h,
                            cfg.webUser, cfg.webPass);
                    Logs.add("panel", "面板已启动，端口 " + h.port()
                            + (p != base ? "（" + base + " 被占用，顺延）" : ""));
                    return "面板已启动，端口 " + h.port();
                }
            }
            Logs.add("panel", "面板启动失败：端口 " + base + "~" + (base + 5) + " 均被占用");
            return "面板启动失败：端口被占用";
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            WebPanel w = instance;
            instance = null;
            if (w != null) {
                w.http.stop();
                Logs.add("panel", "面板已停止");
            }
        }
    }

    public static String restart(Context ctx) {
        stop();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        return start(ctx);
    }

    public static boolean isRunning() {
        return instance != null && instance.http.isRunning();
    }

    public static int runningPort() {
        WebPanel w = instance;
        return w == null ? 0 : w.http.port();
    }

    private int port() {
        return http.port();
    }

    // ---------- 路由 ----------

    private Http.Resp route(Http.Req req) throws Exception {
        if (!authorized(req)) {
            Http.Resp r = Http.Resp.error(401, "需要登录");
            r.headers.put("WWW-Authenticate", "Basic realm=\"msm8916-box\"");
            return r;
        }

        String p = req.path;
        if (p == null || p.length() == 0) p = "/";

        if ("/".equals(p) || "/index.html".equals(p)) {
            return asset("web/index.html", "text/html; charset=utf-8");
        }
        if ("/favicon.ico".equals(p)) {
            return Http.Resp.text(204, "image/x-icon", "");
        }
        if (p.startsWith("/api/")) {
            try {
                return api(req, p);
            } catch (IllegalArgumentException e) {
                return Http.Resp.error(400, e.getMessage());
            } catch (Throwable t) {
                Logs.add("panel", "API " + p + " 异常: " + t);
                return Http.Resp.error(500, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return Http.Resp.error(404, "没有这个页面");
    }

    private boolean authorized(Http.Req req) {
        String h = req.headers.get("authorization");
        if (h == null || h.length() < 7) return false;
        if (!h.toLowerCase().startsWith("basic ")) return false;
        try {
            String raw = new String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), "UTF-8");
            int c = raw.indexOf(':');
            if (c < 0) return false;
            return raw.substring(0, c).equals(user) && raw.substring(c + 1).equals(pass);
        } catch (Throwable t) {
            return false;
        }
    }

    private Http.Resp api(Http.Req req, String path) throws Exception {
        boolean post = "POST".equals(req.method);
        JSONObject p = params(req);

        if ("/api/status".equals(path)) return Http.Resp.json(status(p).toString());
        if ("/api/sys".equals(path)) return Http.Resp.json(sys());
        if ("/api/logs".equals(path)) return Http.Resp.json(logs(p));
        if ("/api/messages".equals(path)) return Http.Resp.json(messages(p));
        if ("/api/outbox".equals(path)) return Http.Resp.json(outbox(p));
        if ("/api/config".equals(path)) return post ? saveConfig(req) : loadConfig();

        if ("/api/messages/retry".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(retry(p));
        }
        if ("/api/messages/test".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(inject(p));
        }
        if ("/api/messages/delete".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(deleteMsg(p));
        }
        if ("/api/outbox/delete".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(deleteOut(p));
        }
        if ("/api/inbox/import".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            int n = Inbox.importAll(app);
            RelayService.wake(app);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("added", n);
            o.put("message", "补收完成，新增 " + n + " 条");
            return Http.Resp.json(o.toString());
        }
        if ("/api/send".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(send(p));
        }
        if ("/api/update".equals(path)) return Http.Resp.json(updateState());
        if ("/api/update/check".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Update.check(app, AppConfig.load(app)).toString());
        }
        if ("/api/update/apply".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Update.apply(app, AppConfig.load(app)).toString());
        }
        if ("/api/update/probe".equals(path)) {
            String h = p.optString("host", "api.github.com");
            return Http.Resp.json(Net.probe(h));
        }
        if ("/api/update/config".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(updateConfig(p));
        }
        if ("/api/wifi".equals(path)) {
            return post ? Http.Resp.json(wifiApply(p)) : Http.Resp.json(wifiState());
        }
        if ("/api/wifi/scan".equals(path)) {
            // scan 自己带 ok / list / hint（热点开着时单射频扫不了，要给出原因）
            return Http.Resp.json(WifiCtl.scan(app).toString());
        }

        // ---- 维护（B0 + B1）：现有 21 个 API 一个都不动 ----
        if ("/api/health".equals(path)) return Http.Resp.json(Maint.health(app).toString());
        if (path.startsWith("/api/maint/")) return maint(path, p, post);

        // ---- 移动网络 / 换卡救砖（B2 第 13–19 项）----
        if ("/api/radio".equals(path)) return Http.Resp.json(Radio.state(app).toString());
        if (path.startsWith("/api/radio/")) return radio(path, p, post);
        if ("/api/netdiag".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(NetDiag.run(app, p.optString("kind", "ping"),
                    p.optString("target", ""), p.optString("iface", ""),
                    p.optInt("count", 4)).toString());
        }
        if ("/api/wifi/adv".equals(path)) {
            return Http.Resp.json(post
                    ? WifiCtl.advApply(app, p.optString("action", ""),
                    p.optString("arg", ""), p.optString("arg2", "")).toString()
                    : WifiCtl.advInfo(app).toString());
        }
        return Http.Resp.error(404, "没有这个接口: " + path);
    }

    /** /api/radio/* 路由分发。全部特权动作经 Root → 设备侧 helper。 */
    private Http.Resp radio(String path, JSONObject p, boolean post) throws Exception {
        if ("/api/radio/apns".equals(path)) {
            return Http.Resp.json(Radio.apns(app).toString());
        }
        if ("/api/radio/apn/save".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.apnSave(app, p).toString());
        }
        if ("/api/radio/apn/delete".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.apnDelete(app, p.optLong("id", 0)).toString());
        }
        if ("/api/radio/apn/prefer".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.apnPrefer(app, p.optLong("id", 0),
                    p.optBoolean("reconnect", true)).toString());
        }
        if ("/api/radio/mode".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.setMode(app, p.optInt("mode", -1),
                    p.optBoolean("reboot", false)).toString());
        }
        if ("/api/radio/data".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.setData(app, p.optBoolean("on", true)).toString());
        }
        if ("/api/radio/roaming".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.setRoaming(app, p.optBoolean("on", false)).toString());
        }
        if ("/api/radio/traffic".equals(path)) {
            return Http.Resp.json(Radio.traffic(app).toString());
        }
        if ("/api/radio/traffic/clear".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Radio.trafficClear(app).toString());
        }
        if ("/api/radio/simlock".equals(path)) {
            if (!post) return Http.Resp.json(Radio.simLock(app).toString());
            JSONObject root = new JSONObject(AppConfig.effectiveJson(app));
            JSONObject sl = new JSONObject();
            sl.put("enabled", p.optBoolean("enabled", false));
            String action = p.optString("action", "log");
            if (!"log".equals(action) && !"block".equals(action)) {
                throw new IllegalArgumentException("action 只能是 log / block");
            }
            sl.put("action", action);
            JSONArray wl = new JSONArray();
            String raw = p.optString("list", "");
            String[] parts = raw.split("[,，;；\\s]+");
            for (int i = 0; i < parts.length; i++) {
                // 只留数字：这台 ROM 的 ICCID 会带 f 填充位，用户也可能粘贴带空格的分组卡号
                String s = Radio.normIccid(parts[i].trim());
                if (s.length() >= 4 && s.length() <= 24) wl.put(s);
            }
            sl.put("list", wl);
            if (sl.optBoolean("enabled") && wl.length() == 0) {
                throw new IllegalArgumentException("白名单不能为空：开了锁会把所有卡都判成不合规");
            }
            root.put("simLock", sl);
            AppConfig.saveEffective(app, root.toString(2));
            Logs.add("radio", "SIM 锁 → enabled=" + sl.optBoolean("enabled")
                    + " action=" + action + " 白名单 " + wl.length() + " 条");
            JSONObject out = Radio.simLock(app);
            out.put("message", "已保存：SIM 锁" + (sl.optBoolean("enabled") ? "开启" : "关闭")
                    + "，" + wl.length() + " 条白名单");
            return Http.Resp.json(out.toString());
        }
        return Http.Resp.error(404, "没有这个接口: " + path);
    }

    /** /api/maint/* 路由分发。特权动作全部经 Maint → Root → 设备侧 helper。 */
    private Http.Resp maint(String path, JSONObject p, boolean post) throws Exception {
        if ("/api/maint/helper".equals(path)) {
            AppConfig cfg = AppConfig.load(app);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("alive", Root.alive(app));
            o.put("uid", Root.uid(app));
            o.put("url", cfg.helperUrl);
            o.put("token", cfg.helperToken);
            return Http.Resp.json(o.toString());
        }
        if ("/api/maint/config".equals(path)) {
            if (!post) {
                AppConfig cfg = AppConfig.load(app);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("helperUrl", cfg.helperUrl);
                o.put("helperToken", cfg.helperToken);
                o.put("healthEnabled", cfg.maintHealthEnabled);
                o.put("healthIntervalMin", cfg.maintHealthIntervalMin);
                return Http.Resp.json(o.toString());
            }
            JSONObject root = new JSONObject(AppConfig.effectiveJson(app));
            String u = p.optString("helperUrl", "").trim();
            if (!u.startsWith("http://127.0.0.1:")) {
                throw new IllegalArgumentException("helper 地址只能是 http://127.0.0.1:<port>");
            }
            String t = p.optString("helperToken", "").trim();
            if (t.length() == 0) throw new IllegalArgumentException("token 不能为空");
            JSONObject hp = new JSONObject();
            hp.put("url", u);
            hp.put("token", t);
            root.put("helper", hp);
            JSONObject mt = new JSONObject();
            mt.put("healthEnabled", p.optBoolean("healthEnabled", true));
            int g = p.optInt("healthIntervalMin", 30);
            if (g < 1 || g > 1440) throw new IllegalArgumentException("体检间隔要在 1~1440 分钟");
            mt.put("healthIntervalMin", g);
            root.put("maint", mt);
            AppConfig.saveEffective(app, root.toString(2));
            Logs.add("maint", "helper 配置改为 " + u);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("message", "已保存");
            return Http.Resp.json(o.toString());
        }
        if ("/api/maint/cmd".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.cmd(app, p.optString("cmd", "")).toString());
        }
        if ("/api/maint/checkup".equals(path)) {
            return Http.Resp.json(Maint.checkup(app).toString());
        }
        if ("/api/maint/logs".equals(path)) {
            return Http.Resp.json(Maint.logs(app, p.optString("kind", "main"),
                    p.optString("filter", ""), p.optInt("lines", 200)).toString());
        }
        if ("/api/maint/logs/bundle".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.bundleLogs(app).toString());
        }
        if ("/api/maint/backup".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.backup(app).toString());
        }
        if ("/api/maint/restore".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.restore(app, p.optString("path", ""),
                    p.optBoolean("includeDb", false), p.optString("confirm", "")).toString());
        }
        if ("/api/maint/files".equals(path)) {
            return Http.Resp.json(Maint.files(app).toString());
        }
        if ("/api/maint/file".equals(path)) {
            String fp = p.optString("path", "");
            if (!fp.startsWith("/sdcard/Download/") || fp.contains("..")) {
                throw new IllegalArgumentException("只能下载 /sdcard/Download/ 下的文件");
            }
            byte[] b = Root.file(app, fp);
            Http.Resp r = Http.Resp.bytes("application/octet-stream", b);
            String name = fp.substring(fp.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
            r.headers.put("Content-Disposition", "attachment; filename=\"" + name + "\"");
            return r;
        }
        if ("/api/maint/power".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.power(app, p.optString("action", ""),
                    p.optString("confirm", "")).toString());
        }
        if ("/api/maint/led".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.led(app, p.optString("action", "")).toString());
        }
        if ("/api/maint/cron".equals(path)) {
            return Http.Resp.json(post
                    ? Maint.cronSet(app, p.optString("text", "")).toString()
                    : Maint.cronGet(app).toString());
        }
        if ("/api/maint/reset".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.factoryReset(app, p.optString("scope", "config"),
                    p.optString("confirm", "")).toString());
        }
        return Http.Resp.error(404, "没有这个接口: " + path);
    }

    // ---------- 各接口 ----------

    private JSONObject status(JSONObject p) throws Exception {
        AppConfig cfg = AppConfig.load(app);
        Db db = db();
        long up = SystemClock.elapsedRealtime();

        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("device", cfg.device);
        o.put("model", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("uptime", SysInfo.uptimeText(up));
        o.put("uptimeMs", up);

        JSONObject panel = new JSONObject();
        panel.put("running", isRunning());
        panel.put("port", runningPort());
        panel.put("user", user);
        o.put("panel", panel);

        JSONArray chs = new JSONArray();
        for (int i = 0; i < cfg.channels.size(); i++) {
            Channel c = cfg.channels.get(i);
            JSONObject x = new JSONObject();
            x.put("name", c.name);
            x.put("type", c.type);
            x.put("enabled", c.enabled);
            x.put("active", c.valid());
            x.put("tag", c.nzTag());
            x.put("format", c.isMarkdown() ? "markdown" : "text");
            x.put("maxTries", c.maxTries);
            x.put("host", hostOf(c.url));
            chs.put(x);
        }
        o.put("channels", chs);
        o.put("activeChannels", cfg.activeCount());

        JSONObject q = new JSONObject();
        q.put("pending", db.pending());
        q.put("sent", db.sent());
        q.put("failed", db.failed());
        q.put("events", db.events());
        q.put("lastError", db.lastError());
        o.put("queue", q);

        // USB 直供无电池，不再上报电量
        o.put("signal", new JSONObject().put("level", SysInfo.signalLevel()));
        o.put("webEnabled", cfg.webEnabled);
        return o;
    }

    private String sys() throws Exception {
        AppConfig cfg = AppConfig.load(app);
        JSONObject o = SysInfo.snapshot(app, cfg);
        o.put("ok", true);
        o.put("panel", new JSONObject().put("running", isRunning())
                .put("port", runningPort()));
        return o.toString();
    }

    private String logs(JSONObject p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("logs", Logs.json(p.optInt("limit", 80)));
        return o.toString();
    }

    private String messages(JSONObject p) throws Exception {
        int limit = p.optInt("limit", 50);
        if (limit < 1) limit = 1;
        if (limit > 300) limit = 300;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("messages", db().recentJson(limit));
        return o.toString();
    }

    private String outbox(JSONObject p) throws Exception {
        int limit = p.optInt("limit", 30);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("messages", db().outboxJson(limit));
        return o.toString();
    }

    private Http.Resp loadConfig() throws Exception {
        AppConfig cfg = AppConfig.load(app);
        File f = new File(Environment.getExternalStorageDirectory(), AppConfig.CONF_NAME);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("json", AppConfig.effectiveJson(app));
        o.put("port", runningPort());
        o.put("running", isRunning());
        o.put("file", f.getAbsolutePath());
        o.put("fileExists", f.exists());
        o.put("web", new JSONObject().put("enabled", cfg.webEnabled)
                .put("port", cfg.webPort).put("user", cfg.webUser).put("pass", cfg.webPass));
        return Http.Resp.json(o.toString());
    }

    private Http.Resp saveConfig(Http.Req req) throws Exception {
        String body = req.body == null ? "" : req.body.trim();
        if (!body.startsWith("{")) throw new IllegalArgumentException("配置必须是 JSON 对象");
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("channels");
        if (arr == null) throw new IllegalArgumentException("缺少 channels 数组");

        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) throw new IllegalArgumentException("channels[" + i + "] 不是对象");
            String name = c.optString("name", "");
            String url = c.optString("url", "");
            String type = c.optString("type", "webhook");
            String format = c.optString("format", "text");
            int maxTries = c.optInt("maxTries", 10);
            if (name.length() == 0) throw new IllegalArgumentException("第 " + (i + 1) + " 个通道缺少 name");
            if (!"dingtalk".equalsIgnoreCase(type) && !"webhook".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("通道 " + name + " 的 type 只能是 dingtalk / webhook");
            }
            if (!"text".equalsIgnoreCase(format) && !"markdown".equalsIgnoreCase(format)) {
                throw new IllegalArgumentException("通道 " + name + " 的 format 只能是 text / markdown");
            }
            if (maxTries < 1 || maxTries > 50) {
                throw new IllegalArgumentException("通道 " + name + " 的 maxTries 需在 1~50 之间");
            }
            if (url.length() > 0 && !url.startsWith("http")) {
                throw new IllegalArgumentException("通道 " + name + " 的 url 不是 http 开头");
            }
        }

        AppConfig old = AppConfig.load(app);
        AppConfig.saveEffective(app, body);
        AppConfig now = AppConfig.load(app);
        RelayService.wake(app);

        boolean needRestart = old.webPort != now.webPort
                || old.webEnabled != now.webEnabled
                || !old.webUser.equals(now.webUser)
                || !old.webPass.equals(now.webPass);

        String msg = "配置已保存";
        if (needRestart) {
            msg = WebPanel.restart(app);
            needRestart = true;
        }

        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", msg);
        o.put("restarted", needRestart);
        o.put("port", runningPort());
        o.put("activeChannels", now.activeCount());
        return Http.Resp.json(o.toString());
    }

    private String send(JSONObject p) throws Exception {
        String to = p.optString("to", "").trim();
        String body = p.optString("body", "");
        long id;
        try {
            id = SmsSender.send(app, to, body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", id);
        o.put("message", "已提交发送，稍后刷新看结果");
        return o.toString();
    }

    private String retry(JSONObject p) throws Exception {
        long id = p.optLong("id", 0);
        if (id <= 0) throw new IllegalArgumentException("缺少消息 id");
        AppConfig cfg = AppConfig.load(app);
        Db db = db();
        db.retryEventWithChannels(id, cfg.names());
        RelayService.wake(app);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", "已重新入队，稍后刷新");
        return o.toString();
    }

    private String deleteMsg(JSONObject p) throws Exception {
        Db db = db();
        JSONObject o = new JSONObject();
        if ("givenup".equals(p.optString("scope", ""))) {
            int[] n = db.deleteGivenUp();
            Logs.add("panel", "清理已放弃投递 dl=" + n[0] + " ev=" + n[1]);
            o.put("ok", true);
            o.put("deleted", n[0]);
            o.put("message", n[0] == 0 ? "没有已放弃的记录" : "已清理 " + n[0] + " 条放弃投递");
            return o.toString();
        }
        long id = p.optLong("id", 0);
        if (id <= 0) throw new IllegalArgumentException("缺少消息 id");
        boolean gone = db.deleteEvent(id);
        Logs.add("panel", "删除短信 evId=" + id + (gone ? "" : " 不存在"));
        o.put("ok", gone);
        o.put("deleted", gone ? 1 : 0);
        o.put("message", gone ? "已删除 #" + id : "没有这条记录（可能已被清理）");
        return o.toString();
    }

    private String deleteOut(JSONObject p) throws Exception {
        long id = p.optLong("id", 0);
        if (id <= 0) throw new IllegalArgumentException("缺少记录 id");
        boolean gone = db().deleteOut(id);
        Logs.add("panel", "删除发件记录 id=" + id + (gone ? "" : " 不存在"));
        JSONObject o = new JSONObject();
        o.put("ok", gone);
        o.put("deleted", gone ? 1 : 0);
        o.put("message", gone ? "已删除 #" + id : "没有这条记录（可能已被清理）");
        return o.toString();
    }

    private String updateState() throws Exception {
        AppConfig cfg = AppConfig.load(app);
        JSONObject o = Update.state(app);
        JSONObject c = new JSONObject();
        c.put("mode", cfg.updateMode);
        c.put("repo", cfg.updateRepo);
        c.put("manifest", cfg.updateManifest);
        c.put("auto", cfg.updateAuto);
        c.put("intervalH", cfg.updateIntervalH);
        c.put("insecure", cfg.updateInsecure);
        o.put("config", c);
        o.put("downloadDir", new File(Environment.getExternalStorageDirectory(),
                "Download").getAbsolutePath());
        return o.toString();
    }

    /** 只改 update 段，其余配置原样保留（面板其它页正在改的东西不会被踩掉）。 */
    private String updateConfig(JSONObject p) throws Exception {
        JSONObject root = new JSONObject(AppConfig.effectiveJson(app));
        JSONObject up = new JSONObject();
        String mode = p.optString("mode", "github");
        if (!"off".equals(mode) && !"github".equals(mode) && !"manifest".equals(mode)) {
            throw new IllegalArgumentException("mode 只能是 off / github / manifest");
        }
        up.put("mode", mode);
        String repo = p.optString("repo", "").trim();
        if (repo.length() > 0) {
            if (repo.indexOf('/') <= 0) throw new IllegalArgumentException("仓库要写成 owner/repo");
            up.put("repo", repo);
        }
        String man = p.optString("manifest", "").trim();
        if (man.length() > 0) {
            if (!man.startsWith("http")) throw new IllegalArgumentException("manifest 要以 http 开头");
            up.put("manifest", man);
        }
        up.put("auto", p.optBoolean("auto", true));
        int ih = p.optInt("intervalH", 24);
        if (ih < 1 || ih > 24 * 30) throw new IllegalArgumentException("检查间隔要在 1~720 小时");
        up.put("intervalH", ih);
        if (p.optBoolean("insecure", false)) up.put("insecure", true);
        root.put("update", up);
        AppConfig.saveEffective(app, root.toString(2));
        Logs.add("update", "配置改为 mode=" + mode + " repo=" + repo + " auto=" + up.optBoolean("auto"));
        return updateStateWithOk();
    }

    private String updateStateWithOk() throws Exception {
        JSONObject o = new JSONObject(updateState());
        o.put("ok", true);
        o.put("message", "已保存");
        return o.toString();
    }

    private String inject(JSONObject p) throws Exception {
        AppConfig cfg = AppConfig.load(app);
        SmsEvent e = new SmsEvent();
        e.ts = System.currentTimeMillis();
        e.peer = p.optString("peer", "10086");
        e.body = p.optString("body", "面板测试消息 " + e.ts);
        e.code = SmsEvent.extractCode(e.body);
        Db db = db();
        long id = db.insertEvent(e);
        db.ensureDeliveries(id, cfg.names());
        RelayService.wake(app);
        Logs.add("panel", "注入测试消息 evId=" + id + " 通道=" + cfg.names());
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", id);
        o.put("message", "已注入，通道数 " + cfg.names().size());
        return o.toString();
    }

    private String wifiState() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("state", WifiCtl.state(app));
        return o.toString();
    }

    private String wifiApply(JSONObject p) throws Exception {
        String action = p.optString("action", "");
        String ssid = p.optString("ssid", "");
        String pass = p.optString("pass", "");
        // WifiCtl.apply 直接回 {ok, message, state}，失败时 ok=false 而不是永远 true
        return WifiCtl.apply(app, action, ssid, pass).toString();
    }

    // ---------- 工具 ----------

    private static JSONObject params(Http.Req req) {
        JSONObject o = new JSONObject();
        try {
            String b = req.body == null ? "" : req.body.trim();
            if (b.startsWith("{")) {
                JSONObject j = new JSONObject(b);
                java.util.Iterator<String> it = j.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, j.opt(k));
                }
                return o;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Map.Entry<String, String> e : req.form.entrySet()) o.put(e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : req.query.entrySet()) {
                if (!o.has(e.getKey())) o.put(e.getKey(), e.getValue());
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private Http.Resp asset(String name, String ct) {
        byte[] cached;
        synchronized (ASSETS) {
            cached = ASSETS.get(name);
        }
        if (cached == null) {
            InputStream in = null;
            try {
                in = app.getAssets().open(name);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                cached = bo.toByteArray();
                synchronized (ASSETS) {
                    ASSETS.put(name, cached);
                }
            } catch (Throwable t) {
                Logs.add("panel", "静态资源缺失: " + name + " " + t);
                return Http.Resp.error(500, "页面资源缺失: " + name);
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
            }
        }
        Http.Resp r = Http.Resp.bytes(ct, cached);
        r.headers.put("Content-Encoding", "identity");
        return r;
    }

    private static String hostOf(String url) {
        try {
            int s = url.indexOf("://");
            if (s < 0) return "";
            int e = url.indexOf('/', s + 3);
            return e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
        } catch (Throwable t) {
            return "";
        }
    }
}
