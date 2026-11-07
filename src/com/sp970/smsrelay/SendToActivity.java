package com.sp970.smsrelay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * SENDTO（sms: / smsto: / mms: / mmsto:）处理 Activity（校验四件套之一）。
 * UFI 盒子没有短信写作场景，这里只记录并立即退出——半透明主题，
 * 用户/调用方几乎无感。真正需要“短信写作”时可以在面板维护区加页面。
 */
public class SendToActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri u = getIntent().getData();
        android.util.Log.i("SmsRelay", "sendto ignored: " + u);
        finish();
    }
}
