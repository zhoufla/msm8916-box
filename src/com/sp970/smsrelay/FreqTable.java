package com.sp970.smsrelay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * cpufreq 纯逻辑（不碰 android.*，离线单测覆盖）。
 *
 * 频率单位统一 kHz（/sys 节点原生单位）；展示用 fmtMhz。
 */
public final class FreqTable {

    private FreqTable() {
    }

    /** "200000 400000 ..." → 升序 long[]；空/非法输入返回空数组，非正数丢弃。 */
    public static long[] parseFreqs(String line) {
        if (line == null) return new long[0];
        List<Long> out = new ArrayList<Long>();
        for (String s : line.trim().split("\\s+")) {
            if (s.length() == 0) continue;
            try {
                long v = Long.parseLong(s);
                if (v > 0) out.add(v);
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(out);
        long[] a = new long[out.size()];
        for (int i = 0; i < out.size(); i++) a[i] = out.get(i);
        return a;
    }

    /** kHz → 展示文本：1209600 → "1.21 GHz"，998400 → "998 MHz"，非法 → "-"。 */
    public static String fmtMhz(long khz) {
        if (khz <= 0) return "-";
        if (khz >= 1000000) {
            double g = khz / 1000000.0;
            return Math.round(g * 100) / 100.0 + " GHz";
        }
        return khz / 1000 + " MHz";
    }

    /** 频率是否在可用表里。 */
    public static boolean inList(long f, long[] list) {
        if (list == null) return false;
        for (int i = 0; i < list.length; i++) if (list[i] == f) return true;
        return false;
    }

    /** 校验 min/max：都在表里且 min ≤ max。返回 null 表示通过，否则是错误消息。 */
    public static String validate(long min, long max, long[] available) {
        if (available == null || available.length == 0) {
            return "读不到可用频率表（cpufreq 节点不存在）";
        }
        if (!inList(max, available)) {
            return "最大频率 " + max + " 不在可用表里，可选：" + fmtAll(available);
        }
        if (!inList(min, available)) {
            return "最小频率 " + min + " 不在可用表里，可选：" + fmtAll(available);
        }
        if (min > max) {
            return "最小频率不能大于最大频率";
        }
        return null;
    }

    private static String fmtAll(long[] av) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < av.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(fmtMhz(av[i]));
        }
        return sb.toString();
    }
}
