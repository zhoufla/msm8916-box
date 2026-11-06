package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 调试用：am broadcast -a com.sp970.smsrelay.TEST 注入一条测试消息，
 * 走完整入库→投递链路，无需屏幕。
 */
public class TestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String sendto = intent.getStringExtra("sendto");
            if (sendto != null && sendto.length() > 0) {
                String text = intent.getStringExtra("body");
                if (text == null || text.length() == 0) text = "1";
                android.telephony.SmsManager sm = android.telephony.SmsManager.getDefault();
                sm.sendTextMessage(sendto, null, text, null, null);
                Log.i("SmsRelay", "test sms sent to " + sendto + ": " + text);
                return;
            }
            if (intent.getBooleanExtra("importinbox", false)) {
                importInbox(ctx);
                return;
            }
            SmsEvent e = new SmsEvent();
            e.ts = System.currentTimeMillis();
            e.peer = intent.getStringExtra("peer");
            if (e.peer == null || e.peer.length() == 0) e.peer = "10086";
            e.body = intent.getStringExtra("body");
            if (e.body == null || e.body.length() == 0) e.body = "测试消息 " + e.ts;
            e.code = "";
            long evId = new Db(ctx).insertEvent(e);
            AppConfig cfg = AppConfig.load(ctx);
            new Db(ctx).ensureDeliveries(evId, cfg.names());
            Log.i("SmsRelay", "test event injected: " + e.body + " evId=" + evId
                    + " channels=" + cfg.names());
            ctx.startService(new Intent(ctx, RelayService.class));
        } catch (Throwable t) {
            Log.e("SmsRelay", "test inject failed", t);
        }
    }

    /** 把系统收件箱里还没转发过的短信补进队列（服务漏广播时兜底）。 */
    private void importInbox(Context ctx) {
        int added = 0;
        try {
            AppConfig cfg = AppConfig.load(ctx);
            Db db = new Db(ctx);
            android.database.Cursor c = ctx.getContentResolver().query(
                    android.net.Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    SmsEvent e = new SmsEvent();
                    e.peer = c.getString(0);
                    e.body = c.getString(1);
                    long d = c.getLong(2);
                    e.ts = d > 0 ? d : System.currentTimeMillis();
                    e.code = SmsEvent.extractCode(e.body == null ? "" : e.body);
                    long id = db.insertEvent(e);
                    if (id > 0) {
                        db.ensureDeliveries(id, cfg.names());
                        added++;
                        Log.i("SmsRelay", "inbox imported: " + e.peer + " evId=" + id);
                    }
                }
                c.close();
            }
        } catch (Throwable t) {
            Log.e("SmsRelay", "importInbox", t);
        }
        Log.i("SmsRelay", "importInbox done, added=" + added);
        ctx.startService(new Intent(ctx, RelayService.class));
    }
}
