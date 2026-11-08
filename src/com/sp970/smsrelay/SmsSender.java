package com.sp970.smsrelay;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;

import java.util.ArrayList;

/** 面板发短信：分片发送 + 逐片结果回写 out 表。 */
public final class SmsSender {

    public static final String EXTRA_ID = "out_id";
    public static final String ACTION_SENT = "com.sp970.smsrelay.SENT";
    private static final int MAX_LEN = 1000;

    public static long send(Context ctx, String to, String body) throws Exception {
        String num = to == null ? "" : to.trim();
        String text = body == null ? "" : body;
        if (num.length() == 0) throw new Exception("号码不能为空");
        if (text.length() == 0) throw new Exception("内容不能为空");
        if (text.length() > MAX_LEN) throw new Exception("内容过长（上限 " + MAX_LEN + " 字）");

        SmsManager sm = SmsManager.getDefault();
        if (sm == null) throw new Exception("SmsManager 不可用");

        ArrayList<String> parts = sm.divideMessage(text);
        if (parts == null || parts.isEmpty()) {
            parts = new ArrayList<String>();
            parts.add(text);
        }

        Db db = Db.get(ctx);
        long id = db.insertOut(num, text, parts.size());
        if (id <= 0) throw new Exception("写入发件记录失败");

        SendReceiver.expect(id, parts.size());

        if (parts.size() == 1) {
            sm.sendTextMessage(num, null, parts.get(0), sentIntent(ctx, id, 0, 1), null);
        } else {
            ArrayList<PendingIntent> sent = new ArrayList<PendingIntent>();
            for (int i = 0; i < parts.size(); i++) {
                sent.add(sentIntent(ctx, id, i, parts.size()));
            }
            sm.sendMultipartTextMessage(num, null, parts, sent, null);
        }
        Logs.add("sms", "发送 → " + num + "（" + parts.size() + " 片）id=" + id);
        return id;
    }

    private static PendingIntent sentIntent(Context ctx, long id, int part, int parts) {
        Intent it = new Intent(ctx, SendReceiver.class);
        it.setAction(ACTION_SENT);
        it.putExtra(EXTRA_ID, id);
        it.putExtra("part", part);
        it.putExtra("parts", parts);
        return PendingIntent.getBroadcast(ctx, (int) (id * 100 + part), it,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
