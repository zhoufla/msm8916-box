package com.sp970.smsrelay;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * 常驻服务：短信转发 + 内嵌 Web 面板。
 *
 * 保活手段（Android 4.4 没有 Doze，也不需要 JobScheduler）：
 *  1. startForeground 常驻通知，避开 LMK 的普通进程优先级
 *  2. START_STICKY，被杀后系统重建
 *  3. AlarmManager 每 5 分钟兜底拉起
 *  4. 发送时持 PARTIAL_WAKE_LOCK，熄屏也能把请求发出去
 */
public class RelayService extends Service {

    public static final String ACTION_TICK = "com.sp970.smsrelay.TICK";

    private static final int NOTI_ID = 1001;
    private static final long WATCHDOG_MS = 5L * 60 * 1000;

    private final Object lock = new Object();
    private volatile boolean running = true;
    private Thread worker;
    private Db db;
    private PowerManager.WakeLock wakelock;

    /** 面板上的操作（重发/补收/改配置）后立刻踢一轮投递，不必等轮询间隔。 */
    public static void wake(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, RelayService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = new Db(this);
        db.purgeOrphans();          // 还原数据库/事务中断留下的孤儿投递先清掉
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsrelay:send");
        wakelock.setReferenceCounted(false);
        startForeground(NOTI_ID, buildNotification("等待短信"));
        Logs.add("svc", "服务启动");
        SysInfo.listen(this);
        WebPanel.start(this);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "smsrelay-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleWatchdog();
        synchronized (lock) {
            lock.notifyAll();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        WebPanel.stop();
        SysInfo.unlisten(this);
        Logs.add("svc", "服务销毁");
        synchronized (lock) {
            lock.notifyAll();
        }
        super.onDestroy();
    }

    private void loop() {
        int idle = 0;
        while (running) {
            int sent = 0;
            try {
                AppConfig cfg = AppConfig.load(this);
                // SIM 锁（B2 #16）：卡不在白名单且 action=block 时停止转发，消息仍然入库
                boolean simBlocked = Radio.enforceSimLock(this);
                // CPU 限频维持：thermal/厂商脚本改回去时拉回（未启用时只是读一个小文件）
                CpuFreq.enforce(this);
                List<Deliver> list = db.due(10);
                if (!list.isEmpty() && cfg.activeCount() > 0 && !simBlocked) {
                    wakelock.acquire(60000);
                    try {
                        for (int i = 0; i < list.size(); i++) {
                            Deliver d = list.get(i);
                            Channel ch = cfg.find(d.ch);
                            if (ch == null) {
                                // 通道被删了就再也发不出去，直接判放弃（固定 tries=9 会一直 pending）
                                db.markFail(d.dlId, 10, "通道已删除: " + d.ch, 10);
                                Logs.add("relay", "放弃 " + d.ch + "：通道已删除");
                                continue;
                            }
                            if (!ch.valid()) {
                                // 只是被禁用/URL 没填，保留重试，等用户改好配置还能补发
                                db.markFail(d.dlId, d.tries, "通道已禁用或 URL 无效: " + d.ch, 10);
                                continue;
                            }
                            String err = Sender.send(ch, d, cfg.device);
                            if (err == null) {
                                db.markOk(d.dlId);
                                Logs.add("relay", "送达 " + ch.name + " ← " + d.peer);
                                sent++;
                            } else {
                                db.markFail(d.dlId, d.tries, err, ch.maxTries);
                                Logs.add("relay", "失败 " + ch.name + " 第 " + (d.tries + 1)
                                        + "/" + ch.maxTries + " 次：" + err);
                            }
                        }
                    } finally {
                        if (wakelock.isHeld()) wakelock.release();
                    }
                }
                if (sent > 0) db.prune();
                Update.tick(this);          // 到点才真的去查，内部自己起线程
                Maint.tick(this);           // 定时体检落盘，异常进日志，内部自己起线程
            } catch (Throwable t) {
                android.util.Log.e("SmsRelay", "loop", t);
            }

            updateNotification();
            idle = sent > 0 ? 0 : idle + 1;
            long wait = sent > 0 ? 2000 : (idle > 8 ? 30000 : 5000);
            synchronized (lock) {
                try {
                    lock.wait(wait);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void updateNotification() {
        int pending = 0;
        int active = 0;
        try {
            pending = db.pending();
            active = AppConfig.load(this).activeCount();
        } catch (Throwable ignored) {
        }
        String text = (pending > 0 ? ("待发送 " + pending) : "已就绪")
                + " · 通道 " + active
                + (WebPanel.isRunning() ? " · 面板 " + WebPanel.runningPort() : "");
        try {
            startForeground(NOTI_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("短信转发")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.getNotification();
    }

    private void scheduleWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, AlarmReceiver.class);
            i.setAction(ACTION_TICK);
            PendingIntent pi = PendingIntent.getBroadcast(this, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_MS, WATCHDOG_MS, pi);
        } catch (Throwable ignored) {
        }
    }
}
