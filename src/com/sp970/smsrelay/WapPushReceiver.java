package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * WAP_PUSH_DELIVER 接收器（默认短信 App 校验四件套之一）。
 * 本 app 只做 SMS，不做 MMS：收到的 WAP push（彩信通知 sp/push）照旧不处理——
 * 这里仅满足 SmsApplication.isComplete() 的组件存在性要求，通知类彩信在
 * UFI 盒子上无意义。不处理意味着 push 不会写进 provider，也不会误触发转发。
 */
public class WapPushReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        byte[] data = intent.getByteArrayExtra("data");
        android.util.Log.i("SmsRelay", "wap push ignored, len="
                + (data == null ? -1 : data.length));
    }
}
