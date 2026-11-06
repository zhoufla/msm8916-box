package com.sp970.smsrelay;

import java.security.MessageDigest;

/** 极简 MD5，避免依赖 commons-codec。 */
final class MD5 {

    static String of(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (int i = 0; i < b.length; i++) {
                int v = b[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
