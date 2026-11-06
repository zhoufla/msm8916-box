package com.sp970.smsrelay;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 维护功能聚合层（B0 + B1）。
 *
 * 这里几乎不写业务逻辑，只负责「拼一条 root 命令 → 解析输出」：
 *   #7 电源  #8 日志与打包  #9 一键体检  #10 健康自检
 *   #11 备份恢复  #12 文件浏览下载  #21 定时任务(crontab)  #22 LED 定位
 * 特权动作全部走 Root.exec，app 仍是普通 uid 10075，Manifest 不用加权限。
 *
 * 设备时基不可靠：所有落盘文件名用 /proc/uptime 的秒数，不用日期。
 */
public final class Maint {

    private static final String BB = "/system/xbin/busybox";
    private static final String TMP = "/data/local/tmp/relay-helper";
    private static final String DL = "/sdcard/Download";
    private static final String HELPER = "/system/etc/relay-helper";

    private static volatile long lastHealthAt = 0;

    private Maint() {
    }

    // ---------- #6 通用命令框 ----------

    public static JSONObject cmd(Context ctx, String command) {
        JSONObject o = new JSONObject();
        try {
            if (command == null || command.trim().length() == 0) {
                throw new IllegalArgumentException("命令不能为空");
            }
            if (command.length() > 60000) throw new IllegalArgumentException("命令太长（>60KB）");
            Root.Result r = Root.exec(ctx, command);
            o.put("ok", r.ok());
            o.put("exit", r.exit);
            o.put("out", r.out);
            if (r.error.length() > 0) o.put("error", r.error);
            o.put("message", r.error.length() > 0 ? r.error : "执行完毕，exit=" + r.exit);
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #9 一键体检 / #10 健康自检 ----------

    /**
     * 与主机侧 diag/boot-window-check.py 用同一套判据：
     * boot_id / uptime / subsys state,crash_count / RIL 进程 / SIM / 8080 / smsrelay / MemFree。
     * 刻意不用管道（设备端管道在开机窗口里会产生 SIGPIPE tombstone 噪声）。
     */
    private static final String CHECKUP =
            "echo '##BOOT_ID'; cat /proc/sys/kernel/random/boot_id; " +
            "echo '##UPTIME'; cat /proc/uptime; " +
            "echo '##STATE'; for i in 0 1 2; do cat /sys/bus/msm_subsys/devices/subsys$i/state 2>/dev/null; done; " +
            "echo '##CRASH'; for i in 0 1 2; do cat /sys/bus/msm_subsys/devices/subsys$i/crash_count 2>/dev/null; done; " +
            "echo '##SIM'; getprop gsm.sim.state; " +
            "echo '##NETTYPE'; getprop gsm.network.type; " +
            "echo '##OPERATOR'; getprop gsm.operator.alpha; " +
            "echo '##MEMINFO'; cat /proc/meminfo; " +
            "echo '##TRAFFIC'; cat /proc/net/dev; " +
            "echo '##PS'; ps; " +
            "echo '##NETSTAT'; netstat -ltn; " +
            "echo '##BUILD'; getprop ro.build.version.release; getprop ro.product.model";

    public static JSONObject checkup(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx, CHECKUP);
            if (!r.ok()) throw new IllegalStateException("helper 执行失败：" + brief(r));
            Map<String, List<String>> sec = sections(r.out);

            String bootId = first(sec.get("BOOT_ID"));
            double uptime = 0;
            String up = first(sec.get("UPTIME"));
            if (up.length() > 0) {
                try {
                    uptime = Double.parseDouble(up.trim().split("\\s+")[0]);
                } catch (Throwable ignored) {
                }
            }
            List<String> states = nonEmpty(sec.get("STATE"));
            List<Integer> crashes = new ArrayList<Integer>();
            List<String> crashLines = nonEmpty(sec.get("CRASH"));
            for (String s : crashLines) {
                try {
                    crashes.add(Integer.valueOf(s.trim()));
                } catch (Throwable ignored) {
                }
            }
            String sim = first(sec.get("SIM"));
            String netType = first(sec.get("NETTYPE"));
            String operator = first(sec.get("OPERATOR"));
            String ps = join(sec.get("PS"));
            String netstat = join(sec.get("NETSTAT"));
            int memFree = 0;
            for (String line : sec.get("MEMINFO") == null ? new ArrayList<String>() : sec.get("MEMINFO")) {
                if (line.startsWith("MemFree:")) {
                    memFree = intIn(line);
                    break;
                }
            }
            long rx = 0, tx = 0;
            for (String line : sec.get("TRAFFIC") == null ? new ArrayList<String>() : sec.get("TRAFFIC")) {
                if (!line.contains(":")) continue;
                String[] f = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
                if (f.length >= 9) {
                    try {
                        rx += Long.parseLong(f[0]);
                        tx += Long.parseLong(f[8]);
                    } catch (Throwable ignored) {
                    }
                }
            }

            JSONObject checks = new JSONObject();
            boolean helper = true;
            boolean panel = netstat.contains(":8080 ");
            boolean relay = ps.contains("com.sp970.smsrelay");
            boolean ril = ps.contains("rild") || ps.contains("qmuxd") || ps.contains("netmgrd");
            boolean simReady = sim.toUpperCase().contains("READY");
            boolean ssrZero = !crashes.isEmpty() && allZero(crashes);
            // 与 diag/boot-window-check.py 保持一致：只判 subsys2/modem，
            // subsys0 在这颗 msm8916 上常态 OFFLINE（adsp 未加载），不算异常。
            boolean modemOnline = states.size() >= 3
                    && "ONLINE".equalsIgnoreCase(states.get(2));
            boolean memOk = memFree > 20000;
            boolean adbd = netstat.contains(":5555 ");

            checks.put("helper", helper);
            checks.put("panel8080", panel);
            checks.put("smsrelay", relay);
            checks.put("ril", ril);
            checks.put("simReady", simReady);
            checks.put("ssrZero", ssrZero);
            checks.put("modemOnline", modemOnline);
            checks.put("memOk", memOk);
            checks.put("adbd5555", adbd);

            JSONArray issues = new JSONArray();
            if (!panel) issues.put("8080 面板未监听");
            if (!relay) issues.put("smsrelay 进程不在");
            if (!ril) issues.put("RIL 相关进程缺失");
            if (!simReady) issues.put("SIM 不是 READY（" + sim + "）");
            if (!ssrZero) issues.put("SSR crash_count 非全 0：" + crashLines);
            if (!modemOnline) issues.put("subsys2/modem 非 ONLINE：" + states);
            if (!memOk) issues.put("MemFree 偏低：" + memFree + " kB");
            if (!adbd) issues.put("5555 网络 adb 未监听");

            JSONArray st = new JSONArray();
            for (String s : states) st.put(s);
            JSONArray cr = new JSONArray();
            for (Integer i : crashes) cr.put(i);

            o.put("ok", true);
            o.put("healthy", issues.length() == 0);
            o.put("checks", checks);
            o.put("issues", issues);
            o.put("bootId", bootId);
            o.put("uptimeSec", uptime);
            o.put("states", st);
            o.put("crashes", cr);
            o.put("sim", sim);
            o.put("netType", netType);
            o.put("operator", operator);
            o.put("memFreeKb", memFree);
            o.put("trafficRx", rx);
            o.put("trafficTx", tx);
            o.put("psLines", countLines(ps));
            o.put("netstat", netstat);
            o.put("message", issues.length() == 0 ? "全部判据通过" : issues.length() + " 项需要关注");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "healthy", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /** /api/health：轻量探针，只回判据与结论，不回 ps/netstat 原文。 */
    public static JSONObject health(Context ctx) {
        JSONObject c = checkup(ctx);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", c.optBoolean("ok"));
            o.put("healthy", c.optBoolean("healthy"));
            o.put("checks", c.optJSONObject("checks"));
            o.put("issues", c.optJSONArray("issues"));
            o.put("uptimeSec", c.optDouble("uptimeSec"));
            o.put("bootId", c.optString("bootId", ""));
            o.put("message", c.optString("message", ""));
            o.put("elapsedRealtime", SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 服务循环里顺手调用：定时把体检结论落盘，异常进日志，绝不阻塞投递。 */
    public static void tick(final Context ctx) {
        try {
            final AppConfig cfg = AppConfig.load(ctx);
            if (!cfg.maintHealthEnabled) return;
            long gap = Math.max(1, cfg.maintHealthIntervalMin) * 60L * 1000L;
            long now = SystemClock.elapsedRealtime();
            if (now - lastHealthAt < gap) return;
            lastHealthAt = now;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject c = checkup(ctx);
                        JSONArray issues = c.optJSONArray("issues");
                        String line = "[uptime=" + (long) (c.optDouble("uptimeSec"))
                                + "s host=" + (System.currentTimeMillis() / 1000L)
                                + "] healthy=" + c.optBoolean("healthy")
                                + " issues=" + (issues == null ? "[]" : issues.toString());
                        File f = new File(Environment.getExternalStorageDirectory(),
                                "Download/smsrelay-health.log");
                        FileWriter w = new FileWriter(f, true);
                        w.write(line + "\n");
                        w.close();
                        if (!c.optBoolean("healthy")) {
                            Logs.add("maint", "体检异常：" + (issues == null ? "?" : issues.toString()));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    // ---------- #8 日志查看与打包 ----------

    public static JSONObject logs(Context ctx, String kind, String filter, int lines) {
        JSONObject o = new JSONObject();
        try {
            if (lines < 20) lines = 20;
            if (lines > 1000) lines = 1000;
            String base;
            if ("kernel".equals(kind)) base = "dmesg 2>/dev/null";
            else if ("radio".equals(kind)) base = "logcat -d -v time -b radio -t 800 2>/dev/null";
            else if ("relay".equals(kind)) base = "logcat -d -v time -t 1500 2>/dev/null";
            else if ("all".equals(kind)) base = "logcat -d -v time -b main,radio,system,events -t 1500 2>/dev/null";
            else {
                kind = "main";
                base = "logcat -d -v time -b main,system,events -t 800 2>/dev/null";
            }
            String command = base;
            String safe = safeFilter(filter);
            if (safe.length() > 0) command += " | " + BB + " grep -i -F '" + safe + "'";
            command += " | " + BB + " tail -n " + lines;
            Root.Result r = Root.exec(ctx, command);
            o.put("ok", r.ok());
            o.put("kind", kind);
            o.put("filter", safe);
            o.put("exit", r.exit);
            o.put("text", r.out);
            o.put("message", r.ok() ? "读取完成" : brief(r));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    public static JSONObject bundleLogs(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String command =
                    "B=" + TMP + "/logbundle; mkdir -p $B; " +
                    "logcat -d -v time -b main,radio,system,events -t 2000 > $B/logcat.txt 2>&1; " +
                    "dmesg > $B/dmesg.txt 2>&1; " +
                    "getprop > $B/props.txt 2>&1; " +
                    "ps > $B/ps.txt 2>&1; " +
                    "netstat -ltn > $B/netstat.txt 2>&1; " +
                    "cat /proc/uptime > $B/uptime.txt 2>&1; " +
                    "cat /proc/meminfo > $B/meminfo.txt 2>&1; " +
                    "U=$(cat /proc/uptime | " + BB + " cut -d'.' -f1); " +
                    "T=" + DL + "/smsrelay-logs-$U.tar.gz; " +
                    BB + " tar czf $T -C $B . 2>/dev/null; " +
                    "echo PATH=$T; echo SIZE=$(" + BB + " stat -c %s $T 2>/dev/null)";
            Root.Result r = Root.exec(ctx, command);
            parseArtifact(o, r, "日志包");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #11 备份 / 恢复 ----------

    public static JSONObject backup(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String command =
                    "B=" + TMP + "/backup; rm -rf $B; mkdir -p $B; " +
                    "[ -f /sdcard/smsrelay.conf ] && cp /sdcard/smsrelay.conf $B/smsrelay.conf; " +
                    "[ -d /data/data/com.sp970.smsrelay/shared_prefs ] && " +
                    "  cp -r /data/data/com.sp970.smsrelay/shared_prefs $B/shared_prefs; " +
                    "DB=/data/data/com.sp970.smsrelay/databases/relay.db; " +
                    "if [ -f $DB ]; then " +
                    "  if [ -x /system/bin/sqlite3 ]; then " +
                    "    sqlite3 $DB \".backup $B/relay.db\" 2>/dev/null || cp $DB $B/relay.db; " +
                    "  else cp $DB $B/relay.db; fi; fi; " +
                    "[ -f /data/misc/wifi/softap.conf ] && cp /data/misc/wifi/softap.conf $B/softap.conf; " +
                    "getprop > $B/props.txt 2>&1; " +
                    "cat /proc/uptime > $B/uptime.txt; " +
                    "U=$(cat /proc/uptime | " + BB + " cut -d'.' -f1); " +
                    "T=" + DL + "/smsrelay-backup-$U.tar.gz; " +
                    BB + " tar czf $T -C $B . 2>/dev/null; " +
                    "echo PATH=$T; echo SIZE=$(" + BB + " stat -c %s $T 2>/dev/null)";
            Root.Result r = Root.exec(ctx, command);
            parseArtifact(o, r, "备份");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /** 只从设备上已有的备份包恢复；不接 HTTP 上传（MAX_BODY 有限，上传走 adb push）。 */
    public static JSONObject restore(Context ctx, String path, boolean includeDb, String confirm) {
        JSONObject o = new JSONObject();
        try {
            if (!"RESTORE".equals(confirm)) throw new IllegalArgumentException("需要二次确认：confirm=RESTORE");
            if (path == null || !path.startsWith(DL + "/smsrelay-backup-")
                    || !path.endsWith(".tar.gz") || path.contains("..")) {
                throw new IllegalArgumentException("只能恢复 /sdcard/Download/smsrelay-backup-*.tar.gz");
            }
            // 先做一份当前状态的安全备份，恢复出问题还能退回来
            JSONObject safety = backup(ctx);
            String command =
                    "T='" + path + "'; B=" + TMP + "/restore; rm -rf $B; mkdir -p $B; " +
                    BB + " tar xzf $T -C $B 2>/dev/null || exit 9; " +
                    "APP=/data/data/com.sp970.smsrelay; U=$(" + BB + " stat -c %u $APP 2>/dev/null); " +
                    "[ -z \"$U\" ] && U=10075; " +
                    "[ -f $B/smsrelay.conf ] && cp $B/smsrelay.conf /sdcard/smsrelay.conf; " +
                    "[ -d $B/shared_prefs ] && cp -r $B/shared_prefs $APP/ && chown -R $U:$U $APP/shared_prefs; " +
                    (includeDb
                            ? "[ -f $B/relay.db ] && cp $B/relay.db $APP/databases/relay.db && chown $U:$U $APP/databases/relay.db; "
                            : "") +
                    "echo RESTORED; " +
                    "(sleep 2; am force-stop com.sp970.smsrelay; " +
                    " am startservice -n com.sp970.smsrelay/.RelayService) >/dev/null 2>&1 &";
            Root.Result r = Root.exec(ctx, command);
            o.put("ok", r.ok() && r.out.contains("RESTORED"));
            o.put("out", r.out);
            o.put("exit", r.exit);
            o.put("safetyBackup", safety.optString("path", ""));
            o.put("message", o.optBoolean("ok")
                    ? "已恢复配置" + (includeDb ? "+数据库" : "") + "，服务将在 2 秒后自动重启"
                    : "恢复失败：" + brief(r));
            Logs.add("maint", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #20 自定义恢复出厂 ----------
    //
    // 原厂 sysback.sh 会删 telephony.db / mmssms.db 再改 preferred_network_mode 重启 ——
    // 那是「清数据」，不是「重置配置」。这里只重置**自己写的东西**：
    //   config : 面板账号/端口、设备名、转发通道、SIM 锁、流量基线（= 回到内置示例）
    //   wifi   : 关热点 + 删 softap.conf（系统会重建默认 SSID）+ 清 MAC 白名单
    //   all    : 上面两样
    // 短信库 relay.db、系统短信库 mmssms.db、telephony.db 一律不碰。

    public static JSONObject factoryReset(Context ctx, String scope, String confirm) {
        JSONObject o = new JSONObject();
        try {
            if (!"RESET".equals(confirm)) throw new IllegalArgumentException("需要二次确认：confirm=RESET");
            String sc = scope == null ? "" : scope.trim();
            if (!"config".equals(sc) && !"wifi".equals(sc) && !"all".equals(sc)) {
                throw new IllegalArgumentException("scope 只能是 config / wifi / all");
            }
            JSONObject safety = backup(ctx);
            List<String> did = new ArrayList<String>();

            if ("config".equals(sc) || "all".equals(sc)) {
                ctx.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
                        .edit().clear().commit();
                File conf = new File(Environment.getExternalStorageDirectory(), AppConfig.CONF_NAME);
                if (conf.exists() && conf.delete()) did.add("已删除 /sdcard/" + AppConfig.CONF_NAME);
                else did.add("配置文件不存在或删除失败：/sdcard/" + AppConfig.CONF_NAME);
                ctx.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().clear().commit();
                did.add("已清面板账号/通道/SIM 锁/流量基线");
            }

            if ("wifi".equals(sc) || "all".equals(sc)) {
                try {
                    WifiCtl.apply(ctx, "ap_off", null, null);
                } catch (Throwable ignored) {
                }
                Root.Result r = Root.exec(ctx,
                        "rm -f /data/misc/wifi/softap.conf; rm -f /data/hostapd/hostapd.accept; echo ok");
                did.add(r.ok() ? "已关闭热点并重置 softap.conf / MAC 白名单" : "WiFi 重置失败：" + brief(r));
            }

            // 面板账号可能已经改回 admin/admin，必须重启进程才会用新端口/新密码
            Root.exec(ctx, "(sleep 2; am force-stop com.sp970.smsrelay; "
                    + "sleep 1; am start -n com.sp970.smsrelay/.MainActivity) >/dev/null 2>&1 &");

            o.put("ok", true);
            o.put("scope", sc);
            o.put("did", new JSONArray(did));
            o.put("safetyBackup", safety.optString("path", ""));
            o.put("message", "已重置（" + sc + "），服务 2 秒后重启；重置前已自动备份到 "
                    + safety.optString("path", "?") + "。短信与通话数据库未改动");
            Logs.add("maint", "恢复出厂 scope=" + sc);
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #12 文件列表 / 下载 ----------

    public static JSONObject files(Context ctx) {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            Root.Result r = Root.exec(ctx,
                    "for f in " + DL + "/*; do if [ -f \"$f\" ]; then " +
                    "echo \"$f|\"$(" + BB + " stat -c %s \"$f\" 2>/dev/null); fi; done");
            for (String line : r.out.split("\n")) {
                int i = line.lastIndexOf('|');
                if (i <= 0) continue;
                String p = line.substring(0, i).trim();
                long size = 0;
                try {
                    size = Long.parseLong(line.substring(i + 1).trim());
                } catch (Throwable ignored) {
                }
                JSONObject f = new JSONObject();
                f.put("path", p);
                f.put("name", p.substring(p.lastIndexOf('/') + 1));
                f.put("size", size);
                arr.put(f);
            }
            o.put("ok", true);
            o.put("files", arr);
            o.put("dir", DL);
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #7 电源 ----------

    public static JSONObject power(Context ctx, String action, String confirm) {
        JSONObject o = new JSONObject();
        try {
            String cmd;
            String label;
            if ("reboot".equals(action)) {
                cmd = "reboot";
                label = "重启";
            } else if ("shutdown".equals(action)) {
                cmd = "reboot -p";
                label = "关机";
            } else if ("bootloader".equals(action)) {
                cmd = "reboot bootloader";
                label = "进入 bootloader";
            } else {
                throw new IllegalArgumentException("action 只能是 reboot / shutdown / bootloader");
            }
            if (!action.toUpperCase().equals(confirm)) {
                throw new IllegalArgumentException("需要二次确认：confirm=" + action.toUpperCase());
            }
            // 后台延时执行，否则 CGI 还没把响应写回去设备就重启了
            Root.Result r = Root.exec(ctx, "(sleep 3; " + cmd + ") >/dev/null 2>&1 & echo SENT");
            o.put("ok", r.ok() && r.out.contains("SENT"));
            o.put("message", o.optBoolean("ok")
                    ? label + "命令已发出，3 秒后执行（短信转发会中断，约 1 分钟恢复）"
                    : label + "失败：" + brief(r));
            Logs.add("maint", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #21 定时任务 ----------

    public static JSONObject cronGet(Context ctx) {
        JSONObject o = new JSONObject();
        Root.Result r = Root.exec(ctx, "cat " + HELPER + "/crontab 2>/dev/null");
        try {
            o.put("ok", r.ok());
            o.put("text", r.out);
            o.put("message", r.ok() ? "保存后 30 秒内看门狗自动重载 crond" : brief(r));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject cronSet(Context ctx, String text) {
        JSONObject o = new JSONObject();
        try {
            if (text == null || text.length() > 8000) throw new IllegalArgumentException("crontab 太长（>8KB）");
            // base64 传输，避免引号/换行把 shell 命令拼坏
            String b64 = Base64.encodeToString(text.getBytes("UTF-8"), Base64.NO_WRAP);
            Root.Result r = Root.exec(ctx, "echo '" + b64 + "' | " + BB + " base64 -d > "
                    + HELPER + "/crontab; echo SAVED; " + BB + " cat " + HELPER + "/crontab");
            o.put("ok", r.ok() && r.out.contains("SAVED"));
            o.put("message", o.optBoolean("ok") ? "已保存，30 秒内自动重载 crond" : "保存失败：" + brief(r));
            o.put("text", text);
            Logs.add("maint", o.optString("message"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- #22 LED 定位 ----------

    /**
     * LED 定位（torch-light）。
     * ⚠ 回退到最初版本做基线对比（2026-11-07 用户要求）：
     * 写 7、toolbox sleep 0.3、不动 trigger。行为记录：
     *  亮=闪烁 / 灭=常亮 / 闪8下只见4下——用这个基线与各修正版对照，定位真实模型。
     */
    public static JSONObject led(Context ctx, String action) {
        JSONObject o = new JSONObject();
        try {
            String node = "/sys/class/leds/torch-light/brightness";
            String cmd;
            if ("on".equals(action)) cmd = "echo 7 > " + node;
            else if ("off".equals(action)) cmd = "echo 0 > " + node;
            else if ("blink".equals(action)) {
                cmd = "(for i in 1 2 3 4 5 6 7 8; do echo 7 > " + node
                        + "; sleep 0.3; echo 0 > " + node + "; sleep 0.3; done) >/dev/null 2>&1 & echo SENT";
            } else throw new IllegalArgumentException("action 只能是 on / off / blink");
            Root.Result r = Root.exec(ctx, cmd);
            o.put("ok", r.ok());
            o.put("out", r.out);
            o.put("message", r.ok() ? "LED " + action + " 已执行" : "LED 失败：" + brief(r));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    // ---------- 小工具 ----------

    private static void parseArtifact(JSONObject o, Root.Result r, String what) {
        try {
            String path = "", size = "0";
            for (String line : r.out.split("\n")) {
                if (line.startsWith("PATH=")) path = line.substring(5).trim();
                if (line.startsWith("SIZE=")) size = line.substring(5).trim();
            }
            long sz = 0;
            try {
                sz = Long.parseLong(size);
            } catch (Throwable ignored) {
            }
            o.put("ok", r.ok() && path.length() > 0 && sz > 0);
            o.put("path", path);
            o.put("size", sz);
            o.put("message", o.optBoolean("ok")
                    ? what + "已生成，可在下方文件列表下载"
                    : what + "生成失败：" + brief(r));
            if (o.optBoolean("ok")) Logs.add("maint", what + " " + path + " (" + sz + " B)");
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
    }

    private static Map<String, List<String>> sections(String out) {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        String cur = "";
        for (String line : out.split("\n")) {
            if (line.startsWith("##")) {
                cur = line.substring(2).trim();
                m.put(cur, new ArrayList<String>());
            } else if (m.containsKey(cur)) {
                m.get(cur).add(line);
            }
        }
        return m;
    }

    private static List<String> nonEmpty(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in == null) return out;
        for (String s : in) if (s.trim().length() > 0) out.add(s.trim());
        return out;
    }

    private static String first(List<String> in) {
        List<String> l = nonEmpty(in);
        return l.isEmpty() ? "" : l.get(0);
    }

    private static String join(List<String> in) {
        StringBuilder sb = new StringBuilder();
        if (in == null) return "";
        for (String s : in) sb.append(s).append('\n');
        return sb.toString();
    }

    private static int countLines(String s) {
        return s.length() == 0 ? 0 : s.split("\n").length;
    }

    private static int intIn(String s) {
        String[] f = s.trim().split("\\s+");
        if (f.length < 2) return 0;
        try {
            return Integer.parseInt(f[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean allZero(List<Integer> l) {
        for (Integer i : l) if (i != null && i != 0) return false;
        return true;
    }

    /** 只保留安全字符，避免把用户输入拼进 shell 单引号里造成注入。 */
    private static String safeFilter(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 64; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == ' ') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String brief(Root.Result r) {
        String s = (r.error.length() > 0 ? r.error : r.out).trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : (s.length() == 0 ? "exit=" + r.exit : s);
    }

    private static String str(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
    }
}
