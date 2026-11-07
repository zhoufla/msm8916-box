package t;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;

/** 原型：root 进程里直接走系统 context 写 SMS provider（打印反射失败栈）。
 *  batch 模式复刻 InboxMirror.batchAsRoot/insertAsRoot 逻辑，带逐行异常输出。 */
public class Tiny {

    static Object cachedProvider;

    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 0) {
                System.out.println("usage: insert <address> <body> <ts> | delsql <ids> | sql"
                        + " | batch <b64json> | batchraw <peer1> <body1> <ts1> ..."
                        + " | checkdefault [pkg]");
                System.exit(2);
            }
            if (args[0].equals("delsql")) {                // 一次性运维：root 直连 SQLite 删探针行（content delete 在这台 ROM 上是空操作）
                android.database.sqlite.SQLiteDatabase db =
                        android.database.sqlite.SQLiteDatabase.openDatabase(
                                "/data/data/com.android.providers.telephony/databases/mmssms.db",
                                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
                db.execSQL("DELETE FROM sms WHERE _id IN (" + args[1] + ")");
                db.execSQL("DELETE FROM threads WHERE _id NOT IN (SELECT DISTINCT thread_id FROM sms)");
                System.out.println("DELOK");
            } else if (args[0].equals("checkdefault")) {
                // 四组件自检（纯 PM 查询，不碰 Settings——代理进程没注册 ApplicationThread，
                // Settings 走 ContentResolver 会吃 "Unable to find app for caller"，
                // 这正是 framework 收到真实 SMS 时用 phone 进程权限做的那次校验）
                Context c = getSystemContext();
                if (c == null) System.exit(3);
                String pkg = args.length > 1 ? args[1] : "com.sp970.smsrelay";
                android.content.pm.PackageManager pm = c.getPackageManager();
                boolean smsOk = false, wapOk = false, rvmOk = false, toOk = false;
                String smsCls = "-", wapCls = "-", rvmCls = "-", toCls = "-";
                for (android.content.pm.ResolveInfo ri : pm.queryBroadcastReceivers(
                        new android.content.Intent("android.provider.Telephony.SMS_DELIVER"), 0)) {
                    if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)
                            && "android.permission.BROADCAST_SMS".equals(ri.activityInfo.permission)) {
                        smsOk = true; smsCls = ri.activityInfo.name;
                    }
                }
                android.content.Intent wi =
                        new android.content.Intent("android.provider.Telephony.WAP_PUSH_DELIVER");
                wi.setDataAndType(null, "application/vnd.wap.mms-message");
                for (android.content.pm.ResolveInfo ri : pm.queryBroadcastReceivers(wi, 0)) {
                    if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)
                            && "android.permission.BROADCAST_WAP_PUSH".equals(ri.activityInfo.permission)) {
                        wapOk = true; wapCls = ri.activityInfo.name;
                    }
                }
                android.content.Intent ri2 =
                        new android.content.Intent("android.intent.action.RESPOND_VIA_MESSAGE",
                                android.net.Uri.fromParts("smsto", "", null));
                for (android.content.pm.ResolveInfo rr : pm.queryIntentServices(ri2, 0)) {
                    if (rr.serviceInfo != null && pkg.equals(rr.serviceInfo.packageName)
                            && "android.permission.SEND_RESPOND_VIA_MESSAGE".equals(rr.serviceInfo.permission)) {
                        rvmOk = true; rvmCls = rr.serviceInfo.name;
                    }
                }
                android.content.Intent si = new android.content.Intent(android.content.Intent.ACTION_SENDTO,
                        android.net.Uri.fromParts("smsto", "", null));
                for (android.content.pm.ResolveInfo rr : pm.queryIntentActivities(si, 0)) {
                    if (rr.activityInfo != null && pkg.equals(rr.activityInfo.packageName)) {
                        toOk = true; toCls = rr.activityInfo.name;
                    }
                }
                System.out.println("COMPONENTS smsDeliver=" + smsOk + "(" + smsCls + ")"
                        + " wapDeliver=" + wapOk + "(" + wapCls + ")"
                        + " respondVia=" + rvmOk + "(" + rvmCls + ")"
                        + " sendTo=" + toOk + "(" + toCls + ")");
                System.out.println(smsOk && wapOk && rvmOk && toOk ? "COMPLETE" : "INCOMPLETE");
            } else if (args[0].equals("sql")) {
                // 校验真实存储值：直接只读打开 mmssms.db
                android.database.sqlite.SQLiteDatabase db =
                        android.database.sqlite.SQLiteDatabase.openDatabase(
                                "/data/data/com.android.providers.telephony/databases/mmssms.db",
                                null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                android.database.Cursor c = db.rawQuery(
                        "select _id,address,body,date,type from sms order by _id desc limit 8", null);
                while (c.moveToNext()) {
                    System.out.println("ROW " + c.getLong(0) + " " + c.getString(1) + " "
                            + c.getString(2) + " ts=" + c.getLong(3) + " type=" + c.getLong(4));
                }
                c.close();
            } else if (args[0].equals("batchraw")) {
                int n = 0;
                for (int i = 1; i + 2 < args.length; i += 3) {
                    try {
                        System.out.println("ROWTRY " + args[i + 1]);
                        insertAsRoot(args[i], args[i + 1], Long.parseLong(args[i + 2]));
                        n++;
                        System.out.println("ROWOK " + args[i + 1]);
                    } catch (Throwable rowFail) {
                        System.out.println("ROWERR " + args[i + 1] + " : " + rowFail);
                        rowFail.printStackTrace(System.out);
                    }
                }
                System.out.println("OK " + n);
            } else if (args[0].equals("batch")) {
                int n = 0;
                JSONArray a = new JSONArray(new String(
                        android.util.Base64.decode(args[1], android.util.Base64.NO_WRAP), "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject row = a.optJSONObject(i);
                    if (row == null) continue;
                    try {
                        System.out.println("ROWTRY " + row.optString("body", ""));
                        insertAsRoot(row.optString("peer", ""), row.optString("body", ""),
                                row.optLong("ts", 0L));
                        n++;
                        System.out.println("ROWOK " + row.optString("body", ""));
                    } catch (Throwable rowFail) {
                        System.out.println("ROWERR " + row.optString("body", "") + " : " + rowFail);
                        rowFail.printStackTrace(System.out);
                    }
                }
                System.out.println("OK " + n);
            } else {
                Context ctx = getSystemContext();
                System.out.println("CTX " + (ctx == null ? "null" : ctx.getClass().getName()));
                if (ctx == null) System.exit(3);
                ContentResolver cr = ctx.getContentResolver();
                if (args[0].equals("insert")) {
                    ContentValues cv = new ContentValues();
                    cv.put("address", args[1]);
                    cv.put("body", args[2]);
                    cv.put("date", Long.parseLong(args[3]));
                    Uri u = insertViaExternalProvider(cr, cv);
                    System.out.println("URI " + u);
                }
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t);
            t.printStackTrace(System.out);
            System.exit(9);
        }
        System.exit(0);
    }

    /** 与 InboxMirror.insertAsRoot 相同逻辑（含 cachedProvider 复用）。 */
    static Uri insertAsRoot(String peer, String body, long ts) throws Exception {
        try {
            android.os.Looper.prepare();   // KitKat 抛 RuntimeException，不是 IllegalStateException
        } catch (RuntimeException already) {
        }
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
                .invoke(prov, "com.sp970.smsrelay", Uri.parse("content://sms/inbox"), cv);
    }

    /** 与 4.4 content 命令同路：ContentProviderHolder + getContentProviderExternal。 */
    static Uri insertViaExternalProvider(ContentResolver cr, ContentValues cv) throws Exception {
        Class<?> amn = Class.forName("android.app.ActivityManagerNative");
        Object am = amn.getMethod("getDefault").invoke(null);
        android.os.IBinder token = new android.os.Binder();
        Object holder = am.getClass().getMethod("getContentProviderExternal",
                String.class, int.class, android.os.IBinder.class).invoke(am, "sms", 0, token);
        Object prov = holder.getClass().getField("provider").get(holder);
        return (Uri) prov.getClass().getMethod("insert", String.class, Uri.class, ContentValues.class)
                .invoke(prov, "com.sp970.smsrelay", Uri.parse("content://sms/inbox"), cv);
    }

    private static Context getSystemContext() {
        try {
            android.os.Looper.prepare();   // systemMain() 会在主线程造 handler，必须先 prepare
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            System.out.println("AT " + thread);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            System.out.println("CTXERR1 " + c);
            c.printStackTrace(System.out);
            return null;
        } catch (Throwable t) {
            System.out.println("CTXERR2 " + t);
            t.printStackTrace(System.out);
            return null;
        }
    }
}
