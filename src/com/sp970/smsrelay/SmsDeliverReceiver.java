package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * SMS_DELIVER 腿（4.4 默认短信 App 的入站主通道，见 SmsApplication.isComplete）：
 * 本 app 成为默认短信 App 后，framework 的 InboundSmsHandler 会把点对点的
 * SMS_DELIVER 投给这里（必须有 android:permission=BROADCAST_SMS 的接收器和
 * SENDTO/RESPOND_VIA_MESSAGE/WAP_PUSH_DELIVER 三个伙伴组件一起，才被认作可设默认）。
 * 处理逻辑与 SmsReceiver 完全同一套：Db.insertEvent 的 peer+body+ts 判重天然吸收
 * framework 随后的 SMS_RECEIVED 二次广播（同一 PDU 两次只入一条事件）。
 */
public class SmsDeliverReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SmsReceiver.handle(ctx, intent);
    }
}
