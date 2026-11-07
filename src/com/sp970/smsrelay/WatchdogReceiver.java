package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 给 relay-helper 的 root 看门狗用（exported=true，只认自定义 action）。
 *
 * 面板卡死时 App 内的 AlarmManager 可能也投递不出来（2026-11-07 事故里
 * 15 分钟没拉起），需要外部独立进程能唤醒 App。这里只做
 * 「拉起服务 + 探活重建」，不接收任何数据、不暴露配置或短信内容。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Watchdog.ACTION_TICK.equals(intent.getAction())) return;
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
