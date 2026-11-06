package com.sp970.smsrelay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;


/**
 * 面板触发的在线升级。
 *
 * 两种取版本的方式：
 *  - github：GET api.github.com/repos/{repo}/releases/latest，从 release 资产里挑 .apk。
 *    下载走 api 的 /releases/assets/{id}（Accept: octet-stream），它会 302 到
 *    objects.githubusercontent.com —— 实测这台机器 **github.com 连不上**（超时），
 *    但 api 和 objects 都通，所以不能直接用 browser_download_url。
 *  - manifest：GET 一个 JSON（自带 hash 的可放任意镜像），形如
 *    {"version":"1.2","url":"https://…/x.apk","sha256":"…","size":12345,"notes":"…"}
 *
 * 安全上做了三道：
 *  1. 下载完先比 sha256（manifest 有就比）
 *  2. 用 PackageManager 读下载包的签名，必须和已安装的一致，不一致直接拒绝
 *  3. 包的 versionCode 必须比当前大，防止降级循环
 * 传输层默认校验证书；Android 4.4 的根证书是 2013 年的，某些新 CA 会握手失败，
 * 可在面板里开「跳过证书校验」兜底——真被中间人换包也过不了第 2 条。
 */
public final class Update {

    private static final String PREFS = "update";
    private static final String KEY_STATE = "state";

    /** 单次请求超时：这台机器走 2G/3G 回程，别设太短。 */
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 30000;

    private static volatile boolean busy;

    private Update() {
    }

    // ---------- 版本信息 ----------

    public static String currentName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    public static int currentCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------- 状态（面板读它） ----------

    public static JSONObject state(Context ctx) {
        JSONObject o = null;
        try {
            String s = prefs(ctx).getString(KEY_STATE, "");
            if (s != null && s.length() > 0) o = new JSONObject(s);
        } catch (Throwable ignored) {
        }
        if (o == null) o = new JSONObject();
        try {
            String cur = currentName(ctx);
            o.put("current", cur);
            o.put("code", currentCode(ctx));
            o.put("busy", busy);
            o.put("ok", true);
            // 升完级之后缓存里的 found 还留着 true，得按当前版本重算一遍
            String latest = o.optString("latest", "");
            if (latest.length() > 0) {
                boolean now = Ver.newer(latest, cur);
                o.put("found", now);
                if (!now) o.put("message", "已是最新版本 " + cur);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void saveState(Context ctx, JSONObject o) {
        prefs(ctx).edit().putString(KEY_STATE, o.toString()).commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 检查 ----------

    /** 查最新版本，返回状态对象；网络/限流等错误落在 error 字段里，不抛异常。 */
    public static JSONObject check(Context ctx, AppConfig cfg) {
        JSONObject st = new JSONObject();
        JSONObject prev = state(ctx);
        try {
            st.put("current", currentName(ctx));
            st.put("code", currentCode(ctx));
            st.put("checkedAt", System.currentTimeMillis());
            st.put("iso", new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new java.util.Date()));

            String mode = cfg.updateMode;
            if ("off".equals(mode)) {
                st.put("ok", true);
                st.put("found", false);
                st.put("message", "在线升级未启用");
                saveState(ctx, st);
                return st;
            }

            JSONObject rel = "manifest".equals(mode)
                    ? fromManifest(cfg) : fromGithub(cfg);

            String ver = Ver.norm(rel.optString("version", ""));
            st.put("latest", ver);
            st.put("notes", Ver.brief(rel.optString("notes", ""), 400));
            st.put("size", rel.optLong("size", 0));
            st.put("sha256", rel.optString("sha256", ""));
            st.put("url", rel.optString("url", ""));
            st.put("assetId", rel.optLong("assetId", 0));
            st.put("source", mode);

            boolean newer = ver.length() > 0 && Ver.newer(ver, currentName(ctx));
            st.put("found", newer);
            st.put("ok", true);
            st.put("message", newer
                    ? "发现新版本 " + ver + "（当前 " + currentName(ctx) + "）"
                    : "已是最新版本 " + currentName(ctx));
            Logs.add("update", (newer ? "发现新版本 " : "当前已最新 ") + ver);
        } catch (Throwable t) {
            st = new JSONObject();
            try {
                st.put("current", currentName(ctx));
                st.put("code", currentCode(ctx));
                st.put("ok", false);
                st.put("found", false);
                st.put("checkedAt", System.currentTimeMillis());
                st.put("iso", prev.optString("iso", ""));
                st.put("error", msg(t));
                st.put("message", "检查失败：" + msg(t));
            } catch (Throwable ignored) {
            }
            Logs.add("update", "检查失败：" + msg(t));
        }
        saveState(ctx, st);
        return st;
    }

    private static JSONObject fromGithub(AppConfig cfg) throws Exception {
        String repo = cfg.updateRepo == null ? "" : cfg.updateRepo.trim();
        if (repo.indexOf('/') <= 0) throw new IllegalArgumentException("仓库要写成 owner/repo");
        String api = "https://api.github.com/repos/" + repo + "/releases/latest";
        Net.Resp r = Net.getAuto(api, cfg.updateInsecure, "application/vnd.github+json");
        int code = r.code;
        String body = r.body;
        if (code == 403) {
            throw new IllegalStateException("GitHub 限流（同一出口 IP 每小时 60 次），"
                    + "过一会儿再试或改用 manifest 源");
        }
        if (code == 404) {
            throw new IllegalStateException("仓库没有 Release，或仓库不可见（" + repo + "）");
        }
        if (code != 200) throw new IllegalStateException("GitHub 返回 HTTP " + code);
        JSONObject rel = new JSONObject(body);

        JSONObject out = new JSONObject();
        out.put("version", rel.optString("tag_name", ""));
        out.put("notes", rel.optString("body", ""));
        out.put("url", rel.optString("html_url", ""));

        JSONArray assets = rel.optJSONArray("assets");
        long apkId = 0;
        String apkUrl = "";
        long size = 0;
        String sha = "";
        JSONObject jsonAsset = null;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "").toLowerCase(Locale.US);
            if (name.endsWith(".apk") && apkId == 0) {
                apkId = a.optLong("id", 0);
                // 不用 browser_download_url：它指到 github.com，这台机器连不上
                apkUrl = a.optString("url", "");
                size = a.optLong("size", 0);
            } else if (name.equals("latest.json")) {
                jsonAsset = a;
            }
        }
        if (apkId == 0) throw new IllegalStateException("这个 Release 没挂 .apk 资产");
        out.put("assetId", apkId);
        out.put("url", apkUrl);
        out.put("size", size);

        // 有 latest.json 就取其中的 sha256（可选，失败不影响升级）
        if (jsonAsset != null) {
            try {
                Net.Resp jr = Net.getAuto(jsonAsset.optString("url", ""), cfg.updateInsecure,
                        "application/octet-stream");
                if (jr.code == 200) {
                    JSONObject j = new JSONObject(jr.body);
                    sha = j.optString("sha256", "");
                    if (j.optLong("size", 0) > 0) out.put("size", j.optLong("size"));
                }
            } catch (Throwable ignored) {
            }
        }
        out.put("sha256", sha);
        return out;
    }

    private static JSONObject fromManifest(AppConfig cfg) throws Exception {
        String url = cfg.updateManifest == null ? "" : cfg.updateManifest.trim();
        if (!url.startsWith("http")) throw new IllegalArgumentException("manifest 地址要以 http 开头");
        Net.Resp r = Net.getAuto(url, cfg.updateInsecure, "application/json");
        int code = r.code;
        String body = r.body;
        if (code != 200) throw new IllegalStateException("manifest 返回 HTTP " + code);
        JSONObject j = new JSONObject(body);
        JSONObject out = new JSONObject();
        out.put("version", j.optString("version", j.optString("tag_name", "")));
        out.put("notes", j.optString("notes", j.optString("body", "")));
        out.put("size", j.optLong("size", 0));
        out.put("sha256", j.optString("sha256", ""));
        String apk = j.optString("url", "");
        if (apk.length() == 0) throw new IllegalStateException("manifest 里没有 url 字段");
        out.put("url", apk);
        return out;
    }

    // ---------- 下载 + 安装 ----------

    public static JSONObject apply(Context ctx, AppConfig cfg) {
        JSONObject r = new JSONObject();
        if (busy) {
            try {
                r.put("ok", false);
                r.put("message", "上一次升级还在进行中");
            } catch (Throwable ignored) {
            }
            return r;
        }
        busy = true;
        File apk = null;
        try {
            JSONObject st = state(ctx);
            String url = st.optString("url", "");
            String ver = st.optString("latest", "");
            if (url.length() == 0) throw new IllegalStateException("先点「检查更新」");
            if (!Ver.newer(ver, currentName(ctx))) {
                throw new IllegalStateException("当前已是最新版本 " + currentName(ctx));
            }

            apk = new File(dir(), "smsrelay-" + ver + ".apk");
            if (apk.exists()) apk.delete();
            Logs.add("update", "开始下载 " + ver + " → " + apk.getName());
            download(ctx, cfg, url, st.optLong("assetId", 0), apk, st);

            // 1) sha256
            String want = st.optString("sha256", "");
            if (want.length() > 0) {
                String got = Ver.sha256(readFile(apk));
                if (!want.equalsIgnoreCase(got)) {
                    apk.delete();
                    throw new IllegalStateException("sha256 不符，已删除安装包（期望 "
                            + want.substring(0, Math.min(12, want.length())) + "… 实际 "
                            + got.substring(0, Math.min(12, got.length())) + "…）");
                }
            }

            // 2) 签名与已装应用一致 + 3) versionCode 递增
            PackageManager pm = ctx.getPackageManager();
            PackageInfo np = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (np == null) throw new IllegalStateException("安装包读不出来，可能下载不完整");
            PackageInfo cur = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (!sameSig(cur, np)) {
                apk.delete();
                throw new IllegalStateException("签名和已安装版本不一致，拒绝安装（换过签名就只能卸载重装）");
            }
            if (np.versionCode <= cur.versionCode) {
                throw new IllegalStateException("安装包 versionCode " + np.versionCode
                        + " 不比当前 " + cur.versionCode + " 大，跳过");
            }

            String how = install(ctx, apk);
            r.put("ok", true);
            r.put("message", "已下载 " + Ver.size(apk.length()) + "，" + how);
            r.put("how", how);
            Logs.add("update", "升级 " + ver + "：" + how);
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("message", msg(t));
            } catch (Throwable ignored) {
            }
            Logs.add("update", "升级失败：" + msg(t));
        } finally {
            busy = false;
        }
        return r;
    }

    private static void download(final Context ctx, AppConfig cfg, String url, long assetId,
                                 File out, final JSONObject st) throws Exception {
        final int[] lastPct = new int[]{-1};
        Net.Resp r = Net.downloadAuto(url, cfg.updateInsecure, "application/octet-stream", out,
                new Net.Progress() {
                    @Override
                    public void on(long done, long total) {
                        if (total <= 0) return;
                        int pct = (int) (done * 100 / total);
                        if (pct == lastPct[0] || pct % 20 != 0) return;
                        lastPct[0] = pct;
                        try {
                            st.put("phase", "下载 " + pct + "%");
                            saveState(ctx, st);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        if (r.code >= 400) throw new IllegalStateException("下载失败 HTTP " + r.code);
        try {
            st.put("transport", r.transport);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 装包三条路，按可靠性排序：
     *  1. su -c pm install（有 Magisk/SuperSU 授权就是静默的）
     *  2. 机器自带 web 后台的 root 通道（这台 SP970 的 http://127.0.0.1/run，digest 后以 root 执行；
     *     只发一条短命令，不传大 body —— 这个通道传大数据会把整机搞掉线）
     *  3. 系统安装器 Intent（要人点确认）
     */
    private static String install(Context ctx, File apk) {
        String path = apk.getAbsolutePath();
        // 装包会把本进程杀掉，所以 pm install 和 am start 必须同一条命令里发出去，
        // 否则进程死了，后面的 am start 根本发不出来（实测面板会一直停着）。
        String chain = "pm install -r -d " + path + "; sleep 2; am start -n "
                + ctx.getPackageName() + "/.MainActivity";
        String out = root(chain);
        if (out.toLowerCase(Locale.US).contains("success")) {
            return "已用 root 静默安装，服务会自动重启";
        }
        String why = out.length() > 0 ? out.trim() : "su 不可用";

        String wk = webkey(ctx, chain);
        if (wk.toLowerCase(Locale.US).contains("success")) {
            return "已通过机器自带 web 后台（root 通道）静默安装，服务会自动重启";
        }
        if (wk.length() > 0) why = why + "；webkey: " + wk.trim();
        if (why.length() > 120) why = why.substring(0, 120) + "…";
        // 交给系统安装器：4.4 上会弹一次确认
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "已下载到 " + path + "（静默安装失败：" + why + "，已唤起系统安装器，需手动确认）";
        } catch (Throwable t) {
            return "已下载到 " + path + "，需要手动安装：adb install -r " + path
                    + "（静默安装失败：" + why + "）";
        }
    }

    /** 用 su 跑一条命令，返回合并输出；拿不到 root 就返回原因。 */
    private static String root(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c",
                    "su -c \"" + cmd.replace("\"", "\\\"") + "\"")
                    .redirectErrorStream(true).start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream in = fp.getInputStream();
                    try {
                        byte[] b = new byte[512];
                        int n;
                        while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int code = p.exitValue();
                    reader.join(2000);
                    return "exit=" + code + " " + sb.toString().trim();
                } catch (IllegalThreadStateException alive) {
                    Thread.sleep(500);
                }
            }
            p.destroy();
            return "超时 " + sb.toString().trim();
        } catch (Throwable t) {
            return msg(t);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 走本机 web 后台的 /run 通道执行 root 命令（这台机器上是 root 身份）。
     *
     * 只能用 digest：Android 4.4 的 HttpURLConnection 是 OkHttp 1.x，根本不实现 Digest，
     * java.net.Authenticator 设了也没用（会一直 401）。这里自己算：
     *   HA1 = MD5(user:realm:pass)，HA2 = MD5(POST:uri)
     *   response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
     */
    private static String webkey(Context ctx, String cmd) {
        AppConfig cfg = AppConfig.load(ctx);
        String cred = cfg.updateWebkey;
        if (cred == null || cred.indexOf(':') <= 0) return "";
        String user = cred.substring(0, cred.indexOf(':'));
        String pass = cred.substring(cred.indexOf(':') + 1);
        String uri = "/run";
        try {
            // 1) 先探一下挑战
            java.net.HttpURLConnection probe = post("http://127.0.0.1" + uri, cmd, null);
            int code = probe.getResponseCode();
            if (code == 200) return readBody(probe);
            String www = probe.getHeaderField("WWW-Authenticate");
            try {
                probe.disconnect();
            } catch (Throwable ignored) {
            }
            if (www == null || !www.toLowerCase(Locale.US).contains("digest")) {
                Logs.add("update", "webkey 需要 digest，但服务端回了: " + www);
                return "";
            }
            String realm = param(www, "realm");
            String nonce = param(www, "nonce");
            String qop = param(www, "qop");
            if (qop == null || qop.length() == 0) qop = "auth";
            String nc = "00000001";
            String cnonce = Long.toHexString(System.currentTimeMillis());
            String ha1 = MD5.of(user + ":" + realm + ":" + pass);
            String ha2 = MD5.of("POST:" + uri);
            String resp = MD5.of(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
            String auth = "Digest username=\"" + user + "\", realm=\"" + realm
                    + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", algorithm=MD5"
                    + ", response=\"" + resp + "\", qop=" + qop + ", nc=" + nc
                    + ", cnonce=\"" + cnonce + "\"";

            // 2) 带签名重试
            java.net.HttpURLConnection c = post("http://127.0.0.1" + uri, cmd, auth);
            int c2 = c.getResponseCode();
            String body = readBody(c);
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
            Logs.add("update", "webkey " + cmd + " → HTTP " + c2 + " "
                    + (body.length() > 60 ? body.substring(0, 60) : body));
            return c2 == 200 ? body : "";
        } catch (Throwable t) {
            Logs.add("update", "webkey 失败：" + msg(t));
            return "";
        }
    }

    private static java.net.HttpURLConnection post(String url, String body, String auth)
            throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        c.getOutputStream().write(body.getBytes("UTF-8"));
        c.getOutputStream().flush();
        return c;
    }

    private static String readBody(java.net.HttpURLConnection c) {
        try {
            java.io.InputStream in = c.getResponseCode() >= 400
                    ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 WWW-Authenticate 里抠 key="value"。 */
    private static String param(String header, String key) {
        String k = key + "=";
        int i = header.toLowerCase(Locale.US).indexOf(k);
        if (i < 0) return "";
        String rest = header.substring(i + k.length()).trim();
        if (rest.startsWith("\"")) {
            int e = rest.indexOf('"', 1);
            return e > 0 ? rest.substring(1, e) : "";
        }
        int e = rest.indexOf(',');
        return (e > 0 ? rest.substring(0, e) : rest).trim();
    }

    private static boolean sameSig(PackageInfo a, PackageInfo b) {
        try {
            Signature[] x = a.signatures;
            Signature[] y = b.signatures;
            if (x == null || y == null || x.length == 0 || y.length == 0) return false;
            return Ver.sha256(x[0].toByteArray()).equals(Ver.sha256(y[0].toByteArray()));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 定时自检 ----------

    /** 服务循环里顺手调用：到点就在后台查一次，绝不阻塞投递。 */
    public static void tick(final Context ctx) {
        if (busy) return;
        try {
            final AppConfig cfg = AppConfig.load(ctx);
            String mode = cfg.updateMode;
            if (!cfg.updateAuto) return;
            if (!"github".equals(mode) && !"manifest".equals(mode)) return;
            JSONObject st = state(ctx);
            long last = st.optLong("checkedAt", 0);
            long gap = Math.max(1, cfg.updateIntervalH) * 3600L * 1000L;
            if (System.currentTimeMillis() - last < gap) return;
            // 先占位，避免多轮循环重复触发
            st.put("checkedAt", System.currentTimeMillis());
            saveState(ctx, st);
            final AppConfig c = cfg;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject r = check(ctx, c);
                        if (r.optBoolean("found")) {
                            Logs.add("update", "自动检查：有新版本 " + r.optString("latest")
                                    + "，打开面板可一键升级");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    // ---------- HTTP ----------

    // ---------- 小工具 ----------

    private static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Download");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static byte[] readFile(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(4096, f.length()));
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String msg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (t instanceof javax.net.ssl.SSLException || m.toLowerCase(Locale.US).contains("ssl")
                || m.toLowerCase(Locale.US).contains("certificate")) {
            m = m + "（4.4 的根证书较旧，可在升级页试「跳过证书校验」）";
        }
        return m;
    }
}
