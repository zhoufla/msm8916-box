package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager 兜底：进程被系统回收后把服务拉回来，并顺手探活重建面板。
 *
 * 只 startService 是不够的：服务还在时只走 onStartCommand，端口假在线
 * 永远不会被发现（见 Watchdog 类注释）。探活放到后台线程，别堵主线程。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            ctx.startService(new Intent(ctx, RelayService.class));
        } catch (Throwable ignored) {
        }
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Watchdog.probeAndRepair(app);
            }
        }, "smsrelay-watchdog");
        t.setDaemon(true);
        t.start();
    }
}
