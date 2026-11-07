package com.sp970.smsrelay;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * RESPOND_VIA_MESSAGE 服务（校验四件套之一）。
 * 手机上这个服务由拨号/会话界面在“快捷回复”时调用（startService）。本 app 无
 * 会话 UI，正确行为是立刻自查返回 START_NOT_STICKY，把结果交回调用方。
 */
public class RespondViaMessageService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.util.Log.i("SmsRelay", "respond-via-message ignored: "
                + (intent == null ? null : intent.getDataString()));
        stopSelf(startId);
        return Service.START_NOT_STICKY;
    }
}
