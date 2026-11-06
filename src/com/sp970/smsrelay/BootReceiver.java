package com.sp970.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String a = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
                AppConfig.writeSample();
                ctx.startService(new Intent(ctx, RelayService.class));
            }
        } catch (Throwable ignored) {
        }
    }
}
