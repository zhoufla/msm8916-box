package com.sp970.smsrelay;

/**
 * 桌面离线测试用的 Logs 替身：真实实现依赖 android.util.Log，
 * 桌面 JVM 上用简单打印代替，行为（环形缓冲以外的输出）一致。
 */
public final class Logs {
    public static void add(String tag, String msg) {
        System.out.println("    log[" + tag + "] " + msg);
    }
}
