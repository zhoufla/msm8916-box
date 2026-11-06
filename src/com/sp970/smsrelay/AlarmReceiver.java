package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager 兜底：进程被系统回收后把服务拉回来。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            ctx.startService(new Intent(ctx, RelayService.class));
        } catch (Throwable ignored) {
        }
    }
}
