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

            JSONObject rel;
            if ("manifest".equals(mode)) {
                rel = fromManifest(cfg);
            } else {
                try {
                    rel = fromGithub(cfg);
                } catch (Throwable api) {
                    // API 限流（同一出口 IP 每小时 60 次）或 api.github.com 不通时，
                    // 退到 release 里挂的 latest.json —— 走 github.com，不占 API 配额。
                    rel = fromReleaseJson(cfg, api);
                }
            }

            String ver = Ver.norm(rel.optString("version", ""));
            st.put("latest", ver);
            st.put("notes", Ver.brief(rel.optString("notes", ""), 400));
            st.put("size", rel.optLong("size", 0));
            st.put("sha256", rel.optString("sha256", ""));
            st.put("url", rel.optString("url", ""));
            st.put("browser", rel.optString("browser", ""));
            st.put("assetId", rel.optLong("assetId", 0));
            st.put("source", rel.optString("source", mode));

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
        String apkBrowser = "";
        long size = 0;
        String sha = "";
        JSONObject jsonAsset = null;
        // 本机型的包：jz02 包带 -jz02 后缀；sp970 包无后缀（排除 -jz02 即可）。
        // 机型匹配的资产出现时覆盖先拿到的，机型不符时退回第一个 .apk（老版本行为）。
        String other = "-jz02.apk";
        String mine = "jz02".equals(modelTag()) ? other : ".apk";
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "").toLowerCase(Locale.US);
            if (name.endsWith(".apk") && (apkId == 0
                    || (mine.equals(other) ? name.endsWith(mine) : !name.endsWith(other)))) {
                apkId = a.optLong("id", 0);
                apkUrl = a.optString("url", "");
                // github.com 上的直链：不占 API 配额，能用就优先用它
                apkBrowser = a.optString("browser_download_url", "");
                size = a.optLong("size", 0);
            } else if (name.startsWith("latest")) {
                jsonAsset = a;
            }
        }
        if (apkId == 0) throw new IllegalStateException("这个 Release 没挂 .apk 资产");
        out.put("assetId", apkId);
        out.put("url", apkUrl);
        out.put("browser", apkBrowser);
        out.put("size", size);

        // 有 latest.json 就取其中的 sha256（可选，失败不影响升级）
        if (jsonAsset != null) {
            try {
                String ju = jsonAsset.optString("browser_download_url", "");
                if (ju.length() == 0) ju = jsonAsset.optString("url", "");
                Net.Resp jr = Net.getAuto(ju, cfg.updateInsecure, "application/json");
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
        JSONObject out = parseManifest(url, cfg, url);
        out.put("source", "manifest");
        return out;
    }

    /**
     * GitHub API 不可用时的兜底：直接读 release 里挂的 latest.json。
     * 这个地址走 github.com（网页/直链），不计入 API 每小时 60 次的配额，
     * 所以限流时仍然能查到版本、拿到 sha256，再从直链下载。
     */
    /** 本机机型标签（升级资产匹配用）：JZ02（UFI 棒子）→ jz02，其余 → sp970。 */
    static String modelTag() {
        try {
            String m = String.valueOf(Build.MODEL).toLowerCase(Locale.US);
            return (m.contains("ufi") || m.contains("jz02")) ? "jz02" : "sp970";
        } catch (Throwable t) {
            return "sp970";
        }
    }

    private static JSONObject fromReleaseJson(AppConfig cfg, Throwable api) throws Exception {
        String repo = cfg.updateRepo == null ? "" : cfg.updateRepo.trim();
        try {
            if (repo.indexOf('/') <= 0) throw new IllegalArgumentException("仓库要写成 owner/repo");
            String base = "https://github.com/" + repo + "/releases/latest/download/";
            // 优先机型清单（latest-jz02.json / latest-sp970.json），老 Release 只有 latest.json
            JSONObject out;
            try {
                out = parseManifest(base + "latest-" + modelTag() + ".json", cfg, base);
            } catch (Throwable noModelJson) {
                out = parseManifest(base + "latest.json", cfg, base);
            }
            out.put("source", "release-json");
            out.put("notes", "GitHub API 不可用（" + msg(api) + "），已改用 release 里的 latest 清单");
            Logs.add("update", "API 不可用，改用 latest 清单：v" + out.optString("version", ""));
            return out;
        } catch (Throwable t) {
            throw new IllegalStateException(msg(api) + "；备用 latest 清单也失败：" + msg(t));
        }
    }

    /**
     * 解析清单。apk 地址可以是绝对地址，也可以是相对清单所在目录的文件名
     * （build.sh 产出的 latest.json 里就是裸文件名）。
     */
    private static JSONObject parseManifest(String url, AppConfig cfg, String base) throws Exception {
        Net.Resp r = Net.getAuto(url, cfg.updateInsecure, "application/json");
        if (r.code != 200) throw new IllegalStateException("manifest 返回 HTTP " + r.code);
        JSONObject j = new JSONObject(r.body);
        JSONObject out = new JSONObject();
        out.put("version", j.optString("version", j.optString("tag_name", "")));
        out.put("notes", j.optString("notes", j.optString("body", "")));
        out.put("size", j.optLong("size", 0));
        out.put("sha256", j.optString("sha256", ""));
        String apk = j.optString("url", "");
        if (apk.length() == 0) apk = j.optString("apk", "");
        if (apk.length() == 0) throw new IllegalStateException("manifest 里没有 url/apk 字段");
        out.put("url", Ver.abs(base, apk));
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
            download(ctx, cfg, url, st.optString("browser", ""), apk, st);

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

    private static void download(final Context ctx, AppConfig cfg, String url, String browser,
                                 File out, final JSONObject st) throws Exception {
        String[] tries = browser.length() > 0 && !browser.equals(url)
                ? new String[]{browser, url} : new String[]{url};
        Net.Resp r = null;
        for (String u : tries) {
            if (u == null || u.length() == 0) continue;
            Net.Resp one = fetch(ctx, cfg, u, out, st);
            r = one;
            if (one.code < 400) break;
            Logs.add("update", "下载源失败 HTTP " + one.code + "：" + Ver.brief(u, 90));
        }
        if (r == null) throw new IllegalStateException("没有可用的下载地址");
        if (r.code >= 400) throw new IllegalStateException("下载失败 HTTP " + r.code);
        try {
            st.put("transport", r.transport);
        } catch (Throwable ignored) {
        }
    }

    private static Net.Resp fetch(final Context ctx, AppConfig cfg, String url,
                                  File out, final JSONObject st) throws Exception {
        final int[] lastPct = new int[]{-1};
        return Net.downloadAuto(url, cfg.updateInsecure, "application/octet-stream", out,
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
    }

    /**
     * 装包两条路：
     *  1. relay-helper root 通道（pm install -r -d）——静默、自动拉起，正常路径
     *  2. 系统安装器 Intent（要人点确认）——helper 没部署时的兜底
     *
     * 注意：装包会杀掉本进程，所以 pm install 与 am start 必须拼在同一条 root 命令里，
     * helper 是独立的 root 进程，本 app 死了它照样能执行完 am start。
     * 固件 su 只放行 uid 0/2000，uid 10075 提不了权，那条分支已彻底删除；
     * 机器自带 webkey 的 /run + Digest 也不再使用。
     */
    private static String install(Context ctx, File apk) {
        String path = apk.getAbsolutePath();
        String chain = "pm install -r -d " + path + "; sleep 2; am start -n "
                + ctx.getPackageName() + "/.MainActivity";
        Root.Result r = Root.exec(ctx, chain);
        if (r.ok() && r.out.toLowerCase(Locale.US).contains("success")) {
            return "已通过 relay-helper 静默安装，服务会自动重启";
        }
        String why = (r.error.length() > 0 ? r.error : r.out).trim();
        if (why.length() == 0) why = "helper 不可用（exit=" + r.exit + "）";
        if (why.length() > 120) why = why.substring(0, 120) + "…";
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
