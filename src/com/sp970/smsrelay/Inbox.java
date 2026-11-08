package com.sp970.smsrelay;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * 补收：把系统收件箱里还没转发过的短信补进队列。
 * 用途：服务被杀 / 广播漏收之后，一条不漏地补投。
 */
public final class Inbox {

    /** @return 新补进队列的条数 */
    public static int importAll(Context ctx) {
        int added = 0;
        Cursor c = null;
        try {
            AppConfig cfg = AppConfig.load(ctx);
            Db db = Db.get(ctx);
            int before = db.events();
            c = ctx.getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    SmsEvent e = new SmsEvent();
                    e.peer = c.getString(0);
                    e.body = c.getString(1) == null ? "" : c.getString(1);
                    long d = c.getLong(2);
                    e.ts = d > 0 ? d : System.currentTimeMillis();
                    e.code = SmsEvent.extractCode(e.body);
                    long id = db.insertEvent(e);
                    if (id > 0) {
                        Router.Result r = Router.route(e, cfg.rules, cfg.names(), cfg.rulesDefault);
                        if (r.drop) db.setEventRule(id, r.rule);
                        else db.ensureTargets(id, r.targets);
                    }
                }
            }
            added = db.events() - before;
        } catch (Throwable t) {
            Logs.add("inbox", "补收失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        Logs.add("inbox", "补收完成，新增 " + added + " 条");
        return added;
    }
}
