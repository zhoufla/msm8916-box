package com.sp970.smsrelay;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 按通道类型投递：通用 JSON webhook / 钉钉机器人。 */
public final class Sender {

    /** @return null 表示成功，否则返回错误描述。 */
    public static String send(Channel ch, Deliver d, String device) {
        HttpURLConnection conn = null;
        try {
            String url = ch.url;
            byte[] payload;

            if (ch.isDingTalk()) {
                String tagPart = ch.nzTag().length() > 0 ? "[" + ch.nzTag() + "] " : "";
                // 正文按通道模板渲染，默认就是三行：发件人 / 内容 / 设备名
                String content = Tpl.render(ch.nzTemplate(), d.peer, d.body, device,
                        ch.nzTag(), isoTime(d.ts));
                JSONObject o = new JSONObject();
                if (ch.isMarkdown()) {
                    // 卡片消息：标题优先用通道配置，缺省用「设备 + 号码」
                    String titleText = nz(ch.title).trim();
                    if (titleText.length() == 0) {
                        titleText = "短信 · " + nz(device) + " · " + nz(d.peer);
                    }
                    JSONObject md = new JSONObject();
                    md.put("title", tagPart + titleText);
                    md.put("text", content);
                    o.put("msgtype", "markdown");
                    o.put("markdown", md);
                } else {
                    o.put("msgtype", "text");
                    JSONObject t = new JSONObject();
                    t.put("content", content);
                    o.put("text", t);
                }
                payload = o.toString().getBytes("UTF-8");
                if (ch.secret != null && ch.secret.length() > 0) {
                    long ts = System.currentTimeMillis();
                    String sep = url.indexOf('?') >= 0 ? "&" : "?";
                    url = url + sep + "timestamp=" + ts + "&sign="
                            + URLEncoder.encode(dingTalkSign(ts, ch.secret), "UTF-8");
                }
            } else {
                payload = d.toEvent().toJson(device, ch.nzTag()).toString().getBytes("UTF-8");
            }

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "sp970-smsrelay/1.0");
            if (ch.token != null && ch.token.length() > 0) {
                conn.setRequestProperty("X-Token", ch.token);
            }

            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code < 200 || code >= 300) {
                return "HTTP " + code + " " + cut(resp);
            }
            if (ch.isDingTalk()) {
                try {
                    JSONObject r = new JSONObject(resp);
                    int ec = r.optInt("errcode", 0);
                    if (ec != 0) return "errcode=" + ec + " " + r.optString("errmsg");
                } catch (Throwable ignored) {
                }
            }
            return null;
        } catch (Throwable t) {
            return cut(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 钉钉加签：HmacSHA256(key=secret, data=timestamp+"\n"+secret) 再 base64。 */
    private static String dingTalkSign(long ts, String secret) throws Exception {
        String s = ts + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] raw = mac.doFinal(s.getBytes("UTF-8"));
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String isoTime(long ts) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(ts > 0 ? ts : System.currentTimeMillis()));
    }

    private static String cut(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
