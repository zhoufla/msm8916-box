package com.sp970.smsrelay;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一条待转发短信。 */
public final class SmsEvent {

    public long id;
    public long ts;
    public String peer = "";
    public String body = "";
    public String code = "";
    public int status;      // 0 待发 / 1 已发 / 2 放弃
    public int tries;
    public String err = "";

    /** 去重用：同一条广播可能重复投递，内容+时间戳相同即视为重复。 */
    public String hash() {
        return MD5.of(ts + "|" + peer + "|" + body);
    }

    public JSONObject toJson(String device) {
        return toJson(device, "");
    }

    /** tag 非空时带上，同一 webhook 收多台设备时用来区分。 */
    public JSONObject toJson(String device, String tag) {
        JSONObject o = new JSONObject();
        try {
            o.put("device", device == null ? "" : device);
            if (tag != null && tag.length() > 0) o.put("tag", tag);
            o.put("peer", peer == null ? "" : peer);
            o.put("body", body == null ? "" : body);
            o.put("code", code == null ? "" : code);
            o.put("ts", ts);
            o.put("iso", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ts)));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static final Pattern CODE_CTX = Pattern.compile(
            "(?:验证码|验证密码|动态码|动态密码|校验码|确认码|激活码|提取码|code)[^0-9]{0,10}(\\d{4,8})",
            Pattern.CASE_INSENSITIVE);

    /**
     * 验证码提取，只认上下文明确的形态。
     * 宁可返回空，也不要把普通数字（订单号、余额）当成验证码。
     */
    public static String extractCode(String body) {
        if (body == null || body.length() == 0) return "";
        Matcher m = CODE_CTX.matcher(body);
        if (m.find()) return m.group(1);
        return "";
    }
}
