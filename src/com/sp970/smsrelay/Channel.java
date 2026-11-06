package com.sp970.smsrelay;

import org.json.JSONObject;

/** 一条转发通道。多个通道并行投递，互不影响。 */
public final class Channel {

    public String name = "";
    public String type = "webhook";     // webhook | dingtalk
    public String url = "";
    public String secret = "";          // 钉钉加签密钥 SEC…，可空
    public String token = "";           // webhook 的 X-Token，可空
    public String prefix = "【短信】";    // 钉钉关键词模式需要正文带关键词
    public boolean enabled = true;

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.name = o.optString("name", "");
        c.type = o.optString("type", "webhook");
        c.url = o.optString("url", "");
        c.secret = o.optString("secret", "");
        c.token = o.optString("token", "");
        c.prefix = o.optString("prefix", "【短信】");
        c.enabled = o.optBoolean("enabled", true);
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("type", type);
            o.put("url", url);
            if (secret != null && secret.length() > 0) o.put("secret", secret);
            if (token != null && token.length() > 0) o.put("token", token);
            o.put("prefix", prefix);
            o.put("enabled", enabled);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public boolean isDingTalk() {
        return "dingtalk".equalsIgnoreCase(type);
    }

    public boolean valid() {
        return enabled && url != null && url.startsWith("http");
    }
}
