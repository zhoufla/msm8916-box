package com.sp970.smsrelay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一条待投递记录：某条短信 → 某个通道。 */
public final class Deliver {

    public long dlId;
    public long evId;
    public String ch = "";
    public long ts;
    public String peer = "";
    public String body = "";
    public String code = "";
    public int tries;
    public String err = "";
    /** 规则引擎：本条投递的优先级与模板覆盖（due() 从 dl 表带出）。 */
    public int prio;
    public String template = "";

    public SmsEvent toEvent() {
        SmsEvent e = new SmsEvent();
        e.id = evId;
        e.ts = ts;
        e.peer = peer;
        e.body = body;
        e.code = code;
        return e;
    }

    /** 配置里删掉的通道名，用一次就记下来，方便界面提示。 */
    public static List<String> names(List<Channel> chs) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < chs.size(); i++) out.add(chs.get(i).name);
        return out;
    }

    public static JSONArray toJsonArray(List<Channel> chs) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < chs.size(); i++) a.put(chs.get(i).toJson());
        return a;
    }
}
