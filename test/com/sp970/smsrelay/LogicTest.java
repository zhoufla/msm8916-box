package com.sp970.smsrelay;


import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 离线逻辑测试（不依赖 Android 运行时）：
 * 通道 JSON 往返、钉钉 text/markdown 载荷、webhook 载荷、验证码提取、失败退避。
 */
public class LogicTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] a) throws Exception {
        testChannelDefaults();
        testChannelJson();
        testMaxTriesClamp();
        testDingTalkText();
        testDingTalkMarkdown();
        testWebhook();
        testExtractCode();
        testBackoff();

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    // ---------- Channel ----------

    static void testChannelDefaults() throws Exception {
        Channel c = Channel.fromJson(new JSONObject("{\"name\":\"a\",\"type\":\"dingtalk\",\"url\":\"http://x\"}"));
        eq("默认 format=text", "text", c.format);
        eq("默认 maxTries=10", 10, c.maxTries);
        eq("默认 tag 为空", "", c.tag);
        eq("默认 enabled", true, c.enabled);
        eq("dingtalk 判定", true, c.isDingTalk());
        eq("非 markdown", false, c.isMarkdown());
        eq("valid（url 为 http）", true, c.valid());
    }

    static void testChannelJson() throws Exception {
        Channel c = new Channel();
        c.name = "运维群";
        c.type = "dingtalk";
        c.url = "https://oapi.dingtalk.com/robot/send?access_token=x";
        c.format = "markdown";
        c.title = "重要短信";
        c.tag = "客厅-158";
        c.maxTries = 3;
        JSONObject o = c.toJson();
        eq("toJson 带 format", "markdown", o.optString("format"));
        eq("toJson 带 title", "重要短信", o.optString("title"));
        eq("toJson 带 tag", "客厅-158", o.optString("tag"));
        eq("toJson 带 maxTries", 3, o.optInt("maxTries"));

        Channel back = Channel.fromJson(o);
        eq("往返 name", c.name, back.name);
        eq("往返 format", "markdown", back.format);
        eq("往返 title", "重要短信", back.title);
        eq("往返 tag", "客厅-158", back.tag);
        eq("往返 maxTries", 3, back.maxTries);
        eq("往返 isMarkdown", true, back.isMarkdown());

        // 纯文本的钉钉通道不该把 format 写进配置
        Channel t = new Channel();
        t.type = "dingtalk";
        t.format = "text";
        eq("text 不写 format 字段", false, t.toJson().has("format"));
        // webhook 通道即使 format=markdown 也不落字段
        Channel w = new Channel();
        w.type = "webhook";
        w.format = "markdown";
        eq("webhook 不写 format 字段", false, w.toJson().has("format"));
    }

    static void testMaxTriesClamp() throws Exception {
        Channel c0 = Channel.fromJson(new JSONObject("{\"maxTries\":0}"));
        eq("maxTries=0 夹到 1", 1, c0.maxTries);
        Channel c99 = Channel.fromJson(new JSONObject("{\"maxTries\":99}"));
        eq("maxTries=99 夹到 50", 50, c99.maxTries);
    }

    // ---------- Sender ----------

    static void testDingTalkText() throws Exception {
        Channel c = new Channel();
        c.name = "ch";
        c.type = "dingtalk";
        c.prefix = "【短信】";
        String body = capture(c, "10086", "余额不足");

        JSONObject o = new JSONObject(body);
        eq("text msgtype", "text", o.getString("msgtype"));
        String content = o.getJSONObject("text").getString("content");
        eq("text 内容格式", "【短信】[10086]\n余额不足", content);
    }

    static void testDingTalkMarkdown() throws Exception {
        Channel c = new Channel();
        c.name = "ch";
        c.type = "dingtalk";
        c.prefix = "【短信】";
        c.format = "markdown";
        String body = capture(c, "10086", "验证码 1234");
        JSONObject o = new JSONObject(body);
        eq("markdown msgtype", "markdown", o.getString("msgtype"));
        JSONObject md = o.getJSONObject("markdown");
        eq("markdown 自动标题", "短信 · 158 · 10086", md.getString("title"));
        String text = md.getString("text");
        eq("markdown 正文含关键词前缀", true, text.startsWith("【短信】[10086]"));
        eq("markdown 正文含原文", true, text.indexOf("验证码 1234") > 0);
        eq("markdown 带时间脚注", true, text.indexOf("\n\n> ") > 0);

        c.title = "告警";
        c.tag = "客厅";
        JSONObject o2 = new JSONObject(capture(c, "10086", "x"));
        eq("自定义标题带标签", "[客厅] 告警", o2.getJSONObject("markdown").getString("title"));
        eq("tag 进正文", true,
                o2.getJSONObject("markdown").getString("text").startsWith("[客厅] 【短信】"));
    }

    static void testWebhook() throws Exception {
        Channel c = new Channel();
        c.name = "hook";
        c.type = "webhook";
        c.tag = "卧室";
        String body = capture(c, "10086", "hello");
        JSONObject o = new JSONObject(body);
        eq("webhook device", "158", o.getString("device"));
        eq("webhook tag", "卧室", o.getString("tag"));
        eq("webhook peer", "10086", o.getString("peer"));
        eq("webhook body", "hello", o.getString("body"));
        eq("webhook 有 iso", true, o.getString("iso").length() == 19);

        Channel noTag = new Channel();
        noTag.type = "webhook";
        eq("无 tag 时不带字段", false, new JSONObject(capture(noTag, "1", "x")).has("tag"));
    }

    /** 起一个一次性 HTTP 服务，收下 Sender 发的 body。 */
    static String capture(final Channel ch, String peer, String text) throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final String[] got = new String[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket s = ss.accept();
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    int b, state = 0;
                    while ((b = in.read()) >= 0) {
                        head.write(b);
                        if (b == '\r' && (state == 0 || state == 2)) state++;
                        else if (b == '\n' && (state == 1 || state == 3)) state++;
                        else state = 0;
                        if (state == 4) break;
                    }
                    String h = head.toString("UTF-8");
                    int len = 0;
                    for (String line : h.split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            len = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    byte[] buf = new byte[len];
                    int off = 0;
                    while (off < len) {
                        int n = in.read(buf, off, len - off);
                        if (n < 0) break;
                        off += n;
                    }
                    got[0] = new String(buf, 0, off, "UTF-8");
                    OutputStream out = s.getOutputStream();
                    byte[] resp = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes("UTF-8");
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + resp.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    out.write(resp);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();

        ch.url = "http://127.0.0.1:" + ss.getLocalPort() + "/hook";
        Deliver d = new Deliver();
        d.peer = peer;
        d.body = text;
        d.ts = 1793950857000L;
        d.code = "";
        String err = Sender.send(ch, d, "158");
        eq("发送返回成功", null, err);
        t.join(3000);
        ss.close();
        return got[0] == null ? "{}" : got[0];
    }

    // ---------- 其他纯逻辑 ----------

    static void testExtractCode() {
        eq("验证码 1234", "1234", SmsEvent.extractCode("【银行】您的验证码是 1234，5 分钟内有效"));
        eq("动态码", "554433", SmsEvent.extractCode("动态码:554433 请勿泄露"));
        eq("code 关键字", "998877", SmsEvent.extractCode("Your login code 998877 expires soon"));
        eq("普通数字不误判", "", SmsEvent.extractCode("您的余额为 100 元，消费 25.50"));
        eq("空内容", "", SmsEvent.extractCode(""));
    }

    static void testBackoff() {
        long[] want = {60000L, 120000L, 240000L, 480000L, 960000L, 1800000L,
                1800000L, 1800000L, 1800000L, 1800000L};
        for (int i = 0; i < want.length; i++) {
            eq("第 " + (i + 1) + " 次失败退避", want[i], Retry.backoffMs(i + 1));
        }
        eq("退避次数小于 1 也按 1 算", 60000L, Retry.backoffMs(0));
        eq("maxTries 夹紧下界", 1, Retry.clampTries(0));
        eq("maxTries 夹紧上界", 50, Retry.clampTries(99));
        eq("maxTries 正常值", 10, Retry.clampTries(10));
        eq("第 10 次放弃", true, Retry.giveUp(9, 10));
        eq("第 9 次不放弃", false, Retry.giveUp(8, 10));
        eq("maxTries=3 时第 3 次放弃", true, Retry.giveUp(2, 3));
    }

    // ---------- 断言 ----------

    static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "：期望 [" + want + "]，实际 [" + got + "]");
        }
    }
}
