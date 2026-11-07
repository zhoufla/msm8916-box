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
        testRouterHitAndPriority();
        testRouterStop();
        testRouterDrop();
        testRouterUnmatched();
        testRouterTimeWindow();
        testRouterValidate();
        testRouterRoundTrip();
        testRouterParseLenient();
        testSenderRuleTemplate();
        testTokenCors();
        testAlerts();
        testDdns();
        testCodeTpl();
        testDdnsRecords();

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    // ---------- DDNS 纯逻辑 ----------

    static void testDdns() {
        java.util.List<String> addrs = new java.util.ArrayList<String>();
        addrs.add("fe80::1234%rmnet0");
        addrs.add("fd00::1");
        addrs.add("240e:5678::abcd");
        eq("v6 跳过链路本地与 ULA", "240e:5678::abcd", DdnsLogic.pickGlobalV6(addrs));
        eq("v6 空列表", "", DdnsLogic.pickGlobalV6(new java.util.ArrayList<String>()));
        eq("v6 null", "", DdnsLogic.pickGlobalV6(null));
        java.util.List<String> only4 = new java.util.ArrayList<String>();
        only4.add("192.168.1.5");
        eq("v6 忽略 IPv4", "", DdnsLogic.pickGlobalV6(only4));
        eq("family 默认都要", Boolean.TRUE,
                DdnsLogic.wantV4("both") && DdnsLogic.wantV6("both"));
        eq("family null 都要", Boolean.TRUE,
                DdnsLogic.wantV4(null) && DdnsLogic.wantV6(null));
        eq("family=v4 只 v4", Boolean.TRUE,
                DdnsLogic.wantV4("v4") && !DdnsLogic.wantV6("v4"));
        eq("family=v6 只 v6", Boolean.TRUE,
                !DdnsLogic.wantV4("v6") && DdnsLogic.wantV6("v6"));
        eq("family 未知值归 both", Boolean.TRUE,
                DdnsLogic.wantV4("zap") && DdnsLogic.wantV6("zap"));

        String u = DdnsLogic.duckUrl("mybox.duckdns.org", "tk", "1.2.3.4", "");
        eq("duck 后缀剥离", true, u.contains("domains=mybox&"));
        eq("duck v4", true, u.contains("ip=1.2.3.4"));
        eq("duck 无 v6 参", false, u.contains("ip6="));
        String u2 = DdnsLogic.duckUrl("mybox", "tk", "", "240e::1");
        eq("duck v6", true, u2.contains("ip6=240e%3A%3A1") || u2.contains("ip6=240e::1"));
        eq("duck 无 v4 参", false, u2.contains("&ip="));
        eq("duckOk OK", true, DdnsLogic.duckOk("OK\n240e::1"));
        eq("duckOk KO", false, DdnsLogic.duckOk("KO"));
        eq("duckOk null", false, DdnsLogic.duckOk(null));

        eq("pairKey", "1.2.3.4|240e::1", DdnsLogic.pairKey("1.2.3.4", "240e::1"));
        eq("changed 首次", true, DdnsLogic.ipChanged("", "1.2.3.4", "240e::1"));
        eq("changed 未变", false, DdnsLogic.ipChanged("1.2.3.4|240e::1", "1.2.3.4", "240e::1"));
        eq("changed v4 变", true, DdnsLogic.ipChanged("1.2.3.4|", "1.2.3.5", ""));
        eq("changed 全空", false, DdnsLogic.ipChanged("1.2.3.4|", "", ""));

        eq("cfOk true", true, DdnsLogic.cfOk("{\"success\":true,\"result\":[]}"));
        eq("cfOk false", false, DdnsLogic.cfOk("{\"success\":false}"));
        eq("cfOk 坏 JSON", false, DdnsLogic.cfOk("oops"));
        eq("cfZoneId", "abc123", DdnsLogic.cfZoneId(
                "{\"success\":true,\"result\":[{\"id\":\"abc123\",\"name\":\"e.com\"}]}"));
        eq("cfZoneId 空", "", DdnsLogic.cfZoneId("{\"success\":true,\"result\":[]}"));
        eq("cfRecordId", "rec9", DdnsLogic.cfRecordId(
                "{\"success\":true,\"result\":[{\"id\":\"rec9\",\"type\":\"A\"}]}"));
        eq("cfError", "10000 Invalid", DdnsLogic.cfError(
                "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Invalid\"}]}"));
        eq("cfError 空", "", DdnsLogic.cfError("{\"success\":true}"));
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

    /** 起一个一次性 HTTP 服务，收下 Sender 发的 body（无规则模板覆盖）。 */
    static String capture(final Channel ch, String peer, String text) throws Exception {
        return captureD(ch, peer, text, null, "");
    }

    /** ruleTpl：模拟规则命中的 template 覆盖（null = 无覆盖）。 */
    static String capture(final Channel ch, String peer, String text, final String ruleTpl) throws Exception {
        return captureD(ch, peer, text, ruleTpl, "");
    }

    /** code：模拟带验证码的短信（验证码前置默认模板用）。 */
    static String captureD(final Channel ch, String peer, String text, final String ruleTpl, final String code) throws Exception {
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
        d.code = code;
        d.template = ruleTpl == null ? "" : ruleTpl;
        String err = Sender.send(ch, d, "158");
        eq("发送返回成功", null, err);
        t.join(3000);
        ss.close();
        return got[0] == null ? "{}" : got[0];
    }

    // ---------- 验证码模板与多记录 DDNS ----------

    static void testCodeTpl() throws Exception {
        eq("code 前置模板", "【123456】10086\n正文",
                Tpl.render("【{code}】{peer}\n{body}", "10086", "正文", "123456", "dev", "tag", "t"));
        eq("code 空清位", "10086", Tpl.render("{code}{peer}", "10086", "b", "", "dev", "tag", "t"));
        eq("6 参委托 code 空", "10086", Tpl.render("{peer}{code}", "10086", "b", "dev", "tag", "t"));

        Channel ch = new Channel();
        ch.name = "ding";
        ch.type = "dingtalk";
        ch.enabled = true;
        String body = captureD(ch, "10086", "您的验证码 123456", null, "123456");
        eq("默认验证码前置", true, body.contains("\"content\":\"【123456】10086"));
        ch.template = "code={code}";
        body = captureD(ch, "10086", "正文", null, "654321");
        eq("通道模板 {code}", true, body.contains("code=654321"));
        ch.template = "";
        body = captureD(ch, "10086", "普通短信", null, "");
        eq("无验证码用默认模板", true, body.contains("普通短信") && !body.contains("【】"));
    }

    static void testDdnsRecords() {
        eq("iface 归一", "rmnet0", DdnsLogic.normIface(" rmnet0 "));
        eq("iface 非法归空", "", DdnsLogic.normIface("bad iface!"));
        eq("iface null 归空", "", DdnsLogic.normIface(null));
        eq("iface 20 位通过", true, DdnsLogic.normIface("a2345678901234567890").length() == 20);
        eq("iface 21 位拒绝", "", DdnsLogic.normIface("a23456789012345678901"));
        eq("family v4", "v4", DdnsLogic.normFamily("v4"));
        eq("family v6", "v6", DdnsLogic.normFamily("v6"));
        eq("family 非法归 both", "both", DdnsLogic.normFamily("zap"));
        eq("family null 归 both", "both", DdnsLogic.normFamily(null));
        eq("wantV4/wantV6 both", Boolean.TRUE,
                DdnsLogic.wantV4("both") && DdnsLogic.wantV6("both"));
    }

    // ---------- 其他纯逻辑 ----------

    static void testExtractCode() {
        eq("验证码 1234", "1234", SmsEvent.extractCode("【银行】您的验证码是 1234，5 分钟内有效"));
        eq("动态码", "554433", SmsEvent.extractCode("动态码:554433 请勿泄露"));
        eq("code 关键字", "998877", SmsEvent.extractCode("Your login code 998877 expires soon"));
        eq("验证密码 变体", "756104", SmsEvent.extractCode("【中国移动】(验证密码)756104。尊敬的客户"));
        eq("动态密码 变体", "330011", SmsEvent.extractCode("【银行】动态密码 330011，切勿告知他人"));
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

    // ---------- 规则引擎（P0-A） ----------

    static SmsEvent ev(String peer, String body) {
        SmsEvent e = new SmsEvent();
        e.peer = peer;
        e.body = body;
        e.ts = 1793950857000L;
        e.code = SmsEvent.extractCode(body);
        return e;
    }

    static java.util.List<String> chNames3() {
        java.util.List<String> all = new java.util.ArrayList<String>();
        all.add("otp");
        all.add("ops");
        all.add("log");
        return all;
    }

    static Router.Result route(String rulesJson, String peer, String body,
                               String unmatched, long now) throws Exception {
        org.json.JSONArray arr = new org.json.JSONArray(rulesJson);
        java.util.List<Router.Rule> rules = Router.parseRules(arr);
        return Router.route(ev(peer, body), rules, chNames3(), unmatched, now);
    }

    static String targetsOf(Router.Result r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.targets.size(); i++) {
            Router.Target t = r.targets.get(i);
            if (sb.length() > 0) sb.append(",");
            sb.append(t.ch).append("@").append(t.prio);
        }
        return sb.toString();
    }

    static void testRouterHitAndPriority() throws Exception {
        String rules = "[" +
                "{\"name\":\"a\",\"peer\":[\"^10086\"],\"channels\":[\"ops\"],\"priority\":10}," +
                "{\"name\":\"b\",\"peer\":[\"10086\"],\"channels\":[\"otp\",\"ops\"],\"priority\":100}]";
        Router.Result r = route(rules, "10086", "x", "all", 0);
        eq("命中顺序", "[a, b]", r.matched.toString());
        eq("主规则", "a", r.rule);
        eq("并集去重取最高优先级", "ops@100,otp@100", targetsOf(r));
        eq("不投未命中通道", false, targetsOf(r).contains("log"));
        Router.Result r2 = route(rules, "10010", "x", "all", 0);
        eq("未命中走默认全投", "otp@0,ops@0,log@0", targetsOf(r2));
        eq("未命中 rule=null", null, r2.rule);
    }

    static void testRouterStop() throws Exception {
        String rules = "[" +
                "{\"name\":\"a\",\"peer\":[\"^955\"],\"channels\":[\"ops\"],\"stop\":true}," +
                "{\"name\":\"b\",\"channels\":[\"otp\"]}]";
        Router.Result r = route(rules, "95588", "x", "all", 0);
        eq("stop 后不再看后续", "ops@0", targetsOf(r));
        eq("matched 只有一条", 1, r.matched.size());
        Router.Result r2 = route(rules, "10086", "x", "all", 0);
        eq("a 不命中 b 继续兜底", "otp@0", targetsOf(r2));
    }

    static void testRouterDrop() throws Exception {
        String rules = "[" +
                "{\"name\":\"ads\",\"body\":[\"退订|优惠\"],\"action\":\"drop\"}," +
                "{\"name\":\"b\",\"channels\":[\"otp\"]}]";
        Router.Result r = route(rules, "10086", "回复TD退订", "all", 0);
        eq("drop 命中", true, r.drop);
        eq("drop 记规则名", "ads", r.rule);
        eq("drop 无投递目标", 0, r.targets.size());
        Router.Result r2 = route(rules, "10086", "验证码 123456", "all", 0);
        eq("不匹配 drop 正常转发", "otp@0", targetsOf(r2));
        eq("不匹配不 drop", false, r2.drop);
    }

    static void testRouterUnmatched() throws Exception {
        eq("无规则默认全投", "otp@0,ops@0,log@0",
                targetsOf(route("[]", "1", "x", "all", 0)));
        eq("rulesDefault=drop 丢弃", true, route("[]", "1", "x", "drop", 0).drop);
        // 规则引用的通道在运行期可能已被删除：按规则记录目标，投递时服务侧报「通道已删除」，
        // 不静默改投其他通道（避免把银行短信误投到无关群）。
        String rules = "[{\"name\":\"a\",\"peer\":[\"10086\"],\"channels\":[\"gone\"]}]";
        eq("规则通道已删除仍按规则记录", "gone@0",
                targetsOf(route(rules, "10086", "x", "all", 0)));
    }

    static void testRouterTimeWindow() throws Exception {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.JULY, 6, 3, 30, 0);
        long t330 = c.getTimeInMillis();          // 03:30
        c.set(2026, java.util.Calendar.JULY, 6, 5, 30, 0);
        long t530 = c.getTimeInMillis();          // 05:30
        c.set(2026, java.util.Calendar.JULY, 6, 23, 30, 0);
        long t2330 = c.getTimeInMillis();         // 23:30

        eq("时段内命中", true, Router.inWindow(t330, 120, 240));
        eq("时段外不命中", false, Router.inWindow(t530, 120, 240));
        eq("跨零点凌晨命中", true, Router.inWindow(t330, 23 * 60, 240));
        eq("跨零点深夜命中", true, Router.inWindow(t2330, 23 * 60, 240));
        eq("跨零点白天不命中", false, Router.inWindow(t530, 23 * 60, 240));
        eq("整日时段", true, Router.inWindow(t530, 0, 1439));

        String rules = "[{\"name\":\"night\",\"from\":\"23:00\",\"to\":\"04:00\",\"channels\":[\"ops\"]}]";
        eq("时段规则凌晨生效", "ops@0", targetsOf(route(rules, "1", "x", "all", t330)));
        eq("时段规则白天不生效", "otp@0,ops@0,log@0", targetsOf(route(rules, "1", "x", "all", t530)));
        eq("时段规则深夜生效", "ops@0", targetsOf(route(rules, "1", "x", "all", t2330)));
    }

    static void eqPass(String name, String rulesJson) {
        try {
            Router.validateRulesJson(new org.json.JSONArray(rulesJson), chNames3());
            eq(name, "OK", "OK");
        } catch (IllegalArgumentException e) {
            eq(name, "OK", "IAE:" + e.getMessage());
        }
    }

    static void eqIAE(String name, String rulesJson) {
        try {
            Router.validateRulesJson(new org.json.JSONArray(rulesJson), chNames3());
            eq(name, "IAE", "no");
        } catch (IllegalArgumentException e) {
            eq(name, "IAE", "IAE");
        }
    }

    static String manyRules() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"r").append(i).append("\",\"channels\":[\"otp\"]}");
        }
        return sb.append(']').toString();
    }

    static void testRouterValidate() {
        eqPass("合法规则",
                "[{\"name\":\"a\",\"peer\":[\"^106\\\\d+$\"],\"channels\":[\"otp\"],\"priority\":100}]");
        eqPass("drop 规则可无 channels", "[{\"name\":\"d\",\"action\":\"drop\"}]");
        eqPass("时段成对合法",
                "[{\"name\":\"n\",\"from\":\"23:00\",\"to\":\"04:00\",\"channels\":[\"ops\"]}]");
        eqIAE("缺 name", "[{\"peer\":[\"a\"],\"channels\":[\"otp\"]}]");
        eqIAE("重名", "[{\"name\":\"a\",\"channels\":[\"otp\"]},{\"name\":\"a\",\"channels\":[\"ops\"]}]");
        eqIAE("坏正则", "[{\"name\":\"a\",\"peer\":[\"([\"],\"channels\":[\"otp\"]}]");
        eqIAE("转发规则 channels 为空", "[{\"name\":\"a\",\"peer\":[\"x\"]}]");
        eqIAE("引用不存在通道", "[{\"name\":\"a\",\"channels\":[\"nope\"]}]");
        eqIAE("priority 越界", "[{\"name\":\"a\",\"channels\":[\"otp\"],\"priority\":1001}]");
        eqIAE("action 非法", "[{\"name\":\"a\",\"action\":\"zap\",\"channels\":[\"otp\"]}]");
        eqIAE("from 无 to", "[{\"name\":\"a\",\"from\":\"23:00\",\"channels\":[\"otp\"]}]");
        eqIAE("from 格式错", "[{\"name\":\"a\",\"from\":\"25:00\",\"to\":\"04:00\",\"channels\":[\"otp\"]}]");
        eqIAE("超过 50 条", manyRules());
    }

    static void testRouterRoundTrip() throws Exception {
        String json = "[{\"name\":\"otp\",\"enabled\":false,\"peer\":[\"^106\\\\d+$\",\"^955\"]," +
                "\"body\":[\"验证码|code\"],\"channels\":[\"otp\",\"ops\"],\"priority\":100," +
                "\"stop\":true,\"template\":\"{peer}: {body}\",\"from\":\"23:00\",\"to\":\"04:00\"}]";
        org.json.JSONArray arr = new org.json.JSONArray(json);
        java.util.List<Router.Rule> r1 = Router.parseRules(arr);
        eq("往返 1 条", 1, r1.size());
        org.json.JSONArray out = new org.json.JSONArray();
        out.put(Router.ruleToJson(r1.get(0)));
        java.util.List<Router.Rule> r2 = Router.parseRules(out);
        Router.Rule a = r1.get(0), b = r2.get(0);
        eq("往返 name", a.name, b.name);
        eq("往返 enabled", a.enabled, b.enabled);
        eq("往返 peerRaw", a.peerRaw, b.peerRaw);
        eq("往返 bodyRaw", a.bodyRaw, b.bodyRaw);
        eq("往返 channels", a.channels, b.channels);
        eq("往返 priority", a.priority, b.priority);
        eq("往返 stop", a.stop, b.stop);
        eq("往返 template", a.template, b.template);
        eq("往返 from/to", a.fromRaw + "/" + a.toRaw, b.fromRaw + "/" + b.toRaw);
        eq("往返正则仍可匹配", true, b.peer.get(0).matcher("10690000000").find());
    }

    static void testRouterParseLenient() throws Exception {
        org.json.JSONArray arr = new org.json.JSONArray(
                "[notobject,{\"peer\":[\"([\"],\"channels\":[\"otp\"]}," +
                        "{\"name\":\"ok\",\"peer\":\"10086\",\"channels\":[\"ops\"]}]");
        java.util.List<Router.Rule> rs = Router.parseRules(arr);
        eq("非对象条目被跳过", 2, rs.size());
        eq("坏正则不进编译列表", 0, rs.get(0).peer.size());
        eq("坏正则规则仍保留通道", "[otp]", rs.get(0).channels.toString());
        eq("单字符串 peer 兼容", "10086", rs.get(1).peerRaw.get(0));
        eq("单字符串编译成 1 个正则", 1, rs.get(1).peer.size());
        java.util.List<Router.Rule> rs2 = Router.parseRules(new org.json.JSONArray("[{\"channels\":[\"otp\"]}]"));
        eq("无名规则默认命名", "rule1", rs2.get(0).name);
    }

    static void testSenderRuleTemplate() throws Exception {
        Channel c = new Channel();
        c.name = "ch";
        c.type = "dingtalk";
        c.template = "{body}（通道模板）";
        String body = capture(c, "10086", "hi", "{peer}: {body}");
        JSONObject o = new JSONObject(body);
        eq("规则模板覆盖通道模板", "10086: hi", o.getJSONObject("text").getString("content"));
        String body2 = capture(c, "10086", "hi", null);
        JSONObject o2 = new JSONObject(body2);
        eq("无规则模板用通道模板", "hi（通道模板）", o2.getJSONObject("text").getString("content"));
    }

    // ---------- 断言 ----------

    static void testTokenCors(){
        eq("token: 正确匹配", Boolean.TRUE, Http.tokenMatches("abc123","abc123"));
        eq("token: 未配置拒绝", Boolean.TRUE, !Http.tokenMatches("","abc123"));
        eq("token: null 配置拒绝", Boolean.TRUE, !Http.tokenMatches(null,"abc123"));
        eq("token: 空 presented 拒绝", Boolean.TRUE, !Http.tokenMatches("abc123",""));
        eq("token: 错误值拒绝", Boolean.TRUE, !Http.tokenMatches("abc123","abc124"));
        eq("token: 短前缀拒绝", Boolean.TRUE, !Http.tokenMatches("abc123","abc"));
        java.util.List<String> raw=new java.util.ArrayList<String>();
        raw.add(" http://a.local:8123 ");raw.add("");raw.add(null);raw.add("*");raw.add("http://a.local:8123");
        java.util.List<String> n=Http.normalizeCors(raw);
        eq("cors: 清理去重", "[http://a.local:8123, *]", n.toString());
        eq("cors: 白名单命中", Boolean.TRUE, Http.corsAllowed(n,"http://a.local:8123"));
        eq("cors: 星号放行任意", Boolean.TRUE, Http.corsAllowed(n,"http://x.local"));
        java.util.List<String> noStar=java.util.Arrays.asList("http://a.local:8123");
        eq("cors: 未命中拒绝", Boolean.TRUE, !Http.corsAllowed(noStar,"http://evil.local"));
        eq("cors: 空列表关闭", Boolean.TRUE, !Http.corsAllowed(new java.util.ArrayList<String>(),"http://a.local:8123"));
        eq("cors: 无 Origin 头拒绝", Boolean.TRUE, !Http.corsAllowed(n,null));
        eq("cors: null 输入", "[]", Http.normalizeCors(null).toString());
    }

    static void testAlerts(){
        long now=1000000L, remind=3600000L;
        eq("alert: 首次异常即推", "fire", AlertLogic.decide(false,0,now,true,remind));
        eq("alert: 持续异常未到提醒间隔不动", "", AlertLogic.decide(true,now-remind+1000,now,true,remind));
        eq("alert: 持续异常到点提醒", "remind", AlertLogic.decide(true,now-remind,now,true,remind));
        eq("alert: 提醒间隔边界（>=）", "remind", AlertLogic.decide(true,now-remind-1,now,true,remind));
        eq("alert: 异常态恢复即推", "recover", AlertLogic.decide(true,now,now,false,remind));
        eq("alert: 未异常不推", "", AlertLogic.decide(false,0,now,false,remind));
        eq("alert: 电量解析", 87, AlertLogic.parseBatteryLevel("Current Battery Service state:\n  level: 87\n  voltage: 3900\n"));
        eq("alert: 电量缺失", -1, AlertLogic.parseBatteryLevel("no battery info\n"));
        eq("alert: 电量 null", -1, AlertLogic.parseBatteryLevel(null));
        eq("alert: 充电中", Boolean.TRUE, AlertLogic.parseBatteryPowered("  AC powered: true\n  USB powered: false\n"));
        eq("alert: 未充电", Boolean.FALSE, AlertLogic.parseBatteryPowered("  AC powered: false\n  USB powered: false\n"));
        eq("alert: 电量输出 null", Boolean.FALSE, AlertLogic.parseBatteryPowered(null));
    }

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
