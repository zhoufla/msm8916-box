package com.sp970.smsrelay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CPU 频率限制（cpufreq）。
 *
 * 本机（msm8916）实测结构：
 *  - 单 policy：cpu0 是 owner，related_cpus = 0 1 2 3；离线核没有 cpufreq 目录
 *    （mpdecision 热插拔把 cpu1-3 摘掉后节点整个消失）。
 *  - 可用频率：200/400/533/800/998/1094/1152/1209 MHz，governor=interactive。
 *  - ⚠ 权限坑：scaling_max_freq 是 -rw-rw---- system system，others 不可读——
 *    应用进程（u0_a16）直接 java IO 读会拿到空串。所以 cpufreq 节点的读和写
 *    【全部走 relay-helper root 通道】，一个 HTTP 回环请求，开销可接受。
 *  - policy 共享，写一次全体在线核生效，后续热插拔上线的核沿用 policy 限值。
 *  - thermal-engine / 厂商脚本可能重新钳制，支持服务循环周期校正（enforce）。
 *
 * 顺序约定：先写 max 再写 min（kernel 会互相钳制，顺序只影响瞬时值）。
 * 纯逻辑（解析/校验/格式化）在 FreqTable，离线单测覆盖。
 */
public final class CpuFreq {

    public static final String BASE = "/sys/devices/system/cpu/cpu0/cpufreq";

    private CpuFreq() {
    }

    private static String sec(String out, String key) {
        if (out == null) return "";
        int i = out.indexOf(key);
        if (i < 0) return "";
        String rest = out.substring(i + key.length()).trim();
        int nl = rest.indexOf('\n');
        return (nl < 0 ? rest : rest.substring(0, nl)).trim();
    }

    /** 经 root 读一个 /sys 节点；失败返回空串。 */
    private static String rootCat(Context ctx, String path) {
        Root.Result r = Root.exec(ctx, "cat " + path);
        return r.ok() ? r.out.trim() : "";
    }

    /** 当前 cpufreq 状态（一次 root 请求读全）；helper 不可用时字段缺省。 */
    public static JSONObject info(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Root.Result r = Root.exec(ctx,
                    "echo '##AV'; cat " + BASE + "/scaling_available_frequencies;" +
                    " echo '##GA'; cat " + BASE + "/scaling_available_governors;" +
                    " echo '##GV'; cat " + BASE + "/scaling_governor;" +
                    " echo '##CUR'; cat " + BASE + "/scaling_cur_freq;" +
                    " echo '##MIN'; cat " + BASE + "/scaling_min_freq;" +
                    " echo '##MAX'; cat " + BASE + "/scaling_max_freq;" +
                    " echo '##REL'; cat " + BASE + "/related_cpus");
            o.put("helperOk", r.ok());
            if (!r.ok()) {
                o.put("error", r.error.length() > 0 ? r.error : "exit=" + r.exit);
                o.put("onlineCores", countOnline());
                return o;
            }
            long[] av = FreqTable.parseFreqs(sec(r.out, "##AV"));
            JSONArray a = new JSONArray();
            for (int i = 0; i < av.length; i++) a.put(av[i]);
            o.put("available", a);
            JSONArray g = new JSONArray();
            for (String s : sec(r.out, "##GA").split("\\s+")) {
                if (s.length() > 0) g.put(s);
            }
            o.put("governors", g);
            o.put("governor", sec(r.out, "##GV"));
            o.put("cur", sec(r.out, "##CUR"));
            o.put("min", sec(r.out, "##MIN"));
            o.put("max", sec(r.out, "##MAX"));
            o.put("relatedCpus", sec(r.out, "##REL"));
            o.put("onlineCores", countOnline());
        } catch (Throwable t) {
            Logs.add("maint", "cpufreq info 失败: " + t);
        }
        return o;
    }

    private static int countOnline() {
        java.io.BufferedReader br = null;
        int n = 0;
        try {
            // online 节点是世界可读的，直接 java IO；cpu0 没有 online 节点（不可摘）
            for (int i = 0; i < 8; i++) {
                String v = "";
                try {
                    br = new java.io.BufferedReader(
                            new java.io.FileReader("/sys/devices/system/cpu/cpu" + i + "/online"));
                    v = br.readLine();
                    br.close();
                    br = null;
                } catch (Throwable t) {
                    v = "";
                }
                if (v == null) v = "";
                v = v.trim();
                if (v.length() == 0) {
                    if (i == 0) { n++; continue; }
                    break;   // 更高编号没目录 = 到顶了
                }
                if ("1".equals(v)) n++;
            }
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        return n;
    }

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("message", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 应用限频并保存配置；auto=true 时服务循环会周期校正。
     * min 传 0 表示「不动下限」，用当前值补齐（只限最高频的常用场景）。
     * 校验不过直接拒绝（不写半截），失败时设备保持原状。
     */
    public static JSONObject apply(Context ctx, long min, long max, boolean auto) {
        try {
            long[] av = FreqTable.parseFreqs(rootCat(ctx, BASE + "/scaling_available_frequencies"));
            // min 传 0 表示「不动下限」，用当前值补齐
            if (min <= 0) {
                String cur = rootCat(ctx, BASE + "/scaling_min_freq");
                try { min = Long.parseLong(cur); } catch (Throwable t) { min = -1; }
                if (min <= 0) return fail("读不到当前最小频率，请显式指定 min");
            }
            if (max <= 0) return fail("请指定最大频率");
            String err = FreqTable.validate(min, max, av);
            if (err != null) return fail(err);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append("[ -d /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq ] || continue;")
                  .append(" echo ").append(max)
                  .append(" > /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq/scaling_max_freq;")
                  .append(" echo ").append(min)
                  .append(" > /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq/scaling_min_freq;");
            }
            sb.append(" echo '##MAX'; cat ").append(BASE).append("/scaling_max_freq;")
              .append(" echo '##MIN'; cat ").append(BASE).append("/scaling_min_freq");
            Root.Result r = Root.exec(ctx, sb.toString());
            if (!r.ok()) {
                return fail("root 通道执行失败：" + (r.error.length() > 0 ? r.error : "exit=" + r.exit));
            }
            String maxNow = sec(r.out, "##MAX");
            String minNow = sec(r.out, "##MIN");
            boolean ok = String.valueOf(max).equals(maxNow) && String.valueOf(min).equals(minNow);
            // 配置存独立 prefs 键，与共享的 config JSON 解耦（防其它保存路径回退）
            save(ctx, auto, max, min);
            Logs.add("maint", "CPU 限频 → max=" + max + " min=" + min + " auto=" + auto
                    + " 生效=" + ok);
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("message", ok ? "已生效：最高 " + FreqTable.fmtMhz(max)
                            + " / 最低 " + FreqTable.fmtMhz(min)
                            + (auto ? "（服务循环持续维持）" : "（仅本次）")
                    : "写入后读回不一致（max=" + maxNow + " min=" + minNow
                    + "），可能被 thermal/厂商脚本立即改回");
            o.put("max", maxNow);
            o.put("min", minNow);
            return o;
        } catch (Throwable t) {
            return fail("应用失败: " + t);
        }
    }

    // ---------- 配置存取（独立 SharedPreferences 键） ----------
    //
    // ⚠ 不放进 AppConfig 的 maint 段：那会被 /api/config（通道/登录保存）、
    // /api/maint/config（helper 配置）等多条路径整段重写，旧页面一保存就回退
    // （2026-11-07 真机踩过「维持开关被改回仅一次」）。独立键谁也碰不到。

    /** CPU 限频配置（enabled/max/min）。 */
    public static final class CpuCfg {
        public boolean enabled;
        public long maxKhz;
        public long minKhz;
    }

    private static final String K_ENABLED = "cpuLimitEnabled";
    private static final String K_MAX = "cpuMaxKhz";
    private static final String K_MIN = "cpuMinKhz";

    public static CpuCfg load(Context ctx) {
        CpuCfg c = new CpuCfg();
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
            c.enabled = sp.getBoolean(K_ENABLED, false);
            c.maxKhz = sp.getLong(K_MAX, 0);
            c.minKhz = sp.getLong(K_MIN, 0);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static void save(Context ctx, boolean enabled, long maxKhz, long minKhz) {
        android.content.SharedPreferences.Editor e =
                ctx.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(K_ENABLED, enabled);
        e.putLong(K_MAX, maxKhz);
        e.putLong(K_MIN, minKhz);
        e.commit();
    }

    /**
     * 关闭限频：停止维持并立即解除（max 写回可用表最大值，恢复全速）。
     */
    public static JSONObject disable(Context ctx) {
        try {
            // 只停用维持，保留已保存的限值（总开关再打开时能直接恢复）
            CpuCfg prev = load(ctx);
            save(ctx, false, prev.maxKhz, prev.minKhz);
            long[] av = FreqTable.parseFreqs(rootCat(ctx, BASE + "/scaling_available_frequencies"));
            if (av.length == 0) return fail("读不到可用频率表");
            long full = av[av.length - 1];
            long min;
            try { min = Long.parseLong(rootCat(ctx, BASE + "/scaling_min_freq")); }
            catch (Throwable t) { min = av[0]; }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append("[ -d /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq ] || continue;")
                  .append(" echo ").append(full)
                  .append(" > /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq/scaling_max_freq;")
                  .append(" echo ").append(min)
                  .append(" > /sys/devices/system/cpu/cpu").append(i)
                  .append("/cpufreq/scaling_min_freq;");
            }
            sb.append(" echo '##MAX'; cat ").append(BASE).append("/scaling_max_freq");
            Root.Result r = Root.exec(ctx, sb.toString());
            String maxNow = sec(r.out, "##MAX");
            boolean ok = r.ok() && String.valueOf(full).equals(maxNow);
            Logs.add("maint", "CPU 限频关闭 → 恢复全速 " + full + " 生效=" + ok);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("message", ok ? "限频已关闭：恢复全速 " + FreqTable.fmtMhz(full)
                        : "解除失败（max=" + maxNow + "）");
            } catch (Throwable ignored) {}
            return o;
        } catch (Throwable t) {
            return fail("关闭限频失败: " + t);
        }
    }

    /** 仅启停维持（不动频率值）：on 时用已保存的 max/min 继续维持，并立即应用一次。 */
    public static JSONObject setEnabled(Context ctx, boolean on) {
        CpuCfg c = load(ctx);
        if (on && c.maxKhz <= 0) {
            return fail("还没有应用过限频，请先选频率点「应用」");
        }
        save(ctx, on, c.maxKhz, c.minKhz);
        Logs.add("maint", "CPU 限频维持 → " + (on ? "开启" : "暂停"));
        if (on) return apply(ctx, c.minKhz, c.maxKhz, true);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("message", "维持已暂停（当前限值仍生效，循环不再校正）");
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * 服务循环校正：已启用且实际 max 与配置不一致时重新应用。
     * thermal/厂商脚本改回去的场景在这里被拉回；helper 不可用就等下一轮。
     */
    public static void enforce(Context ctx) {
        try {
            CpuCfg c = load(ctx);
            if (!c.enabled || c.maxKhz <= 0) return;
            String cur = rootCat(ctx, BASE + "/scaling_max_freq");
            if (cur.length() == 0 || String.valueOf(c.maxKhz).equals(cur)) return;
            apply(ctx, c.minKhz, c.maxKhz, true);
        } catch (Throwable ignored) {
        }
    }
}
