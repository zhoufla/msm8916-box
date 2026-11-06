package com.sp970.smsrelay;

/**
 * 投递失败退避策略（纯逻辑，方便离线测试）：
 * 1/2/4/8/16/30 分钟，之后固定 30 分钟；次数上限由通道配置决定，夹在 1~50。
 */
final class Retry {

    private Retry() {
    }

    static long backoffMs(int tries) {
        int t = tries < 1 ? 1 : tries;
        return 60L * 1000L * Math.min(30, 1 << Math.min(t - 1, 5));
    }

    static int clampTries(int maxTries) {
        if (maxTries < 1) return 1;
        return maxTries > 50 ? 50 : maxTries;
    }

    /** 这次失败之后是否该放弃（tries 传的是本次失败前的次数）。 */
    static boolean giveUp(int tries, int maxTries) {
        return tries + 1 >= clampTries(maxTries);
    }
}
