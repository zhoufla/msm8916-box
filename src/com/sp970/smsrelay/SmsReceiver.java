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
        handle(ctx, intent);
    }

    /**
     * 统一处理两条入站广播：SMS_RECEIVED（framework 的 legacy 广播）与
     * SMS_DELIVER（本 app 为默认短信 App 时点对点投递的腿）。
     * 同一条 PDU 两个 action 都到 → Db.insertEvent 的判重保证只产生一条事件，
     * 投递、镜像也都被对应判重吸收，不会双发。
     */
    static void handle(Context ctx, Intent intent) {
        try {
            String action = intent.getAction();
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)
                    && !Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) return;

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
            String leg = Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)
                    ? "deliver" : "legacy";

            Db db = new Db(ctx);
            long id = db.insertEvent(e);
            AppConfig cfg = AppConfig.load(ctx);
            Router.Result r = Router.route(e, cfg.rules, cfg.names(), cfg.rulesDefault);
            if (r.drop) {
                db.setEventRule(id, r.rule);
                android.util.Log.i("SmsRelay", "recv " + e.peer + " len=" + e.body.length()
                        + " code=" + e.code + " rule=" + r.rule + " action=drop leg=" + leg);
            } else {
                db.ensureTargets(id, r.targets);
                android.util.Log.i("SmsRelay", "recv " + e.peer + " len=" + e.body.length()
                        + " code=" + e.code + " rule=" + r.rule
                        + " targets=" + r.targets.size() + " leg=" + leg);
            }

            ctx.startService(new Intent(ctx, RelayService.class));

            // JZ02 / 无默认短信 App：把这条短信镜像进系统收件箱（provider 空真空器件，
            // auto 模式下 SP970 有 Mms 时这里什么都不做）
            InboxMirror.storeAsync(ctx, e.peer, e.body, e.ts);
        } catch (Throwable t) {
            android.util.Log.e("SmsRelay", "receiver fail", t);
        }
    }
}
