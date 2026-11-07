package com.sp970.smsrelay;

import org.json.JSONArray;

/**
 * 告警的纯 JDK 判定与解析（P0-C）：无 android 依赖，桌面单测直接覆盖。
 * Alerts（android 侧）负责取数与推送，这里只管"算"。
 */
public final class AlertLogic {
    private AlertLogic() {
    }

    /** 状态翻转判定：fire=首次告警 / remind=持续异常重复提醒 / recover=恢复 / ""=不动。 */
    static String decide(boolean wasFiring, long lastSentMs, long nowMs, boolean bad, long remindMs) {
        if (bad) {
            if (!wasFiring) return "fire";
            if (nowMs - lastSentMs >= remindMs) return "remind";
            return "";
        }
        return wasFiring ? "recover" : "";
    }

    /** dumpsys battery 输出里的 level: N（找不到回 -1）。 */
    static int parseBatteryLevel(String dumpsys) {
        if (dumpsys == null) return -1;
        String[] lines = dumpsys.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("level:")) {
                try {
                    return Integer.parseInt(t.substring(6).trim());
                } catch (Throwable ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 是否在充电（AC/USB/Wireless 任一 powered: true）。 */
    static boolean parseBatteryPowered(String dumpsys) {
        if (dumpsys == null) return false;
        String[] lines = dumpsys.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().endsWith("powered: true")) return true;
        }
        return false;
    }

    /** issues 数组拼成一行：a；b；c。 */
    static String joinIssues(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append("；");
            sb.append(String.valueOf(arr.opt(i)));
        }
        return sb.toString();
    }

    /** 取第一行（日志摘要用）。 */
    static String firstLine(String s) {
        int c = s.indexOf('\n');
        return c > 0 ? s.substring(0, c) : s;
    }
}
