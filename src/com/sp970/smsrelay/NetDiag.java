package com.sp970.smsrelay;

import android.content.Context;

import org.json.JSONObject;

/**
 * 网络诊断（B2 第 18 项）。
 *
 * 只做「拼命令 → 回原文」，不做结论；判读交给面板上的人。
 * 所有目标都过一遍白名单字符校验 —— 这里是唯一接受外部输入拼进 shell 的地方，
 * 不校验就等于把通用 root 通道开在 GET 参数上。
 */
public final class NetDiag {

    private static final String BB = "/system/xbin/busybox";
    private static final String DL = "/sdcard/Download";

    private NetDiag() {
    }

    public static JSONObject run(Context ctx, String kind, String target, String iface, int count) {
        JSONObject o = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            kind = kind == null ? "" : kind.trim();
            if (count < 1) count = 4;
            if (count > 20) count = 20;

            String cmd;
            if ("ping".equals(kind)) {
                String host = host(target);
                cmd = "ping -c " + count + " " + host + " 2>&1";
            } else if ("trace".equals(kind)) {
                String host = host(target);
                cmd = BB + " traceroute -m 10 -w 2 -q 1 " + host + " 2>&1";
            } else if ("dns".equals(kind)) {
                String host = host(target);
                cmd = BB + " nslookup " + host + " 2>&1";
            } else if ("http".equals(kind)) {
                String url = url(target);
                cmd = BB + " wget -S -O /dev/null -T 8 '" + url + "' 2>&1";
            } else if ("route".equals(kind)) {
                cmd = "ip route 2>&1; echo '##ADDR'; ip -o addr 2>&1; echo '##NEIGH'; ip neigh 2>&1";
            } else if ("neigh".equals(kind)) {
                cmd = "ip neigh 2>&1";
            } else if ("sockets".equals(kind)) {
                cmd = "netstat -anp 2>&1";
            } else if ("tcpdump".equals(kind)) {
                String dev = iface == null || iface.trim().length() == 0 ? "any" : iface(iface);
                int sec = count > 20 ? 20 : count;
                int pkts = 200;
                cmd = "U=$(cat /proc/uptime | " + BB + " cut -d'.' -f1); "
                        + "T=" + DL + "/tcpdump-$U.pcap; "
                        + BB + " timeout " + sec + " tcpdump -i " + dev + " -c " + pkts
                        + " -s 0 -w $T 2>&1; "
                        + "echo '##PATH'; echo $T; "
                        + "echo '##SIZE'; " + BB + " stat -c %s $T 2>/dev/null";
            } else if ("attach".equals(kind)) {
                cmd = "echo '##SIM'; getprop gsm.sim.state; getprop gsm.operator.alpha; "
                        + "getprop gsm.operator.numeric; getprop gsm.network.type; "
                        + "getprop gsm.data.state; "
                        + "echo '##SUBSYS'; for i in 0 1 2; do "
                        + "cat /sys/bus/msm_subsys/devices/subsys$i/name 2>/dev/null; "
                        + "cat /sys/bus/msm_subsys/devices/subsys$i/state 2>/dev/null; done; "
                        + "echo '##RIL'; ps | " + BB + " grep -E 'rild|qcril|netmgrd' | " + BB + " grep -v grep; "
                        + "echo '##ADDR'; ip -o addr 2>&1; "
                        + "echo '##ROUTE'; ip route 2>&1; "
                        + "echo '##DNS'; getprop net.dns1; getprop net.dns2; "
                        + "echo '##RMNET'; cat /proc/net/dev | " + BB + " grep -E 'rmnet|wlan|rndis'";
            } else {
                throw new IllegalArgumentException("未知诊断类型：" + kind);
            }

            Root.Result r = Root.exec(ctx, cmd);
            o.put("ok", r.error.length() == 0);
            o.put("kind", kind);
            o.put("exit", r.exit);
            o.put("elapsedMs", System.currentTimeMillis() - t0);
            o.put("text", r.out);
            if (r.error.length() > 0) o.put("error", r.error);
            if ("tcpdump".equals(kind)) parseDump(o, r.out);
            o.put("message", r.error.length() > 0 ? r.error
                    : ("完成，耗时 " + (System.currentTimeMillis() - t0) + " ms"));
        } catch (Throwable t) {
            put(o, "ok", false);
            put(o, "message", str(t));
        }
        return o;
    }

    /** 抓包结果：把 PATH/SIZE 两段抽出来，面板直接给下载按钮。 */
    private static void parseDump(JSONObject o, String out) {
        try {
            String path = "", size = "0";
            String[] lines = out.replace("\r", "").split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals("##PATH") && i + 1 < lines.length) {
                    path = lines[i + 1].trim();
                }
                if (lines[i].trim().equals("##SIZE") && i + 1 < lines.length) {
                    size = lines[i + 1].trim();
                }
            }
            o.put("path", path);
            o.put("size", size);
            long n = 0;
            try {
                n = Long.parseLong(size);
            } catch (Throwable ignored) {
            }
            o.put("ok", path.length() > 0);
            o.put("message", path.length() == 0 ? "没有生成抓包文件"
                    : ("抓包完成：" + path + "（" + n + " 字节）"));
        } catch (Throwable ignored) {
        }
    }

    // ---------- 输入校验 ----------

    /** 主机名 / IPv4 / IPv6 字面量。不接受任何 shell 元字符。 */
    private static String host(String s) {
        String v = s == null ? "" : s.trim();
        if (!v.matches("[A-Za-z0-9._:\\-]{1,64}")) {
            throw new IllegalArgumentException("目标只能是域名或 IP：" + v);
        }
        return v;
    }

    private static String url(String s) {
        String v = s == null ? "" : s.trim();
        if (!v.matches("https?://[A-Za-z0-9._:\\-/?#=&%+~]{1,200}")) {
            throw new IllegalArgumentException("只支持 http/https URL：" + v);
        }
        return v;
    }

    private static String iface(String s) {
        String v = s == null ? "" : s.trim();
        if (!v.matches("[A-Za-z0-9._]{1,16}")) {
            throw new IllegalArgumentException("网卡名不合法：" + v);
        }
        return v;
    }

    private static String str(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
    }
}
