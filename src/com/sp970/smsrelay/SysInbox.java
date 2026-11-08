package com.sp970.smsrelay;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 系统短信收件箱的只读视图，给面板「短信 → 系统收件箱」子页签。
 *
 * Android 4.4 起写/删短信 provider 需要默认短信 App 身份，本 app 不抢占默认短信 App，
 * 所以这里只做「看」和「单条转发进队列」，不做删除；全量补收仍走 Inbox.importAll()。
 *
 * 分页直接在 sortOrder 里拼 LIMIT/OFFSET（4.4 的 SmsProvider 支持）；
 * 总数走一次只取 date 列的轻查询全扫计数（收件箱量级很小，代价可忽略），
 * 有的 ROM 不配合投影时回 -1，面板降级为只有「下一页」的模式。
 */
public final class SysInbox {

    private static final Uri URI = Uri.parse("content://sms/inbox");

    private SysInbox() {
    }

    /**
     * 一页收件箱 + 每条的「已转发」标记（判定口径与入库判重同源：号码+内容+90s 窗口）。
     *
     * @return {total, messages:[{peer,body,ts,iso,relayed}]}，读不到 provider 时返回 null
     */
    public static JSONObject page(Context ctx, int page, int size) {
        JSONArray arr = new JSONArray();
        int total = -1;
        try {
            Cursor t = null;
            try {
                t = ctx.getContentResolver().query(URI, new String[]{"date"}, null, null, null);
                if (t != null) total = t.getCount();
            } catch (Throwable ignored) {
                total = -1;
            } finally {
                if (t != null) t.close();
            }

            Db db = Db.get(ctx);
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(URI,
                        new String[]{"address", "body", "date"}, null, null,
                        "date DESC LIMIT " + size + " OFFSET " + ((page - 1) * size));
                if (c == null) return null;
                while (c.moveToNext()) {
                    JSONObject m = new JSONObject();
                    String peer = c.getString(0);
                    String body = c.getString(1);
                    long ts = c.getLong(2);
                    m.put("peer", peer == null ? "" : peer);
                    m.put("body", body == null ? "" : body);
                    m.put("ts", ts);
                    m.put("iso", ts > 0 ? Db.iso(ts) : "-");
                    m.put("relayed", db.nearDuplicateId(peer, body, ts) > 0);
                    arr.put(m);
                }
            } finally {
                if (c != null) c.close();
            }
        } catch (Throwable t) {
            Logs.add("sysinbox", "读取失败: " + t);
            return null;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("total", total);
            o.put("messages", arr);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 单条转发：把系统收件箱里的一条短信送进转发队列。
     * 走与广播链路同一个入队口（insertEvent 的 90s 窗口判重），已转发过的不会重复投递。
     */
    public static JSONObject forwardOne(Context ctx, String peer, String body, long ts) {
        JSONObject o = new JSONObject();
        try {
            Db db = Db.get(ctx);
            SmsEvent e = new SmsEvent();
            e.peer = peer == null ? "" : peer;
            e.body = body == null ? "" : body;
            e.ts = ts > 0 ? ts : System.currentTimeMillis();
            e.code = SmsEvent.extractCode(e.body);
            int before = db.events();
            long id = db.insertEvent(e);
            if (id <= 0) {
                o.put("ok", false);
                o.put("message", "入队失败，看面板「维护 → 日志」的 sysinbox 段");
                return o;
            }
            db.ensureTargets(id, Router.routeAll(AppConfig.load(ctx).names()).targets);
            RelayService.wake(ctx);
            boolean dup = db.events() == before;    // 计数没涨 = 命中已有事件
            o.put("ok", true);
            o.put("id", id);
            o.put("duplicate", dup);
            o.put("message", dup
                    ? "已在转发队列（判重命中 #" + id + "），未重复投递"
                    : "已入队 #" + id + "，按当前通道投递");
            Logs.add("sysinbox", (dup ? "单条转发判重命中: " : "单条转发入队: ") + peer);
            return o;
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("message", "转发失败: " + t);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }
}
