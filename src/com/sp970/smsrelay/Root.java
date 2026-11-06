package com.sp970.smsrelay;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * app 侧的 root 通道客户端。
 *
 * 设备侧 helper 是 init(root) 拉起的 busybox httpd，只听 127.0.0.1:8899：
 *   POST /cgi-bin/cmd?t=&lt;token&gt;  body=shell 命令  → stdout/stderr + "[exit=N]"
 *   POST /cgi-bin/get?t=&lt;token&gt;  body=文件路径    → 文件原始字节
 *   GET  /ping.txt                                     → pong
 *
 * 固件 /system/xbin/su 只放行 uid 0/2000，uid 10075 的 smsrelay 永远提不了权，
 * 所以这里不再尝试 su，也不再算 webkey 那套 Digest —— 一律走 helper。
 * helper 不在本机之外暴露，凭据只有一组，跟 8080 面板的配置放在一起。
 */
public final class Root {

    public static final int MAX_OUT = 512 * 1024;

    /** 一次命令执行的结果。 */
    public static final class Result {
        public int exit = -1;
        public String out = "";
        public String error = "";
        /** 请求体已经发出：装包会杀掉本进程，拿不到响应当成功处理。 */
        public boolean sent = false;

        public boolean ok() {
            return exit == 0 && error.length() == 0;
        }
    }

    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 120000;

    private Root() {
    }

    public static Result exec(Context ctx, String cmd) {
        AppConfig cfg = AppConfig.load(ctx);
        return exec(cfg.helperUrl, cfg.helperToken, cmd, READ_MS);
    }

    public static Result exec(String base, String token, String cmd, int readMs) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            byte[] body = cmd.getBytes("UTF-8");
            c = open(base + "/cgi-bin/cmd?t=" + enc(token), "text/plain; charset=utf-8",
                    body.length, readMs);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            r.sent = true;
            String text = readText(c, MAX_OUT);
            int i = text.lastIndexOf("[exit=");
            if (i >= 0) {
                String tail = text.substring(i + 6);
                int e = tail.indexOf(']');
                if (e > 0) {
                    try {
                        r.exit = Integer.parseInt(tail.substring(0, e).trim());
                    } catch (Throwable ignored) {
                    }
                }
                r.out = text.substring(0, i).trim();
            } else {
                r.out = text.trim();
            }
            int code = 0;
            try {
                code = c.getResponseCode();
            } catch (Throwable ignored) {
            }
            if (code >= 400) r.error = "helper HTTP " + code;
        } catch (Throwable t) {
            r.error = msg(t);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    /** 下载设备上的文件（helper 只允许 /sdcard/Download/ 下的路径）。 */
    public static byte[] file(Context ctx, String path) throws Exception {
        AppConfig cfg = AppConfig.load(ctx);
        byte[] body = path.getBytes("UTF-8");
        HttpURLConnection c = null;
        try {
            c = open(cfg.helperUrl + "/cgi-bin/get?t=" + enc(cfg.helperToken),
                    "text/plain; charset=utf-8", body.length, 60000);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            byte[] b = readBytes(c, 16 * 1024 * 1024);
            if (b.length > 7 && new String(b, 0, Math.min(16, b.length), "UTF-8").startsWith("[exit=")) {
                throw new IllegalStateException("下载失败：" + new String(b, "UTF-8").trim());
            }
            return b;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** helper 是否活着（面板维护页用来显示状态）。 */
    public static boolean alive(Context ctx) {
        AppConfig cfg = AppConfig.load(ctx);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(cfg.helperUrl + "/ping.txt").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200 && "pong".equals(readText(c, 64).trim());
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String uid(Context ctx) {
        Result r = exec(ctx, "id");
        return r.ok() ? r.out.trim() : "";
    }

    // ---------- 内部 ----------

    private static HttpURLConnection open(String url, String ct, int len, int readMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(readMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(len);   // 必须有 Content-Length，helper 靠它读 body
        c.setRequestProperty("Content-Type", ct);
        return c;
    }

    private static byte[] readBytes(HttpURLConnection c, int max) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        try {
            while ((n = in.read(b)) > 0) {
                if (bo.size() + n > max) {
                    bo.write(b, 0, max - bo.size());
                    break;
                }
                bo.write(b, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return bo.toByteArray();
    }

    private static String readText(HttpURLConnection c, int max) {
        try {
            return new String(readBytes(c, max), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String msg(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }
}
