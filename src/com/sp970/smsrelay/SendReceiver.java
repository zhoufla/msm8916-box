package com.sp970.smsrelay;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;

/** 接收短信发送结果，回写 out 表。多片短信等所有分片都有结果再定性。 */
public class SendReceiver extends BroadcastReceiver {

    private static final Map<Long, Integer> LEFT = new HashMap<Long, Integer>();

    static synchronized void expect(long id, int parts) {
        LEFT.put(id, parts);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            long id = intent.getLongExtra(SmsSender.EXTRA_ID, -1);
            if (id <= 0) return;
            int code = getResultCode();
            boolean ok = code == Activity.RESULT_OK;
            int left;
            synchronized (LEFT) {
                Integer n = LEFT.get(id);
                left = n == null ? 1 : n - 1;
                if (left <= 0) LEFT.remove(id);
                else LEFT.put(id, left);
            }

            if (!ok) {
                String err = errText(code);
                new Db(ctx).markOut(id, 2, err);
                Logs.add("sms", "发送失败 id=" + id + " code=" + code + " " + err);
                return;
            }
            if (left <= 0) {
                new Db(ctx).markOut(id, 1, "");
                Logs.add("sms", "发送成功 id=" + id);
            }
        } catch (Throwable t) {
            Logs.add("sms", "发送结果处理异常: " + t);
        }
    }

    private static String errText(int code) {
        switch (code) {
            case 1: return "通用失败";
            case 2: return "射频关闭";
            case 3: return "无效 PDU";
            case 4: return "无服务";
            default: return "错误码 " + code;
        }
    }
}
