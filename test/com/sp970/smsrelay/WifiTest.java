package com.sp970.smsrelay;

/**
 * WiFi 连接配置纯逻辑单测（WifiCfg）：SSID 去引号/比较、psk 包引号。
 *
 * 背景：SP970 ROM 的 wifi JNI 不给 psk 包引号，带密码 addNetwork 直接被
 * supplicant 拒（Invalid PSK）——修复方案是失败后用 quotePsk 包引号重试。
 */
public class WifiTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) {
        testStripQuotes();
        testSsidEquals();
        testQuotePsk();

        System.out.println();
        System.out.println("WifiTest 通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    static void testStripQuotes() {
        eq("stripQuotes 基本去引号", "LUOLUOM", WifiCfg.stripQuotes("\"LUOLUOM\""));
        eq("stripQuotes 无引号原样", "LUOLUOM", WifiCfg.stripQuotes("LUOLUOM"));
        eq("stripQuotes null 安全", "", WifiCfg.stripQuotes(null));
        eq("stripQuotes 空串", "", WifiCfg.stripQuotes(""));
        eq("stripQuotes 单个引号不算包裹", "\"", WifiCfg.stripQuotes("\""));
        eq("stripQuotes 两个引号", "", WifiCfg.stripQuotes("\"\""));
        eq("stripQuotes 中间引号保留", "lu\"ol", WifiCfg.stripQuotes("lu\"ol"));
        eq("stripQuotes 首尾空白", "abc", WifiCfg.stripQuotes("  \"abc\" "));
        eq("stripQuotes 引号内空白保留", "a b", WifiCfg.stripQuotes("\"a b\""));
    }

    static void testSsidEquals() {
        ok("ssidEquals 带引号命中", WifiCfg.ssidEquals("\"LUOLUOM\"", "LUOLUOM"));
        ok("ssidEquals 都带引号", WifiCfg.ssidEquals("\"LUOLUOM\"", "\"LUOLUOM\""));
        ok("ssidEquals 都不带", WifiCfg.ssidEquals("LUOLUOM", "LUOLUOM"));
        ok("ssidEquals null 安全", !WifiCfg.ssidEquals(null, "LUOLUOM"));
        ok("ssidEquals target null", !WifiCfg.ssidEquals("\"LUOLUOM\"", null));
        ok("ssidEquals target 空串拒绝", !WifiCfg.ssidEquals("\"LUOLUOM\"", ""));
        ok("ssidEquals target 纯引号拒绝", !WifiCfg.ssidEquals("\"LUOLUOM\"", "\"\""));
        ok("ssidEquals 不同网络", !WifiCfg.ssidEquals("\"LUOLUOM\"", "other"));
        ok("ssidEquals 大小写敏感", !WifiCfg.ssidEquals("\"LUOLUOM\"", "luoluom"));
    }

    static void testQuotePsk() {
        eq("quotePsk 普通口令", "\"5880172xyz\"", WifiCfg.quotePsk("5880172xyz"));
        eq("quotePsk 已包裹原样", "\"abc\"", WifiCfg.quotePsk("\"abc\""));
        eq("quotePsk null 原样", null, WifiCfg.quotePsk(null));
        eq("quotePsk 空串原样", "", WifiCfg.quotePsk(""));
        eq("quotePsk 64hex 也包（hex 合法但保持 passphrase 形式一致性）",
                "\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"",
                WifiCfg.quotePsk("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        eq("quotePsk 纯数字", "\"12345678\"", WifiCfg.quotePsk("12345678"));
        eq("quotePsk 8 位下限", "\"12345678\"", WifiCfg.quotePsk("12345678"));
        eq("quotePsk 含引号字符（首尾不是引号，照包）", "\"a\"b\"", WifiCfg.quotePsk("a\"b"));
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
        boolean okc = (expect == null) ? got == null : expect.equals(got);
        if (okc) {
            pass++;
            System.out.println("  \u2713 " + name);
        } else {
            fail++;
            System.out.println("  \u2717 " + name + "（期望 " + expect + "，实际 " + got + "）");
        }
    }
}
