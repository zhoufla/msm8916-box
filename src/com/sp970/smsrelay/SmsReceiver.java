package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** 收短信 → 落库 → 给每个通道建投递记录 → 拉起服务。 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;

            StringBuilder body = new StringBuilder();
            String peer = "";
            long ts = 0;

            for (int i = 0; i < parts.length; i++) {
                SmsMessage m = parts[i];
                if (m == null) continue;
                String b = m.getMessageBody();
                if (b != null) body.append(b);
                if (peer.length() == 0) {
                    String a = m.getOriginatingAddress();
                    if (a != null) peer = a;
                }
                if (ts == 0) ts = m.getTimestampMillis();
            }

            SmsEvent e = new SmsEvent();
            e.peer = peer;
            e.body = body.toString();
            e.ts = ts > 0 ? ts : System.currentTimeMillis();
            e.code = SmsEvent.extractCode(e.body);

            Db db = new Db(ctx);
            long id = db.insertEvent(e);
            AppConfig cfg = AppConfig.load(ctx);
            Router.Result r = Router.route(e, cfg.rules, cfg.names(), cfg.rulesDefault);
            if (r.drop) {
                db.setEventRule(id, r.rule);
                android.util.Log.i("SmsRelay", "recv " + e.peer + " len=" + e.body.length()
                        + " code=" + e.code + " rule=" + r.rule + " action=drop");
            } else {
                db.ensureTargets(id, r.targets);
                android.util.Log.i("SmsRelay", "recv " + e.peer + " len=" + e.body.length()
                        + " code=" + e.code + " rule=" + r.rule
                        + " targets=" + r.targets.size());
            }

            ctx.startService(new Intent(ctx, RelayService.class));
        } catch (Throwable t) {
            android.util.Log.e("SmsRelay", "receiver fail", t);
        }
    }
}
