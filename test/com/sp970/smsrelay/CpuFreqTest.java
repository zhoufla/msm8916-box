package com.sp970.smsrelay;

/**
 * cpufreq 纯逻辑单测（FreqTable）：解析/格式化/校验。
 * 可用频率表用本机实测值：200000 400000 533333 800000 998400 1094400 1152000 1209600。
 */
public class CpuFreqTest {

    private static int pass = 0;
    private static int fail = 0;
    private static final String LINE = "200000 400000 533333 800000 998400 1094400 1152000 1209600";

    public static void main(String[] a) {
        testParse();
        testFmt();
        testValidate();

        System.out.println();
        System.out.println("CpuFreqTest 通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    static void testParse() {
        long[] av = FreqTable.parseFreqs(LINE);
        eq("8 档频率", 8, av.length);
        eq("升序首项", 200000L, av[0]);
        eq("升序末项", 1209600L, av[7]);
        eq("乱序输入也升序", 400000L, FreqTable.parseFreqs("800000 200000 400000")[1]);
        eq("空串", 0, FreqTable.parseFreqs("").length);
        eq("null", 0, FreqTable.parseFreqs(null).length);
        eq("非法 token 丢弃", 2, FreqTable.parseFreqs("200000 abc 400000").length);
        eq("负数/零丢弃", 0, FreqTable.parseFreqs("0 -5").length);
        eq("多余空白", 8, FreqTable.parseFreqs("  " + LINE + "  ").length);
    }

    static void testFmt() {
        eq("998400 → 998 MHz", "998 MHz", FreqTable.fmtMhz(998400));
        eq("1209600 → 1.21 GHz", "1.21 GHz", FreqTable.fmtMhz(1209600));
        eq("200000 → 200 MHz", "200 MHz", FreqTable.fmtMhz(200000));
        eq("0 → -", "-", FreqTable.fmtMhz(0));
        eq("负数 → -", "-", FreqTable.fmtMhz(-1));
    }

    static void testValidate() {
        long[] av = FreqTable.parseFreqs(LINE);
        ok("合法 min/max 通过", FreqTable.validate(400000, 998400, av) == null);
        ok("min==max 通过", FreqTable.validate(998400, 998400, av) == null);
        ok("max 不在表里拒绝", FreqTable.validate(400000, 999999, av) != null);
        ok("min 不在表里拒绝", FreqTable.validate(123456, 998400, av) != null);
        ok("min>max 拒绝", FreqTable.validate(998400, 400000, av) != null);
        ok("空表拒绝", FreqTable.validate(200000, 400000, new long[0]) != null);
        ok("空表拒绝(null)", FreqTable.validate(200000, 400000, null) != null);
    }

    static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  \u2713 " + name);
        } else {
            fail++;
            System.out.println("  \u2717 " + name);
        }
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
