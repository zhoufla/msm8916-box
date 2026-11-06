package android.util;

/**
 * 桌面离线测试用的 android.util.Base64 替身。
 * 只实现 Sender 钉钉加签用到的 encodeToString(byte[], int)。
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return null;
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) e = e.withoutPadding();
        String s = e.encodeToString(input);
        return (flags & NO_WRAP) != 0 ? s.replace("\n", "") : s;
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
