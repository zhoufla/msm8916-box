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
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内嵌 Web 面板：HTTP 服务 + 静态页面 + JSON API。
 * 默认 8080 端口，HTTP Basic 鉴权（账号密码写在配置的 web 段）。
 */
public final class WebPanel {

    private static final Object LOCK = new Object();
    private static volatile WebPanel instance;
    private static final Map<String, byte[]> ASSETS = new HashMap<String, byte[]>();

    // SSE（概览实时化）：每条连接占一个 panel-conn 线程，连接数设上限；
    // 寿命封顶 30 分钟，之后推 bye 让客户端重连，防僵尸连接越积越多。
    private static final java.util.concurrent.atomic.AtomicInteger SSE_CONN =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int SSE_MAX = 3;
    private static final long SSE_LIFETIME_MS = 30 * 60 * 1000L;
    private static final long SSE_HEARTBEAT_MS = 15 * 1000L;

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
    private final String token;
    private final List<String> cors;
    private Db db;

    private WebPanel(Context app, Http http, String user, String pass, String token, List<String> cors) {
        this.app = app;
        this.http = http;
        this.user = user;
        this.pass = pass;
        this.token = token == null ? "" : token;
        this.cors = cors == null ? new ArrayList<String>() : cors;
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
            if (instance != null) {
                Watchdog.publishPort(instance.port());
                return "面板已在 " + instance.port() + " 端口运行";
            }
            AppConfig cfg = AppConfig.load(ctx);
            if (!cfg.webEnabled) {
                Watchdog.clearPort();
                Logs.add("panel", "配置里 web.enabled=false，面板不启动");
                return "面板已禁用";
            }
            int base = cfg.webPort > 0 ? cfg.webPort : 8080;
            for (int i = 0; i < 6; i++) {
                int p = base + i;
                Http h = new Http(p, FORWARD);
                if (h.start()) {
                    instance = new WebPanel(ctx.getApplicationContext(), h,
                            cfg.webUser, cfg.webPass, cfg.webToken, cfg.webCors);
                    Watchdog.publishPort(h.port());
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
        // CORS 预检不携带 Authorization，配置了允许来源就直接放行（其余请求一律照旧鉴权）
        if ("OPTIONS".equals(req.method) && !cors.isEmpty()) {
            Http.Resp pre = Http.Resp.text(204, "text/plain", "");
            applyCors(pre, req);
            return pre;
        }

        // 免认证探活端点：只回 "ok"。App 内看门狗与 helper root 脚本靠它判断
        // ServerSocket / accept 线程是否还真的活着（「假在线」时拿不到这个响应）。
        if ("/api/ping".equals(req.path)) {
            return Http.Resp.text(200, "text/plain; charset=utf-8", "ok");
        }

        Http.Resp r;
        if (!authorized(req)) {
            r = Http.Resp.error(401, "需要登录");
            r.headers.put("WWW-Authenticate", "Basic realm=\"msm8916-box\"");
        } else {
            r = serve(req);
        }
        applyCors(r, req);
        return r;
    }

    private Http.Resp serve(Http.Req req) throws Exception {
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

    /** Basic 账号密码之外，配置了 web.token 时也接受 Bearer 头与 ?access_token=（EventSource 用）。 */
    private boolean authorized(Http.Req req) {
        String h = req.headers.get("authorization");
        if (h == null) {
            return Http.tokenMatches(token, req.q("access_token", ""));
        }
        String hl = h.toLowerCase();
        if (hl.startsWith("bearer ")) {
            return Http.tokenMatches(token, h.substring(7).trim());
        }
        if (hl.startsWith("basic ")) {
            try {
                String raw = new String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), "UTF-8");
                int c = raw.indexOf(':');
                if (c < 0) return false;
                return raw.substring(0, c).equals(user) && raw.substring(c + 1).equals(pass);
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** 请求带 Origin 且配置了允许来源时补 CORS 头（401/正常响应都要带，浏览器才看得到）。 */
    private void applyCors(Http.Resp resp, Http.Req req) {
        String origin = req.headers.get("origin");
        if (cors.isEmpty() || !Http.corsAllowed(cors, origin)) return;
        resp.headers.put("Access-Control-Allow-Origin", origin);
        resp.headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.headers.put("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Token");
        resp.headers.put("Access-Control-Max-Age", "600");
        resp.headers.put("Vary", "Origin");
    }

    private static String corsJoin(List<String> cors) {
        StringBuilder sb = new StringBuilder();
        for (String s : cors) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private Http.Resp api(Http.Req req, String path) throws Exception {
        boolean post = "POST".equals(req.method);
        JSONObject p = params(req);

        if ("/api/status".equals(path)) return Http.Resp.json(status(p).toString());
        if ("/api/events".equals(path)) return events(req);
        if ("/api/ddns".equals(path)) {
            if (!"GET".equals(req.method)) throw new IllegalArgumentException("需要 GET");
            return Http.Resp.json(Ddns.status(app).toString());
        }
        if ("/api/ddns/now".equals(path)) {
            if (!"POST".equals(req.method)) throw new IllegalArgumentException("需要 POST");
            Ddns.updateNow(app);
            return Http.Resp.json("{\"ok\":true,\"message\":\"已在后台执行，稍后刷新\"}");
        }
        if ("/api/keepalive".equals(path)) {
            if (post) MobileKeepalive.wake();
            JSONObject ka = MobileKeepalive.status(app);
            ka.put("ok", true);
            ka.put("ifaces", MobileKeepalive.ifaces(app));
            return Http.Resp.json(ka.toString());
        }
        if ("/api/sys".equals(path)) return Http.Resp.json(sys());
        if ("/api/logs".equals(path)) return Http.Resp.json(logs(p));
        if ("/api/messages".equals(path)) return Http.Resp.json(messages(p));
        if ("/api/outbox".equals(path)) return Http.Resp.json(outbox(p));
        if ("/api/sysinbox".equals(path)) return Http.Resp.json(sysInbox(p));
        if ("/api/sysinbox/forward".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(SysInbox.forwardOne(app, p.optString("peer", "").trim(),
                    p.optString("body", ""), p.optLong("ts", 0)).toString());
        }
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
        if ("/api/rules/validate".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(rulesValidate(p).toString());
        }
        if ("/api/rules/test".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(rulesTest(p).toString());
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
        if ("/api/wifi/adv".equals(path)) {            return Http.Resp.json(post
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
        if ("/api/maint/cpufreq".equals(path)) {
            if (!post) {
                JSONObject o = CpuFreq.info(app);
                o.put("ok", true);
                // CPU 限频配置存独立 prefs 键（不进 config JSON，防其它保存路径回退）
                CpuFreq.CpuCfg c = CpuFreq.load(app);
                o.put("enabled", c.enabled);
                o.put("cpuMaxKhz", c.maxKhz);
                o.put("cpuMinKhz", c.minKhz);
                return Http.Resp.json(o.toString());
            }
            long max = p.optLong("max", 0);
            long min = p.optLong("min", 0);
            boolean auto = p.optBoolean("auto", false);
            // apply 内部直接写独立 prefs 键并打日志
            JSONObject o;
            if (p.optBoolean("disable")) {
                o = CpuFreq.disable(app);
            } else if (p.has("setEnabled")) {
                o = CpuFreq.setEnabled(app, p.optBoolean("setEnabled"));
            } else {
                o = CpuFreq.apply(app, min, max, auto);
            }
            return Http.Resp.json(o.toString());
        }
        if ("/api/maint/cmd".equals(path)) {
            if (!post) throw new IllegalArgumentException("需要 POST");
            return Http.Resp.json(Maint.cmd(app, p.optString("cmd", "")).toString());
        }
        if ("/api/maint/adb".equals(path)) {
            return Http.Resp.json(post
                    ? Maint.adbAction(app, p.optString("action", ""),
                            p.optString("confirm", "")).toString()
                    : Maint.adbStatus(app).toString());
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

    /**
     * SSE：连上先推全量 status，之后每秒比对一次，变更即推（≤1s 延迟），
     * 无变更 15s 发一次注释心跳。status() 只读缓存/SQLite，1Hz 采样无 root 开销。
     */
    /** SSE 变更比对的 key：剔除随时间恒变的字段（uptimeMs 毫秒级/uptime 分钟级），
     *  否则 1s 快照恒不相等，15s 心跳永远不会触发。推送的 payload 仍带全量字段。 */
    private static final String[] SSE_VOLATILE =
            {"uptime", "uptimeMs", "lastPingAt", "lastOkAt", "pings", "oks"};

    private static String sseCmpKey(JSONObject o) {
        try {
            JSONObject c = new JSONObject(o.toString());
            stripVolatile(c);
            return c.toString();
        } catch (Throwable t) {
            return o.toString();
        }
    }

    /** 递归剔除随时间恒变、不代表状态翻转的字段（含 keepalive 计数/时间戳）。 */
    private static void stripVolatile(JSONObject o) {
        for (int i = 0; i < SSE_VOLATILE.length; i++) o.remove(SSE_VOLATILE[i]);
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject) stripVolatile((JSONObject) v);
        }
    }

    /** SSE 对端断开探测：短暂读一次，-1=客户端已关（FIN，槽位必须释放）；
     *  超时=正常；异常=RST/断开。客户端被杀后 FIN 到达，写 CLOSE_WAIT 仍"成功"，
     *  不主动探测会占住槽位直到 30min 寿命到期。 */
    private static boolean peerGone() {
        Socket s = Http.currentSocket();
        if (s == null || s.isClosed()) return true;
        try {
            int old = s.getSoTimeout();
            s.setSoTimeout(50);
            try {
                if (s.getInputStream().read() == -1) return true;
            } finally {
                s.setSoTimeout(old);
            }
            return false;
        } catch (java.net.SocketTimeoutException te) {
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private Http.Resp events(Http.Req p) throws Exception {
        if (!"GET".equals(p.method)) throw new IllegalArgumentException("SSE 需要 GET");
        if (SSE_CONN.get() >= SSE_MAX) {
            return Http.Resp.error(503, "SSE 连接已达上限（" + SSE_MAX + "），稍后再试");
        }
        final WebPanel w = this;
        return Http.Resp.stream(new Http.Streamer() {
            @Override
            public void stream(OutputStream out) throws Exception {
                SSE_CONN.incrementAndGet();
                try {
                    long end = SystemClock.elapsedRealtime() + SSE_LIFETIME_MS;
                    String lastKey;
                    try { lastKey = sseCmpKey(w.status(null)); }
                    catch (Throwable t) { lastKey = "{\"ok\":false}"; }
                    out.write("retry: 3000\n\n".getBytes("UTF-8"));
                    out.write(("event: status\ndata: " + w.status(null) + "\n\n").getBytes("UTF-8"));
                    out.flush();
                    long lastWrite = SystemClock.elapsedRealtime();
                    while (SystemClock.elapsedRealtime() < end) {
                        Thread.sleep(1000);
                        if (peerGone()) break;
                        JSONObject snap;
                        try { snap = w.status(null); }
                        catch (Throwable t) { continue; }
                        long now = SystemClock.elapsedRealtime();
                        String key = sseCmpKey(snap);
                        if (!key.equals(lastKey)) {
                            out.write(("event: status\ndata: " + snap + "\n\n").getBytes("UTF-8"));
                            out.flush();
                            lastKey = key;
                            lastWrite = now;
                        } else if (now - lastWrite >= SSE_HEARTBEAT_MS) {
                            out.write(": hb\n\n".getBytes("UTF-8"));
                            out.flush();
                            lastWrite = now;
                        }
                    }
                    try {
                        out.write("event: bye\ndata: {}\n\n".getBytes("UTF-8"));
                        out.flush();
                    } catch (Throwable ignored) {}
                } finally {
                    SSE_CONN.decrementAndGet();
                }
            }
        });
    }

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
        o.put("keepalive", MobileKeepalive.status(app));
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

    /** 分页参数：page 从 1 起；page_size 优先，兼容旧 limit 参数（1~100）。 */
    private static int pageOf(JSONObject p) {
        return Math.max(1, p.optInt("page", 1));
    }

    private static int sizeOf(JSONObject p) {
        int s = p.has("page_size") ? p.optInt("page_size", 20) : p.optInt("limit", 20);
        if (s < 1) s = 1;
        if (s > 100) s = 100;
        return s;
    }

    /** 转发记录分页：收信本体 + 每通道投递状态。响应带 total/page/page_size 供面板翻页。 */
    private String messages(JSONObject p) throws Exception {
        int page = pageOf(p), size = sizeOf(p);
        Db db = db();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("total", db.events());
        o.put("page", page);
        o.put("page_size", size);
        o.put("messages", db.recentJson(size, (page - 1) * size));
        return o.toString();
    }

    /** 发送记录分页。 */
    private String outbox(JSONObject p) throws Exception {
        int page = pageOf(p), size = sizeOf(p);
        Db db = db();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("total", db.countOut());
        o.put("page", page);
        o.put("page_size", size);
        o.put("messages", db.outboxJson(size, (page - 1) * size));
        return o.toString();
    }

    /**
     * 系统收件箱（只读）分页，每条带「已转发」标记。
     * total=-1 表示 ROM 没给出总数，前端降级为只有「下一页」的模式。
     */
    private String sysInbox(JSONObject p) throws Exception {
        int page = pageOf(p), size = sizeOf(p);
        JSONObject r = SysInbox.page(app, page, size);
        JSONObject o = new JSONObject();
        o.put("ok", r != null);
        o.put("page", page);
        o.put("page_size", size);
        if (r == null) {
            o.put("message", "读不到系统收件箱（provider 不可用或权限缺失）");
            return o.toString();
        }
        o.put("total", r.optInt("total", -1));
        o.put("messages", r.optJSONArray("messages"));
        try {
            // 收件箱镜像状态（JZ02 fork）：auto 模式且无默认短信 App = 已开启
            String mode = AppConfig.load(app).mirrorInbox;
            boolean on;
            if (InboxMirror.OFF.equals(mode)) on = false;
            else if (InboxMirror.ON.equals(mode)) on = true;
            else on = InboxMirror.noDefaultSmsApp(app);
            o.put("mirror", on);
        } catch (Throwable ignored) {
        }
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
                .put("port", cfg.webPort).put("user", cfg.webUser).put("pass", cfg.webPass)
                .put("token", cfg.webToken)
                .put("cors", corsJoin(cfg.webCors)));
        return Http.Resp.json(o.toString());
    }

    private Http.Resp saveConfig(Http.Req req) throws Exception {
        String body = req.body == null ? "" : req.body.trim();
        if (!body.startsWith("{")) throw new IllegalArgumentException("配置必须是 JSON 对象");
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("channels");
        if (arr == null) throw new IllegalArgumentException("缺少 channels 数组");

        // 非 root 进程不能绑 1024 以下端口（Android 保留端口），80/443 必然起不来。
        // 不拦的话保存后面板切到 80 绑定失败退出，旧端口也停了——整个面板失联
        // （2026-11-07 真机踩过，只能靠 root 改 /sdcard/smsrelay.conf 救回来）。
        JSONObject webNew = root.optJSONObject("web");
        if (webNew != null) {
            int np = webNew.optInt("port", 8080);
            if (np > 0 && np < 1024) {
                throw new IllegalArgumentException(
                        "端口 " + np + " 不可用：Android 非 root 进程不能绑定 1024 以下的端口"
                        + "（80/443 绑不上），请用 1024–65535 之间的端口");
            }
        }

        // maint / simLock 段有各自的专管接口（维护页 helper 配置、CPU 限频、SIM 锁），
        // 这里的 body 来自前端内存里的整份配置——页面开着的时候如果用户在别处改过
        // 这些设置，旧页面一保存就会把它们抹回旧值（CPU 维持开关被改回「仅一次」
        // 就是这么发生的）。强制用当前生效值回填，这两个段不认前端带来的。
        JSONObject effective = new JSONObject(AppConfig.effectiveJson(app));
        if (effective.has("maint")) root.put("maint", effective.getJSONObject("maint"));
        if (effective.has("simLock")) root.put("simLock", effective.getJSONObject("simLock"));

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

        // 规则段（P0-A）：老页面/局部保存不带 rules 时回填当前生效值，防止整段被抹
        // （同 maint/simLock 的保护思路）。规则编辑器保存时始终带 rules（空数组=清空）
        // 与 rulesDefault，走上面的分支做严格校验。
        if (root.has("rules")) {
            java.util.List<String> chNames = new java.util.ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                chNames.add(arr.getJSONObject(i).optString("name", ""));
            }
            Router.validateRulesJson(root.optJSONArray("rules"), chNames);
        } else if (effective.has("rules")) {
            root.put("rules", effective.getJSONArray("rules"));
        }
        // 告警段（P0-C）：老页面不带 alerts 时回填当前生效值，防止整段被抹
        if (!root.has("alerts") && effective.has("alerts")) {
            root.put("alerts", effective.getJSONObject("alerts"));
        }
        // DDNS 段：records[] 校验 + 老 Token 兜底 + 旧单条格式迁移 + 老页面回填
        if (root.has("ddns")) {
            JSONObject dd = root.optJSONObject("ddns");
            if (dd != null && dd.optBoolean("enabled", false)) {
                String tok = dd.optString("token", "").trim();
                if (tok.length() == 0 && effective.has("ddns")) {
                    // 面板 Token 框留空 = 保持原值，避免每次保存都要重贴 Token
                    dd.put("token", effective.getJSONObject("ddns").optString("token", ""));
                }
                if ("cloudflare".equals(dd.optString("provider", "duckdns"))
                        && dd.optString("zone", "").trim().length() == 0) {
                    throw new IllegalArgumentException("Cloudflare 需要填写 Zone");
                }
                JSONArray rs = dd.optJSONArray("records");
                if (rs == null) {
                    // 旧单条格式（domain/iface/family 顶层）→ 迁移成一条记录
                    String od = dd.optString("domain", "").trim();
                    if (od.length() == 0) throw new IllegalArgumentException("DDNS 至少需要一条解析记录");
                    rs = new JSONArray();
                    JSONObject ro = new JSONObject();
                    ro.put("domain", od);
                    ro.put("iface", dd.optString("iface", ""));
                    ro.put("family", dd.optString("family", "both"));
                    rs.put(ro);
                    dd.put("records", rs);
                }
                int n = rs.length();
                if (n == 0) throw new IllegalArgumentException("DDNS 至少需要一条解析记录");
                if (n > 20) throw new IllegalArgumentException("DDNS 解析记录最多 20 条");
                for (int i = 0; i < n; i++) {
                    JSONObject ro = rs.optJSONObject(i);
                    if (ro == null) throw new IllegalArgumentException("DDNS 记录 #" + (i + 1) + " 格式错误");
                    if (ro.optString("domain", "").trim().length() == 0)
                        throw new IllegalArgumentException("DDNS 记录 #" + (i + 1) + " 缺少域名");
                    String fam = ro.optString("family", "both");
                    if (!"v4".equals(fam) && !"v6".equals(fam) && !"both".equals(fam))
                        throw new IllegalArgumentException("DDNS 记录 #" + (i + 1) + " 记录类型无效：" + fam);
                    String ifc = ro.optString("iface", "").trim();
                    if (ifc.length() > 0 && !ifc.matches("[A-Za-z0-9._:-]{1,20}"))
                        throw new IllegalArgumentException("DDNS 记录 #" + (i + 1) + " 网卡名无效：" + ifc);
                }
            }
        } else if (effective.has("ddns")) {
            root.put("ddns", effective.getJSONObject("ddns"));
        }
        if (root.has("rulesDefault")) {
            String rd = root.optString("rulesDefault", "all");
            if (!"all".equals(rd) && !"drop".equals(rd)) {
                throw new IllegalArgumentException("rulesDefault 只能是 all / drop");
            }
        } else if (effective.has("rulesDefault")) {
            root.put("rulesDefault", effective.optString("rulesDefault", "all"));
        }

        AppConfig old = AppConfig.load(app);
        // ⚠ 必须存处理后的 root（端口校验、maint/simLock 回填都在 root 上），
        // 存原始 body 的话这些保护全部白做
        AppConfig.saveEffective(app, root.toString(2));
        AppConfig now = AppConfig.load(app);
        RelayService.wake(app);

        boolean needRestart = old.webPort != now.webPort
                || old.webEnabled != now.webEnabled
                || !old.webUser.equals(now.webUser)
                || !old.webPass.equals(now.webPass)
                || !old.webToken.equals(now.webToken)
                || !old.webCors.equals(now.webCors);

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
        Router.Result r = Router.routeAll(cfg.names());
        db.ensureTargets(id, r.targets);
        RelayService.wake(app);
        Logs.add("panel", "注入测试消息 evId=" + id + " 目标=" + r.targets.size());
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", id);
        o.put("message", "已注入，目标 " + r.targets.size());
        return o.toString();
    }

    /** 规则校验时的通道名单：优先用请求里带的（面板未保存的改动也算数），否则当前配置。 */
    private java.util.List<String> channelNamesForRules(JSONObject p) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        JSONArray ca = p.optJSONArray("channels");
        if (ca != null) {
            for (int i = 0; i < ca.length(); i++) {
                String n = ca.optString(i, "").trim();
                if (n.length() > 0 && !names.contains(n)) names.add(n);
            }
        }
        if (names.isEmpty()) {
            AppConfig cfg = AppConfig.load(app);
            for (int i = 0; i < cfg.channels.size(); i++) names.add(cfg.channels.get(i).name);
        }
        return names;
    }

    /** /api/rules/validate：校验规则 JSON（不落盘），坏正则/重名/越界直接 400。 */
    private JSONObject rulesValidate(JSONObject p) throws Exception {
        Router.validateRulesJson(p.optJSONArray("rules"), channelNamesForRules(p));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", "规则校验通过");
        return o;
    }

    /** /api/rules/test：匹配测试器——现拼一条事件跑规则，回显命中与投递目标。 */
    private JSONObject rulesTest(JSONObject p) throws Exception {
        String peer = p.optString("peer", "").trim();
        if (peer.length() == 0) throw new IllegalArgumentException("请填发件人号码");
        java.util.List<Router.Rule> rules = Router.parseRules(p.optJSONArray("rules"));
        Router.Result r = Router.preview(peer, p.optString("body", ""), rules,
                channelNamesForRules(p), p.optString("rulesDefault", "all"));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("drop", r.drop);
        if (r.rule != null) o.put("rule", r.rule);
        JSONArray ms = new JSONArray();
        for (int i = 0; i < r.matched.size(); i++) ms.put(r.matched.get(i));
        o.put("matched", ms);
        JSONArray ts = new JSONArray();
        for (int i = 0; i < r.targets.size(); i++) {
            Router.Target t = r.targets.get(i);
            JSONObject to = new JSONObject();
            to.put("ch", t.ch);
            to.put("prio", t.prio);
            if (t.template != null && t.template.length() > 0) to.put("template", t.template);
            ts.put(to);
        }
        o.put("targets", ts);
        o.put("message", r.drop ? "丢弃：不进转发队列（仍入库，面板可人工重发）"
                : "转发到 " + r.targets.size() + " 个目标");
        return o;
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
