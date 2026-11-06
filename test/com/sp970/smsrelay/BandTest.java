package com.sp970.smsrelay;

/**
 * LTE EARFCN → 频段映射单测（LteBand）。
 * 边界值全打：每段两端都落在正确频段，段间空档返回空。
 */
public class BandTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) {
        // 国内主用频段
        eq("B3 下界 1200", "B3", LteBand.fromEarfcn(1200));
        eq("B3 内部 1650", "B3", LteBand.fromEarfcn(1650));
        eq("B3 上界 1949", "B3", LteBand.fromEarfcn(1949));
        eq("B1 下界 0", "B1", LteBand.fromEarfcn(0));
        eq("B1 上界 599", "B1", LteBand.fromEarfcn(599));
        eq("B5 2400", "B5", LteBand.fromEarfcn(2400));
        eq("B8 3450", "B8", LteBand.fromEarfcn(3450));
        eq("B8 3799", "B8", LteBand.fromEarfcn(3799));
        eq("B38 36950", "B38", LteBand.fromEarfcn(36950));
        eq("B38 37549", "B38", LteBand.fromEarfcn(37549));
        eq("B39 37550", "B39", LteBand.fromEarfcn(37550));
        eq("B40 37950", "B40", LteBand.fromEarfcn(37950));
        eq("B41 38350", "B41", LteBand.fromEarfcn(38350));
        eq("B41 38649", "B41", LteBand.fromEarfcn(38649));
        eq("B34 36200", "B34", LteBand.fromEarfcn(36200));
        // 段间空档 / 越界
        eq("空档 4950（B11 与 B12 之间）", "", LteBand.fromEarfcn(4950));
        eq("空档 7400（B22 与 B23 之间）", "", LteBand.fromEarfcn(7400));
        eq("空档 10360（B32 与 TDD 之间）", "", LteBand.fromEarfcn(10360));
        eq("负数", "", LteBand.fromEarfcn(-1));
        eq("超出表尾 43600", "", LteBand.fromEarfcn(43600));
        // 其他代表频段
        eq("B2 600", "B2", LteBand.fromEarfcn(600));
        eq("B7 2750", "B7", LteBand.fromEarfcn(2750));
        eq("B20 6150", "B20", LteBand.fromEarfcn(6150));
        eq("B28 9210", "B28", LteBand.fromEarfcn(9210));
        eq("B33 36000", "B33", LteBand.fromEarfcn(36000));
        eq("B35 重叠段先命中 36350", "B35", LteBand.fromEarfcn(36350));

        System.out.println();
        System.out.println("BandTest 通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    static void eq(String name, Object expect, Object got) {
        if (expect == null ? got == null : expect.equals(got)) {
            pass++;
            System.out.println("  \u2713 " + name);
        } else {
            fail++;
            System.out.println("  \u2717 " + name + "（期望 " + expect + "，实际 " + got + "）");
        }
    }
}
