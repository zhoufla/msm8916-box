package com.sp970.smsrelay;

import org.json.JSONObject;

/** 一条转发通道。多个通道并行投递，各自独立重试。 */
public final class Channel {

    public String name = "";
    public String type = "webhook";     // webhook | dingtalk
    public String url = "";
    public String secret = "";          // 钉钉加签密钥 SEC…，可空
    public String token = "";           // webhook 的 X-Token，可空
    public String prefix = "【短信】";    // 钉钉关键词模式需要正文带关键词
    public String format = "text";      // 钉钉消息形态：text | markdown
    public String title = "";           // markdown 卡片标题，空则自动生成
    public String tag = "";             // 多设备标签：同一个机器人区分不同设备
    public int maxTries = 10;           // 通道级重试上限（默认与全局一致）
    public boolean enabled = true;

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.name = o.optString("name", "");
        c.type = o.optString("type", "webhook");
        c.url = o.optString("url", "");
        c.secret = o.optString("secret", "");
        c.token = o.optString("token", "");
        c.prefix = o.optString("prefix", "【短信】");
        c.format = o.optString("format", "text");
        c.title = o.optString("title", "");
        c.tag = o.optString("tag", "");
        int mt = o.optInt("maxTries", 10);
        c.maxTries = mt < 1 ? 1 : (mt > 50 ? 50 : mt);
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
            if (isDingTalk() && isMarkdown()) {
                o.put("format", format);
                if (title != null && title.length() > 0) o.put("title", title);
            }
            if (tag != null && tag.length() > 0) o.put("tag", tag);
            if (maxTries != 10) o.put("maxTries", maxTries);
            o.put("enabled", enabled);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public boolean isDingTalk() {
        return "dingtalk".equalsIgnoreCase(type);
    }

    /** 钉钉卡片消息；只有钉钉通道支持，webhook 一律按 JSON 事件投递。 */
    public boolean isMarkdown() {
        return "markdown".equalsIgnoreCase(format) || "card".equalsIgnoreCase(format);
    }

    public boolean valid() {
        return enabled && url != null && url.startsWith("http");
    }

    public String nzTag() {
        return tag == null ? "" : tag.trim();
    }
}
