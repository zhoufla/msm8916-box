package com.sp970.smsrelay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 短信规则引擎（P0-A）。
 *
 * 规则按配置数组顺序求值；一条规则内字段 AND、同字段列表内 OR：
 *   peer      发件人正则列表（空 = 不限；find() 部分匹配，要整串匹配自己加 ^ $）
 *   body      正文正则列表（空 = 不限）
 *   from/to   生效时段 "HH:mm"，支持跨零点（如 23:00-07:00）
 * 命中后：
 *   action=relay（默认）把规则 channels 里的通道并入投递目标（同通道取最高 priority），
 *        template 非空时覆盖这些通道的钉钉转发模板（webhook 载荷不受模板影响）；
 *   action=drop 整条消息丢弃——仍入库，ev.rule 记规则名，面板可人工重发；
 *   stop=true 命中后不再看后续规则。
 * 所有规则都没攒下任何投递目标（没命中，或命中的规则没配可用通道）时按 rulesDefault：
 *   all（默认，向后兼容：投递到全部通道）/ drop。
 *
 * 调用点约定：自动链路（SmsReceiver / Inbox 补收）走 route()；
 * 人工动作（面板注入、系统收件箱单条转发、测试注入）走 routeAll() 不经规则。
 *
 * 纯 JDK + org.json，不 import android.*，可进 run-tests.sh 桌面测试。
 */
public final class Router {

    private Router() {
    }

    /** 规则命中后的一个投递目标。 */
    public static final class Target {
        public final String ch;
        public final int prio;
        public final String template;   // null / 空 = 用通道自己的模板

        Target(String ch, int prio, String template) {
            this.ch = ch;
            this.prio = prio;
            this.template = template;
        }
    }

    /** 路由结果。 */
    public static final class Result {
        public final boolean drop;
        /** 主命中规则名；默认路径（未命中）为 null（drop 与命中各赋一次，非 final）。 */
        public String rule;
        /** 命中的全部规则名（按求值顺序），丢弃规则也记在内。 */
        public final List<String> matched = new ArrayList<String>();
        public final List<Target> targets = new ArrayList<Target>();

        private Result(boolean drop, String rule) {
            this.drop = drop;
            this.rule = rule;
        }
    }

    /** 一条规则：rawXxx 保留原文（配置往返用），Pattern 是编译结果。 */
    public static final class Rule {
        public String name = "";
        public boolean enabled = true;
        public List<String> peerRaw = new ArrayList<String>();
        public List<String> bodyRaw = new ArrayList<String>();
        public List<Pattern> peer = new ArrayList<Pattern>();
        public List<Pattern> body = new ArrayList<Pattern>();
        public List<String> channels = new ArrayList<String>();
        public int priority = 0;
        public boolean stop = false;
        public boolean dropAction = false;    // action = drop
        public String template = "";
        public int fromMin = -1;              // 时段起点（0-1439 分钟），-1 = 不限
        public int toMin = -1;
        public String fromRaw = "";
        public String toRaw = "";
    }

    public static final int MAX_RULES = 50;
    public static final int MAX_PRIORITY = 1000;

    // ---------- 解析 ----------

    /** 宽松解析：坏正则/坏条目直接跳过（与 AppConfig.parse 其它段同一风格）。 */
    public static List<Rule> parseRules(JSONArray arr) {
        List<Rule> out = new ArrayList<Rule>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < MAX_RULES; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Rule r = new Rule();
            r.name = o.optString("name", "").trim();
            if (r.name.length() == 0) r.name = "rule" + (i + 1);
            r.enabled = o.optBoolean("enabled", true);
            r.dropAction = "drop".equals(o.optString("action", "relay"));
            r.priority = clampPriority(o.optInt("priority", 0));
            r.stop = o.optBoolean("stop", false);
            r.template = o.optString("template", "");
            fillPatterns(o, "peer", r.peerRaw, r.peer);
            fillPatterns(o, "body", r.bodyRaw, r.body);
            fillChannels(o, r.channels);
            r.fromRaw = o.optString("from", "").trim();
            r.toRaw = o.optString("to", "").trim();
            r.fromMin = r.fromRaw.length() == 0 ? -1 : parseHHmm(r.fromRaw);
            r.toMin = r.toRaw.length() == 0 ? -1 : parseHHmm(r.toRaw);
            if (r.fromMin < 0 || r.toMin < 0) {   // 时段残缺 = 不限时段
                r.fromMin = -1;
                r.toMin = -1;
                r.fromRaw = "";
                r.toRaw = "";
            }
            out.add(r);
        }
        return out;
    }

    /** 规则 → 配置 JSON（保留原始正则串）。 */
    public static JSONObject ruleToJson(Rule r) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", r.name);
            if (!r.enabled) o.put("enabled", false);
            if (r.dropAction) o.put("action", "drop");
            if (r.priority != 0) o.put("priority", r.priority);
            if (r.stop) o.put("stop", true);
            if (r.template != null && r.template.length() > 0) o.put("template", r.template);
            if (r.peerRaw.size() > 0) o.put("peer", new JSONArray(r.peerRaw));
            if (r.bodyRaw.size() > 0) o.put("body", new JSONArray(r.bodyRaw));
            if (r.channels.size() > 0) o.put("channels", new JSONArray(r.channels));
            if (r.fromMin >= 0) {
                o.put("from", r.fromRaw);
                o.put("to", r.toRaw);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void fillPatterns(JSONObject o, String key, List<String> raws, List<Pattern> pats) {
        JSONArray a = o.optJSONArray(key);
        if (a != null) {
            for (int i = 0; i < a.length(); i++) addPattern(a.optString(i, ""), raws, pats);
        } else {
            addPattern(o.optString(key, ""), raws, pats);   // 兼容单字符串写法
        }
    }

    private static void addPattern(String s, List<String> raws, List<Pattern> pats) {
        s = s == null ? "" : s.trim();
        if (s.length() == 0) return;
        try {
            pats.add(Pattern.compile(s));
            raws.add(s);
        } catch (PatternSyntaxException ignored) {
            // 坏正则跳过；保存路径有 validateRulesJson 拦着，这里只防手改配置把服务搞挂
        }
    }

    private static void fillChannels(JSONObject o, List<String> out) {
        JSONArray chs = o.optJSONArray("channels");
        if (chs != null) {
            for (int j = 0; j < chs.length(); j++) {
                String c = chs.optString(j, "").trim();
                if (c.length() > 0 && !out.contains(c)) out.add(c);
            }
        } else {
            String[] parts = o.optString("channels", "").split("[,，;；\\s]+");
            for (int j = 0; j < parts.length; j++) {
                String c = parts[j].trim();
                if (c.length() > 0 && !out.contains(c)) out.add(c);
            }
        }
    }

    /** 严格校验（面板保存用）：坏正则/重名/越界直接抛 IllegalArgumentException。 */
    public static void validateRulesJson(JSONArray arr, List<String> channelNames) {
        if (arr == null) return;
        if (arr.length() > MAX_RULES) {
            throw new IllegalArgumentException("规则最多 " + MAX_RULES + " 条，当前 " + arr.length() + " 条");
        }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("第 " + (i + 1) + " 条规则必须是对象");
            String name = o.optString("name", "").trim();
            if (name.length() == 0) throw new IllegalArgumentException("第 " + (i + 1) + " 条规则缺少 name");
            if (names.contains(name)) throw new IllegalArgumentException("规则名重复：" + name);
            names.add(name);

            String action = o.optString("action", "relay");
            if (!"relay".equals(action) && !"drop".equals(action)) {
                throw new IllegalArgumentException("规则 " + name + " 的 action 只能是 relay 或 drop");
            }
            int prio = o.optInt("priority", 0);
            if (prio < 0 || prio > MAX_PRIORITY) {
                throw new IllegalArgumentException("规则 " + name + " 的 priority 需在 0-" + MAX_PRIORITY);
            }
            checkPatterns(o, "peer", name);
            checkPatterns(o, "body", name);

            boolean hasFrom = o.has("from") && o.optString("from", "").trim().length() > 0;
            boolean hasTo = o.has("to") && o.optString("to", "").trim().length() > 0;
            if (hasFrom != hasTo) {
                throw new IllegalArgumentException("规则 " + name + " 的 from/to 必须成对出现");
            }
            if (hasFrom) {
                if (parseHHmm(o.optString("from", "")) < 0 || parseHHmm(o.optString("to", "")) < 0) {
                    throw new IllegalArgumentException("规则 " + name + " 的 from/to 需为 HH:mm（如 23:00）");
                }
            }

            List<String> chs = new ArrayList<String>();
            fillChannels(o, chs);
            if ("relay".equals(action) && chs.isEmpty()) {
                throw new IllegalArgumentException("规则 " + name + " 是转发规则但 channels 为空（丢弃用 action:\"drop\"）");
            }
            if (channelNames != null) {
                for (int j = 0; j < chs.size(); j++) {
                    if (!channelNames.contains(chs.get(j))) {
                        throw new IllegalArgumentException("规则 " + name + " 引用了不存在的通道：" + chs.get(j));
                    }
                }
            }
        }
    }

    private static void checkPatterns(JSONObject o, String key, String name) {
        JSONArray a = o.optJSONArray(key);
        int n = a != null ? a.length() : (o.optString(key, "").trim().length() > 0 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            String s = a != null ? a.optString(i, "") : o.optString(key, "");
            try {
                Pattern.compile(s.trim());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("规则 " + name + " 的 " + key + " 正则无效："
                        + s + "（" + e.getMessage() + "）");
            }
        }
    }

    /** "HH:mm" → 当天分钟数；格式不对返回 -1。 */
    static int parseHHmm(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (!s.matches("\\d{1,2}:\\d{2}")) return -1;
        int i = s.indexOf(':');
        int h = Integer.parseInt(s.substring(0, i));
        int m = Integer.parseInt(s.substring(i + 1));
        if (h > 23 || m > 59) return -1;
        return h * 60 + m;
    }

    private static int clampPriority(int p) {
        if (p < 0) return 0;
        if (p > MAX_PRIORITY) return MAX_PRIORITY;
        return p;
    }

    // ---------- 路由 ----------

    /** 自动链路入口（当前时刻求值）。 */
    public static Result route(SmsEvent e, List<Rule> rules, List<String> allChannels, String unmatched) {
        return route(e, rules, allChannels, unmatched, System.currentTimeMillis());
    }

    /**
     * 规则求值。now 参数独立出来是为了桌面测试可以固定时刻验证时段规则。
     */
    public static Result route(SmsEvent e, List<Rule> rules, List<String> allChannels,
                               String unmatched, long now) {
        Result res = new Result(false, null);
        for (int i = 0; rules != null && i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (!r.enabled) continue;
            if (!matchRule(r, e, now)) continue;
            res.matched.add(r.name);
            if (r.dropAction) {
                Result drop = new Result(true, r.name);
                drop.matched.addAll(res.matched);
                return drop;
            }
            if (res.rule == null) res.rule = r.name;
            mergeTargets(res.targets, r.channels, r.priority, r.template);
            if (r.stop) break;
        }
        if (res.targets.isEmpty()) {
            // 没攒下任何目标（没命中 / 命中规则没有可用通道）：回到未命中默认
            if ("drop".equals(unmatched)) {
                Result drop = new Result(true, null);
                drop.matched.addAll(res.matched);
                return drop;
            }
            mergeTargets(res.targets, allChannels, 0, null);
        }
        return res;
    }

    /** 人工动作入口：不经规则，直接投全部通道（prio=0）。 */
    public static Result routeAll(List<String> allChannels) {
        Result res = new Result(false, null);
        mergeTargets(res.targets, allChannels, 0, null);
        return res;
    }

    /** 面板匹配测试器入口：现拼一条事件跑一遍规则。 */
    public static Result preview(String peer, String body, List<Rule> rules,
                                 List<String> allChannels, String unmatched) {
        SmsEvent e = new SmsEvent();
        e.peer = peer == null ? "" : peer;
        e.body = body == null ? "" : body;
        e.ts = System.currentTimeMillis();
        e.code = SmsEvent.extractCode(e.body);
        return route(e, rules, allChannels, unmatched);
    }

    private static boolean matchRule(Rule r, SmsEvent e, long now) {
        if (r.peer.size() > 0 && !anyMatch(r.peer, e.peer)) return false;
        if (r.body.size() > 0 && !anyMatch(r.body, e.body)) return false;
        if (r.fromMin >= 0 && !inWindow(now, r.fromMin, r.toMin)) return false;
        return true;
    }

    private static boolean anyMatch(List<Pattern> pats, String s) {
        if (s == null) s = "";
        for (int i = 0; i < pats.size(); i++) {
            if (pats.get(i).matcher(s).find()) return true;
        }
        return false;
    }

    /** 时段判定，支持跨零点（from > to 时按环形处理）。 */
    static boolean inWindow(long now, int fromMin, int toMin) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (fromMin <= toMin) return cur >= fromMin && cur <= toMin;
        return cur >= fromMin || cur <= toMin;
    }

    /** 同通道只保留一个目标，prio 取最高。 */
    private static void mergeTargets(List<Target> acc, List<String> chs, int prio, String template) {
        if (chs == null) return;
        for (int i = 0; i < chs.size(); i++) {
            String ch = chs.get(i);
            if (ch == null || ch.length() == 0) continue;
            Target exist = findTarget(acc, ch);
            if (exist == null) {
                acc.add(new Target(ch, prio, template));
            } else if (prio > exist.prio) {
                acc.set(acc.indexOf(exist), new Target(ch, prio, template));
            }
        }
    }

    private static Target findTarget(List<Target> acc, String ch) {
        for (int i = 0; i < acc.size(); i++) {
            if (acc.get(i).ch.equals(ch)) return acc.get(i);
        }
        return null;
    }
}
