package com.sp970.smsrelay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;

/**
 * 系统收件箱镜像（JZ02 / 无默认短信 App 的设备）。
 *
 * SP970 有 Mms 作为默认短信 App，收到短信它会照常写 content://sms/inbox；
 * JZ02 精简后系统里没有任何短信应用，4.4 的短信 provider 因此永远真空——
 * 广播照样到（转发记录正常），但「系统收件箱」「补收」全是死页面。
 *
 * 这里把 app 收到的 Broadcast 事件再写一份进 provider（只镜像收到，不动转发链路）：
 *
 *   1. 先尝试 app 进程直写（未来若有默认短信 App 授权给本 app 时零开销）；
 *      4.4 上非默认短信 App 写收件箱会吃 SecurityException，转路径 2；
 *   2. root 代理：经 relay-helper 的 cmd 通道跑
 *        CLASSPATH=<本 app apk> app_process /system/bin com.sp970.smsrelay.InboxMirror insert …
 *      再走 dalvikvm 的 dialer 里 AMS.getContentProviderExternal("sms") → provider.insert，
 *      uid=0 绕开 4.4 的写权限（实测可行，且 date 用 Long 原样入库——不要走 `content insert`，
 *      那条 CLI 的数字绑定在这台 ROM 上会按 32 位截断）。
 *
 * 写之前先按「号码+内容+90s 窗口」查 provider 防重复，与 Db 判重口径一致，所以系统收件箱
 * 页面上的「已转发」标记天然成立，补收也不会重复转发。
 *
 * 开关（/sdcard/smsrelay.conf 的 mirrorInbox 字段）：
 *   auto（默认）= 无默认短信 App（没有别人负责写收件箱）时才镜像，SP970 上自动等于 off；
 *   on  = 强制镜像；off = 关闭。
 */
public final class InboxMirror {

    private static final Uri INBOX = Uri.parse("content://sms/inbox");
    /** 与 Db 的判重同窗口：广播时间戳与库里时间一致，窗口只防广播重发与补收往返。 */
    private static final long DUP_WINDOW_MS = 90L * 1000L;
    /** root 代理命令体上限（helper 单条 64KB），base64 后接近上限就整块放弃。 */
    private static final int MAX_CMD = 48000;
    private static final int BACKFILL_ROWS = 300;

    public static final String AUTO = "auto";
    public static final String ON = "on";
    public static final String OFF = "off";

    /** 连续失败达到这个数就在本进程内停用，避免日志和 helper 被刷。 */
    private static final int FAIL_LIMIT = 5;

    private static final java.util.concurrent.atomic.AtomicInteger rootFails =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile boolean disabled = false;
    private static volatile boolean backfillDone = false;

    /** 收件箱镜像全部操作走同一个单线程：串行化 root 代理调用，并消除
     *  store 与回填并发写造成的「都查不到对方 → 双写」竞态。 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "inbox-mirror");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private InboxMirror() {
    }

    // ---------- app 侧 ----------

    /** 收到短信后异步镜像（SmsReceiver / 注入测试都会调）。绝不阻塞、绝不影响转发主链路。 */
    public static void storeAsync(final Context ctx, final String peer, final String body, final long ts) {
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    store(ctx, peer, body, ts);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 服务启动后把 app 库里已有、但 provider 里没有的短信回填进收件箱（每次进程一次）。 */
    public static void backfillAsync(final Context ctx) {
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    backfill(ctx);
                } catch (Throwable t) {
                    try { Logs.add("mirror", "回填异常: " + t); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /** @return true=镜像成功（或 provider 里本来就有） */
    static boolean store(Context ctx, String peer, String body, long ts) {
        if (disabled) return false;
        try {
            AppConfig cfg = AppConfig.load(ctx);
            if (OFF.equals(cfg.mirrorInbox)) return false;
            if (AUTO.equals(cfg.mirrorInbox) && !noDefaultSmsApp(ctx)) return false;

            if (providerHas(ctx, peer, body, ts)) return true;
            if (directInsert(ctx, peer, body, ts)) {
                rootFails.set(0);
                return true;
            }
            boolean ok = rootInsert(ctx, peer, body, ts);
            if (ok) {
                rootFails.set(0);
            } else if (rootFails.incrementAndGet() >= FAIL_LIMIT) {
                disabled = true;
                Logs.add("mirror", "连续 " + FAIL_LIMIT + " 次镜像失败，本进程内停用收件箱镜像");
            }
            return ok;
        } catch (Throwable t) {
            try { Logs.add("mirror", "镜像异常: " + t); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static void backfill(Context ctx) {
        synchronized (InboxMirror.class) {
            if (backfillDone) return;
            backfillDone = true;
        }
        try {
            AppConfig cfg = AppConfig.load(ctx);
            if (OFF.equals(cfg.mirrorInbox)) return;
            if (AUTO.equals(cfg.mirrorInbox) && !noDefaultSmsApp(ctx)) return;

            Db db = new Db(ctx);
            Cursor c = null;
            int checked = 0, wrote = 0;
            try {
                c = db.getReadableDatabase().rawQuery(
                        "SELECT peer, body, ts FROM ev ORDER BY ts DESC LIMIT " + BACKFILL_ROWS, null);
                JSONArray chunk = new JSONArray();
                while (c.moveToNext()) {
                    checked++;
                    String peer = c.getString(0);
                    String body = c.getString(1) == null ? "" : c.getString(1);
                    long ts = c.getLong(2);
                    if (providerHas(ctx, peer, body, ts)) continue;
                    chunk.put(row(peer, body, ts));
                    if (b64(chunk.toString()).length() > MAX_CMD) {
                        wrote += rootBatch(ctx, chunk);
                        chunk = new JSONArray();
                    }
                }
                if (chunk.length() > 0) wrote += rootBatch(ctx, chunk);
            } finally {
                if (c != null) c.close();
            }
            if (checked > 0) Logs.add("mirror", "收件箱回填：检查 " + checked + " 条，补写 " + wrote + " 条");
        } catch (Throwable t) {
            try { Logs.add("mirror", "回填失败: " + t); } catch (Throwable ignored) {}
        }
    }

    private static org.json.JSONObject row(String peer, String body, long ts) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("peer", peer == null ? "" : peer);
            o.put("body", body == null ? "" : body);
            o.put("ts", ts);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 一批一起塞进 root 代理（一次 app_process 起动写几十条，别每条起一个 JVM）。 */
    private static int rootBatch(Context ctx, JSONArray chunk) {
        String payload = b64(chunk.toString());
        if (payload.length() > MAX_CMD) {
            Logs.add("mirror", "回填块过大（" + payload.length() + "B），放弃");
            return 0;
        }
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            String cmd = "CLASSPATH=" + q(apk) + " app_process /system/bin "
                    + InboxMirror.class.getName() + " batch " + payload;
            Root.Result r = Root.exec(ctx, cmd);
            int n = -1;
            if (r.ok()) {
                String[] lines = r.out.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String t = lines[i].trim();
                    if (t.startsWith("OK ")) {
                        try {
                            n = Integer.parseInt(t.substring(3).trim());
                        } catch (Throwable ignored) {
                        }
                        break;
                    }
                    if (t.startsWith("ERR ")) break;
                }
            }
            if (n >= 0) return n;
            Logs.add("mirror", "回填 root 代理失败: "
                    + (r.error.length() > 0 ? r.error : r.out));
        } catch (Throwable t) {
            try { Logs.add("mirror", "回填 root 代理异常: " + t); } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** provider 里是否已有同号码同年内容、同窗口的短信。 */
    private static boolean providerHas(Context ctx, String peer, String body, long ts) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(INBOX, new String[]{"_id"},
                    "address=? AND body=? AND date BETWEEN ? AND ?",
                    new String[]{peer == null ? "" : peer, body == null ? "" : body,
                            String.valueOf(ts - DUP_WINDOW_MS), String.valueOf(ts + DUP_WINDOW_MS)},
                    null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            try { Logs.add("mirror", "provider 判重读取失败: " + t); } catch (Throwable ignored) {}
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    /** 路径 1：app 进程直写（别人负责收件箱 / provider 放行 / 未来的默认短信 App）。 */
    private static boolean directInsert(Context ctx, String peer, String body, long ts) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("address", peer == null ? "" : peer);
            cv.put("body", body == null ? "" : body);
            cv.put("date", ts);
            Uri u = ctx.getContentResolver().insert(INBOX, cv);
            if (u != null) {
                Logs.add("mirror", "收件箱镜像成功（app 直写）: " + peer);
                return true;
            }
        } catch (SecurityException e) {
            // 非默认短信 App（或 AppOps 未放行时）预期行为，转 root 代理；
            // 日志带出异常消息方便区分「未设默认」与「AppOps 缓存竞态」
            Logs.add("mirror", "app 直写受限（SecurityException，转 root 代理）: " + e.getMessage());
        } catch (Throwable t) {
            try { Logs.add("mirror", "app 直写失败: " + t); } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 路径 2：root 代理（helper cmd → app_process 起一个 dalvik 进程写 provider）。 */
    private static boolean rootInsert(Context ctx, String peer, String body, long ts) {
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            String payload = b64(body);
            if (payload.length() > MAX_CMD) {
                Logs.add("mirror", "正文过大（base64 " + payload.length() + "B），不镜像");
                return false;
            }
            String cmd = "CLASSPATH=" + q(apk) + " app_process /system/bin "
                    + InboxMirror.class.getName()
                    + " insert " + b64(peer) + " " + payload + " " + ts;
            Root.Result r = Root.exec(ctx, cmd);
            if (r.ok() && r.out.startsWith("OK ")) {
                Logs.add("mirror", "收件箱镜像成功（root 代理）: " + peer);
                return true;
            }
            Logs.add("mirror", "root 代理镜像失败: "
                    + (r.error.length() > 0 ? r.error : r.out));
        } catch (Throwable t) {
            try { Logs.add("mirror", "root 代理镜像异常: " + t); } catch (Throwable ignored) {}
        }
        return false;
    }

    /** no-default-SMS-App 判定：没有短信应用负责写收件箱（JZ02），或本 app 自己就是默认。 */
    static boolean noDefaultSmsApp(Context ctx) {
        try {
            String pkg = Telephony.Sms.getDefaultSmsPackage(ctx);
            if (pkg == null || pkg.trim().length() == 0) return true;
            return ctx.getPackageName().equals(pkg);
        } catch (Throwable t) {
            return true; // 查不到时保守开启；写入失败有 fail 计数兜底
        }
    }

    // ---------- root 代理侧（app_process 入口，独立 JVM，uid=0） ----------

    /** 一次 agent 进程内复用的 sms provider（batch 模式几十条只走一次反射）。 */
    private static volatile Object cachedProvider;

    /**
     * root 进程里写 provider：直接 new ContentResolver().insert 会吃
     * 「Unable to find app for caller …」——AMS 要求调用方有注册过的 ApplicationThread。
     * 与 4.4 的 content 命令同路：ActivityThread.systemMain() 起系统 context（需先
     * Looper.prepare），再用 AMS.getContentProviderExternal("sms") 拿到 IContentProvider
     * 直调 insert(callingPkg, uri, values)。provider insert 会按「窗口内同号码同内容」
     * 的 app 侧判重先行盘子头上（见 providerHas），这里不做二次去重。
     *
     * insert <b64peer> <b64body> <ts>   —— 写一条
     * batch  <b64json>                 —— JSON 数组批量写（rows: peer/body/ts），回 "OK <n>"
     */
    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.out.println("ERR usage: insert <b64peer> <b64body> <ts> | batch <b64json>");
            } else if ("batch".equals(args[0])) {
                System.out.println("OK " + batchAsRoot(new String(
                        Base64.decode(args[1], Base64.NO_WRAP), "UTF-8")));
            } else if ("insert".equals(args[0])) {
                String peer = new String(Base64.decode(args[1], Base64.NO_WRAP), "UTF-8");
                String body = new String(Base64.decode(args[2], Base64.NO_WRAP), "UTF-8");
                Uri u = insertAsRoot(peer, body, Long.parseLong(args[3]));
                System.out.println("OK " + (u == null ? "-" : u.toString()));
            } else {
                System.out.println("ERR unknown mode " + args[0]);
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            System.out.println("ERR " + (m == null ? t.toString() : m));
        }
        System.exit(0);
    }

    private static int batchAsRoot(String json) {
        int n = 0;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject row = a.optJSONObject(i);
                if (row == null) continue;
                try {
                    insertAsRoot(row.optString("peer", ""), row.optString("body", ""), row.optLong("ts", 0L));
                    n++;
                } catch (Throwable rowFail) {
                    // 单行失败不中断整批：这一行 provider 里会有 app 库记录，可再等下次回填
                    Log.i("SmsRelay-mirror", "batch 行失败: " + rowFail); // 只进 logcat，别污染 stdout 协议
                }
            }
        } catch (Throwable ignored) {
            // json 坏了：按 0 返回，OK <n> 依旧成立
        }
        return n;
    }

    /** 确保主线程有 Looper（systemMain 需要一个 handler；只准备一次，本进程只允许一个）。 */
    private static void prepareLooper() throws Exception {
        try {
            android.os.Looper.prepare();
        } catch (RuntimeException already) {
            // 已经 prepare 过：「Only one Looper may be created per thread」
            // KitKat 这里抛 RuntimeException 不是 IllegalStateException，别只抓后者
        }
    }

    private static Uri insertAsRoot(String peer, String body, long ts) throws Exception {
        prepareLooper();
        Object prov = cachedProvider;
        if (prov == null) {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
            if (ctx == null) throw new IllegalStateException("getSystemContext 失败");

            Object am = Class.forName("android.app.ActivityManagerNative")
                    .getMethod("getDefault").invoke(null);
            Object holder = am.getClass().getMethod("getContentProviderExternal",
                    String.class, int.class, android.os.IBinder.class)
                    .invoke(am, "sms", 0, new android.os.Binder());
            prov = holder.getClass().getField("provider").get(holder);
            cachedProvider = prov;
        }

        ContentValues cv = new ContentValues();
        cv.put("address", peer == null ? "" : peer);
        cv.put("body", body == null ? "" : body);
        cv.put("date", ts);
        return (Uri) prov.getClass().getMethod("insert", String.class, Uri.class, ContentValues.class)
                .invoke(prov, "com.sp970.smsrelay", INBOX, cv);
    }

    // ---------- 小工具 ----------

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String b64(String s) {
        try {
            return Base64.encodeToString((s == null ? "" : s).getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }
}
