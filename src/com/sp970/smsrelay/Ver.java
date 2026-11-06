package com.sp970.smsrelay;

import java.security.MessageDigest;

/**
 * 版本号比较、sha256、体积格式化——纯 JDK，不碰 Android，方便离线测试。
 *
 * 版本串统一成 a.b.c 三段整数比较：tag 上的 v1.2.3、1.2、1.2.3-beta 都能吃，
 * 缺的段按 0 算。这样 CI 打 tag 时不用纠结格式。
 */
public final class Ver {

    private Ver() {
    }

    /** v1.2.3 / V1.2.3 / release-1.2.3 → 1.2.3 */
    public static String norm(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        if (s.length() == 0) return "";
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        s = s.substring(i);
        // 去掉 -beta / +build 这类后缀，只留数字和点
        int cut = s.length();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '.' && !Character.isDigit(c)) {
                cut = k;
                break;
            }
        }
        s = s.substring(0, cut);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "1.2.3" → {1,2,3}；非法段按 0。 */
    public static int[] parts(String v) {
        int[] out = new int[]{0, 0, 0};
        String s = norm(v);
        if (s.length() == 0) return out;
        String[] p = s.split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (Throwable ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** 远程比本地新返回 true。 */
    public static boolean newer(String remote, String local) {
        return cmp(remote, local) > 0;
    }

    /** a > b 返回 1，相等 0，a < b 返回 -1。 */
    public static int cmp(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] > y[i] ? 1 : -1;
        }
        return 0;
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + "." + ((n % 1024) * 10 / 1024) + " KB";
        return (n / (1024 * 1024)) + "." + ((n % (1024 * 1024)) * 10 / (1024 * 1024)) + " MB";
    }

    /** 发布说明太长会撑爆面板，掐到 limit 字并压成单行。 */
    public static String brief(String s, int limit) {
        if (s == null) return "";
        String t = s.replace("\r", "").trim();
        if (t.length() > limit) t = t.substring(0, limit) + "…";
        return t;
    }
}
