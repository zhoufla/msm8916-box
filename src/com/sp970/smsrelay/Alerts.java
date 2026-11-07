package com.sp970.smsrelay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-C 阈值告警推送：状态翻转才推 + 周期性重复提醒 + 恢复通知。
 *
 * 事件源：体检判据（modem SSR / RIL / SIM / 内存 / 面板 / 进程，复用 Maint.checkup）、
 * 流量月配额（Radio.traffic 自上次清零累计）、低电量（dumpsys battery）、
 * 保活重建与恢复（MobileKeepalive 事件）。
 *
 * 告警不走 Db：合成 peer="ALERT" 的 Deliver 直发通道（复用 Sender），不进转发记录；
 * 推送失败只记日志——告警链路绝不影响短信转发主链路。
 * 判定与解析的纯逻辑在 {@link AlertLogic}（桌面可单测）。
 */
public final class Alerts {
    public static final String TAG = "alerts";
    private static final Object LOCK = new Object();
    /** key -> 是否处于异常态（翻转判定）。进程重启后状态清零，重启后首个周期会重新推一次。 */
    private static final Map<String, Boolean> firing = new HashMap<String, Boolean>();
    /** key -> 上次推送时间（remind 间隔用）。 */
    private static final Map<String, Long> lastSent = new HashMap<String, Long>();
    private static volatile long lastTickAt = 0;

    private Alerts() {
    }

    /** RelayService.loop 调：到点（alerts.intervalMin）才起线程跑体检 + 配额 + 电量评估。 */
    public static void tick(final Context app) {
        try {
            AppConfig cfg = AppConfig.load(app);
            if (!cfg.alertEnabled) return;
            long gap = Math.max(1, cfg.alertIntervalMin) * 60L * 1000L;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastTickAt < gap) return;
            lastTickAt = now;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        evaluate(app);
                    } catch (Throwable ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.setName("alerts");
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** 事件型告警入口（保活重建/恢复等即时事件）：bad=true 进入异常态并推送，false 恢复。 */
    public static void event(Context app, AppConfig cfg, String key, boolean bad, String text) {
        try {
            if (cfg == null || !cfg.alertEnabled) return;
            if ("keepalive".equals(key) && !cfg.alertKeepalive) return;
            eval(app, cfg, key, bad, text);
        } catch (Throwable ignored) {
        }
    }

    private static void evaluate(Context app) {
        AppConfig cfg = AppConfig.load(app);

        // 1) 体检判据：一次 checkup 覆盖 modem SSR / RIL / SIM / 内存 / 面板 / 进程
        JSONObject c = Maint.checkup(app);
        boolean healthy = c.optBoolean("healthy");
        JSONArray issues = c.optJSONArray("issues");
        String badText = "体检异常：" + (!healthy
                ? (issues == null ? c.optString("message", "未知") : AlertLogic.joinIssues(issues))
                : "");
        eval(app, cfg, "health", !healthy, healthy ? "体检恢复正常，全部判据通过" : badText);

        // 2) 流量月配额（trafficGb=0 关闭；自上次「清零」的累计收+发）
        if (cfg.alertTrafficGb > 0) {
            JSONObject t = Radio.traffic(app);
            double used = (t.optLong("sinceRx", 0) + t.optLong("sinceTx", 0)) / 1e9;
            boolean over = t.optBoolean("ok") && used >= cfg.alertTrafficGb;
            eval(app, cfg, "traffic", over,
                    over ? String.format("流量已用 %.2f GB，超过 %.0f GB 配额", used, (double) cfg.alertTrafficGb)
                         : String.format("流量已回落到 %.2f GB（配额 %.0f GB）", used, (double) cfg.alertTrafficGb));
        }

        // 3) 低电量（batteryPct=0 关闭；仅未在充电时才告警，插电的棒子常态 true）
        if (cfg.alertBatteryPct > 0) {
            String s = Root.exec(app, "dumpsys battery 2>/dev/null").out;
            int level = AlertLogic.parseBatteryLevel(s);
            if (level >= 0) {
                boolean charging = AlertLogic.parseBatteryPowered(s);
                boolean low = !charging && level < cfg.alertBatteryPct;
                eval(app, cfg, "battery", low,
                        low ? "电量仅 " + level + "%（阈值 " + cfg.alertBatteryPct + "%）"
                            : "电量已回升到 " + level + "%");
            }
        }
    }

    private static void eval(Context app, AppConfig cfg, String key, boolean bad, String text) {
        boolean was;
        long sent;
        synchronized (LOCK) {
            Boolean f = firing.get(key);
            was = f != null && f.booleanValue();
            Long s = lastSent.get(key);
            sent = s == null ? 0L : s.longValue();
        }
        long now = System.currentTimeMillis();
        long remindMs = Math.max(1, cfg.alertRemindH) * 3600L * 1000L;
        String act = AlertLogic.decide(was, sent, now, bad, remindMs);
        if ("fire".equals(act) || "remind".equals(act)) {
            synchronized (LOCK) {
                firing.put(key, Boolean.TRUE);
                lastSent.put(key, Long.valueOf(now));
            }
            push(app, cfg, "remind".equals(act) ? "【提醒】仍处于异常：" + text : text);
        } else if ("recover".equals(act)) {
            synchronized (LOCK) {
                firing.put(key, Boolean.FALSE);
                lastSent.put(key, Long.valueOf(now));
            }
            push(app, cfg, text);
        }
    }

    /** 合成 peer="ALERT" 的 Deliver 直发（不走 Db，失败只记日志）。 */
    private static void push(Context app, AppConfig cfg, String text) {
        try {
            List<Channel> list = new ArrayList<Channel>();
            if (cfg.alertChannels.isEmpty()) {
                for (int i = 0; i < cfg.channels.size(); i++) {
                    Channel ch = cfg.channels.get(i);
                    if (ch.valid()) list.add(ch);
                }
            } else {
                for (int i = 0; i < cfg.alertChannels.size(); i++) {
                    Channel ch = cfg.find(cfg.alertChannels.get(i));
                    if (ch != null && ch.valid()) list.add(ch);
                }
            }
            if (list.isEmpty()) {
                Logs.add(TAG, "告警无可用通道，未推送：" + AlertLogic.firstLine(text));
                return;
            }
            Deliver d = new Deliver();
            d.peer = "ALERT";
            d.body = text;
            d.ts = System.currentTimeMillis();
            int okN = 0;
            StringBuilder errs = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                Channel ch = list.get(i);
                d.ch = ch.name;
                String err = Sender.send(ch, d, cfg.device);
                if (err == null) okN++;
                else errs.append(ch.name).append(": ").append(err).append("；");
            }
            Logs.add(TAG, "告警推送 " + okN + "/" + list.size() + "：" + AlertLogic.firstLine(text)
                    + (errs.length() > 0 ? " 失败[" + errs + "]" : ""));
        } catch (Throwable t) {
            Logs.add(TAG, "告警推送异常：" + t);
        }
    }
}
