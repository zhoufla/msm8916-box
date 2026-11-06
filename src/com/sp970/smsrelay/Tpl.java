package com.sp970.smsrelay;

/**
 * 转发模板渲染。
 *
 * 默认模板就三行：发件人号码 / 正文 / 设备名，够看又不啰嗦。
 * 占位符：{peer} 发件人、{body} 正文、{device} 设备名、{tag} 通道标签、{time} 收信时间。
 */
public final class Tpl {

    private Tpl() {
    }

    public static String render(String tpl, String peer, String body,
                                String device, String tag, String time) {
        String t = (tpl == null || tpl.trim().length() == 0) ? Channel.DEFAULT_TEMPLATE : tpl;
        t = t.replace("{peer}", nz(peer))
                .replace("{body}", nz(body))
                .replace("{device}", nz(device))
                .replace("{tag}", nz(tag))
                .replace("{time}", nz(time));
        return tidy(t);
    }

    /** 去行尾空白、压掉连续空行、去掉首尾空行——短信正文里经常带尾随空格和空行。 */
    static String tidy(String s) {
        String[] lines = nz(s).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String l = rtrim(line);
            if (l.length() == 0) {
                blank++;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                if (blank > 0) sb.append('\n');
            }
            sb.append(l);
            blank = 0;
        }
        return sb.toString();
    }

    private static String rtrim(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == ' ' || s.charAt(n - 1) == '\t')) n--;
        return s.substring(0, n);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
