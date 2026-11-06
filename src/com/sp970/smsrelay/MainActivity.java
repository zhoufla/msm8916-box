package com.sp970.smsrelay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 配置界面：直接编辑多通道 JSON。
 * 设备通常无屏，日常靠 /sdcard/smsrelay.conf 部署，这里用于现场排错。
 */
public class MainActivity extends Activity {

    private EditText etJson;
    private TextView tvStatus;

    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setText("通道配置（JSON）：type = dingtalk | webhook\n" +
                "device 可改；channels 里每项独立投递、独立重试");
        root.addView(tip);

        etJson = new EditText(this);
        etJson.setTextSize(11);
        etJson.setTypeface(Typeface.MONOSPACE);
        etJson.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        etJson.setMinLines(10);
        etJson.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(etJson);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("保存并启动", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndStart();
            }
        }));
        bar.addView(button("停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, RelayService.class));
                toast("已停止");
            }
        }));
        root.addView(bar);

        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.addView(button("插入测试消息", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertTest();
            }
        }));
        bar2.addView(button("重载配置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etJson.setText(AppConfig.effectiveJson(MainActivity.this));
                updateStatus();
            }
        }));
        root.addView(bar2);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, p, 0, 0);
        root.addView(tvStatus);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        etJson.setText(AppConfig.effectiveJson(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void saveAndStart() {
        String json = etJson.getText().toString().trim();
        try {
            new JSONObject(json);
        } catch (Throwable t) {
            toast("JSON 解析失败：" + t.getMessage());
            return;
        }
        AppConfig.saveJson(this, json);
        AppConfig cfg = AppConfig.load(this);
        if (cfg.activeCount() == 0) {
            toast("没有启用的通道（url 需 http 开头、enabled=true）");
            return;
        }
        startService(new Intent(this, RelayService.class));
        toast("已保存，" + cfg.activeCount() + " 个通道启用");
        updateStatus();
    }

    private void insertTest() {
        SmsEvent e = new SmsEvent();
        e.peer = "10086";
        e.body = "测试消息 " + System.currentTimeMillis();
        e.ts = System.currentTimeMillis();
        e.code = "";

        Db db = new Db(this);
        long id = db.insertEvent(e);
        db.ensureDeliveries(id, AppConfig.load(this).names());
        startService(new Intent(this, RelayService.class));
        toast("已插入测试消息");
        updateStatus();
    }

    private void updateStatus() {
        Db db = new Db(this);
        AppConfig cfg = AppConfig.load(this);
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(android.os.Build.MODEL).append('\n');
        sb.append("通道：").append(cfg.activeCount()).append(" / ").append(cfg.channels.size())
                .append(" 启用\n");
        sb.append("待发送：").append(db.pending()).append(" 条投递\n");
        sb.append("已送达：").append(db.sent()).append(" 条投递\n");
        String err = db.lastError();
        if (err != null && err.length() > 0) sb.append("最近错误：").append(err).append('\n');
        sb.append("配置文件 /sdcard/").append(AppConfig.CONF_NAME).append(" 存在时优先");
        tvStatus.setText(sb.toString());
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
