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
        testTemplate();
        testWebhook();
        testExtractCode();
        testBackoff();
        testVer();

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    // ---------- 版本比较 ----------

    static void testVer() {
        eq("norm 去 v", "1.2.3", Ver.norm("v1.2.3"));
        eq("norm 去前缀", "1.2.3", Ver.norm("release-1.2.3"));
        eq("norm 去后缀", "1.2.3", Ver.norm("1.2.3-beta1"));
        eq("norm 空", "", Ver.norm(null));

        eq("1.2 < 1.10", -1, Ver.cmp("1.2", "1.10"));
        eq("相等", 0, Ver.cmp("1.2.0", "1.2"));
        eq("1.2.1 > 1.2.0", 1, Ver.cmp("1.2.1", "1.2.0"));
        eq("新版本判定", true, Ver.newer("1.3", "1.2.9"));
        eq("同版本不算新", false, Ver.newer("1.1", "1.1.0"));
        eq("旧版本不算新", false, Ver.newer("v1.0.9", "1.1"));

        eq("sha256 已知值", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Ver.sha256("abc".getBytes()));
        eq("体积 KB", "2.0 KB", Ver.size(2048));
        eq("brief 截断", true, Ver.brief("一二三四五", 3).endsWith("…"));

        // 清单里的 apk 引用：绝对地址原样，裸文件名接到清单所在目录
        String base = "https://github.com/o/r/releases/latest/download/latest.json";
        eq("绝对地址不变", "http://x/a.apk", Ver.abs(base, "http://x/a.apk"));
        eq("裸文件名", "https://github.com/o/r/releases/latest/download/a.apk",
                Ver.abs(base, "a.apk"));
        eq("./ 前缀", "https://github.com/o/r/releases/latest/download/a.apk",
                Ver.abs(base, "./a.apk"));
        eq("根路径", "https://github.com/o/r/releases/latest/download/a.apk",
                Ver.abs(base, "/a.apk"));
        eq("manifest 目录", "http://192.168.1.2:9099/a.apk",
                Ver.abs("http://192.168.1.2:9099/latest.json", "a.apk"));
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
        String body = capture(c, "10086", "余额不足");

        JSONObject o = new JSONObject(body);
        eq("text msgtype", "text", o.getString("msgtype"));
        String content = o.getJSONObject("text").getString("content");
        eq("默认模板三行", "10086\n余额不足\n设备：158", content);
    }

    static void testDingTalkMarkdown() throws Exception {
        Channel c = new Channel();
        c.name = "ch";
        c.type = "dingtalk";
        c.format = "markdown";
        String body = capture(c, "10086", "验证码 1234");
        JSONObject o = new JSONObject(body);
        eq("markdown msgtype", "markdown", o.getString("msgtype"));
        JSONObject md = o.getJSONObject("markdown");
        eq("markdown 自动标题", "短信 · 158 · 10086", md.getString("title"));
        String text = md.getString("text");
        eq("markdown 正文同样是三行", "10086\n验证码 1234\n设备：158", text);
        eq("markdown 不再加时间脚注", -1, text.indexOf("> "));

        c.title = "告警";
        c.tag = "客厅";
        JSONObject o2 = new JSONObject(capture(c, "10086", "x"));
        eq("自定义标题带标签", "[客厅] 告警", o2.getJSONObject("markdown").getString("title"));
        eq("标签不再混进正文", "10086\nx\n设备：158",
                o2.getJSONObject("markdown").getString("text"));
    }

    // ---------- 转发模板 ----------

    static void testTemplate() throws Exception {
        eq("空模板退回默认三行", "10086\n正文\n设备：158",
                Tpl.render("", "10086", "正文", "158", "", ""));
        eq("自定义模板可用占位符", "158|10086|正文",
                Tpl.render("{device}|{peer}|{body}", "10086", "正文", "158", "", ""));
        eq("tag 占位符", "[客厅]10086", Tpl.render("[{tag}]{peer}", "10086", "x", "158", "客厅", ""));
        eq("time 占位符", "10-01 12:00:00 正文",
                Tpl.render("{time} {body}", "1", "正文", "158", "", "10-01 12:00:00"));
        eq("压掉多余空行与行尾空格", "a\n\nb", Tpl.tidy("a  \n\n\n\nb  \n\n"));
        eq("去掉首尾空行（保留行首缩进）", " 正文", Tpl.tidy("\n\n 正文 \n\n"));

        Channel c = new Channel();
        c.type = "dingtalk";
        c.template = "{peer}: {body}";
        JSONObject o = new JSONObject(capture(c, "10086", "hi"));
        eq("通道模板生效", "10086: hi", o.getJSONObject("text").getString("content"));
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
