package com.sp970.smsrelay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 HTTP/1.1 服务器：ServerSocket + 每连接一线程 + keep-alive。
 * 只为内网面板服务，不追求完整协议（不支持 chunked / 100-continue / HEAD 之外的花样）。
 */
public final class Http {

    public interface Handler {
        Resp serve(Req req) throws Exception;
    }

    /** 解析后的一次请求。 */
    public static final class Req {
        public String method = "GET";
        public String path = "/";
        public String rawQuery = "";
        public Map<String, String> query = new LinkedHashMap<String, String>();
        public Map<String, String> headers = new LinkedHashMap<String, String>();  // key 小写
        public Map<String, String> form = new LinkedHashMap<String, String>();
        public String body = "";

        public String q(String k, String def) {
            String v = query.get(k);
            return v == null || v.length() == 0 ? def : v;
        }

        public int qi(String k, int def) {
            try {
                return Integer.parseInt(q(k, String.valueOf(def)));
            } catch (Throwable t) {
                return def;
            }
        }
    }

    /** 待发送的响应。 */
    public static final class Resp {
        public int status = 200;
        public String contentType = "application/json; charset=utf-8";
        public byte[] body = new byte[0];
        public Map<String, String> headers = new LinkedHashMap<String, String>();

        public static Resp json(String s) {
            return text(200, "application/json; charset=utf-8", s);
        }

        public static Resp text(int status, String ct, String s) {
            Resp r = new Resp();
            r.status = status;
            r.contentType = ct;
            try {
                r.body = s.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                r.body = s.getBytes();
            }
            return r;
        }

        public static Resp bytes(String ct, byte[] b) {
            Resp r = new Resp();
            r.contentType = ct;
            r.body = b == null ? new byte[0] : b;
            return r;
        }

        public static Resp error(int status, String msg) {
            String m = msg == null ? "" : msg.replace("\\", "\\\\").replace("\"", "\\\"");
            return text(status, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"" + m + "\"}");
        }
    }

    private static final int MAX_HEAD = 16 * 1024;
    // 维护页的命令框 / crontab 编辑器会提交稍大的纯文本；仍然不做 multipart，
    // 文件上传交给 adb push 或升级通道，二进制下载走 Resp.bytes()。
    private static final int MAX_BODY = 512 * 1024;
    private static final int MAX_CONN = 24;

    private final int wantPort;
    private final Handler handler;
    private final AtomicInteger conns = new AtomicInteger(0);
    private volatile boolean running = false;
    private ServerSocket server;
    private Thread acceptThread;

    public Http(int port, Handler handler) {
        this.wantPort = port;
        this.handler = handler;
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(wantPort), 32);
        } catch (IOException e) {
            try { if (server != null) server.close(); } catch (Throwable ignored) {}
            server = null;
            return false;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "panel-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        server = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? wantPort : s.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (Throwable t) {
                if (!running) return;
                sleep(200);
                continue;
            }
            if (conns.get() >= MAX_CONN) {
                try {
                    s.setSoTimeout(3000);
                    writeQuiet(s, Resp.error(503, "too many connections"));
                } catch (Throwable ignored) {
                } finally {
                    close(s);
                }
                continue;
            }
            final Socket sock = s;
            conns.incrementAndGet();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serveConn(sock);
                    } finally {
                        conns.decrementAndGet();
                        close(sock);
                    }
                }
            }, "panel-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serveConn(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
            OutputStream out = sock.getOutputStream();

            while (running) {
                Req req = readRequest(in);
                if (req == null) return;

                Resp resp;
                try {
                    resp = handler.serve(req);
                    if (resp == null) resp = Resp.error(500, "empty response");
                } catch (Throwable t) {
                    Logs.add("panel", "handle " + req.path + " 失败: " + t);
                    resp = Resp.error(500, t.getClass().getSimpleName() + ": " + t.getMessage());
                }

                boolean keepAlive = !"close".equalsIgnoreCase(req.headers.get("connection"));
                if (write(sock, req, resp, keepAlive) == false) return;
                if (!keepAlive) return;
            }
        } catch (Throwable ignored) {
            // 客户端断开、超时、浏览器预连接，都走这里，不需要处理
        }
    }

    private Req readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
        int state = 0;   // 0..3 匹配 \r\n\r\n
        int b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
            if (head.size() > MAX_HEAD) throw new IOException("header too large");
        }
        if (head.size() == 0) return null;

        Req req = new Req();
        String[] lines = head.toString("UTF-8").split("\r\n");
        if (lines.length == 0) return null;

        String[] rl = lines[0].split(" ");
        if (rl.length < 2) return null;
        req.method = rl[0].toUpperCase();
        String target = rl[1];

        int qi = target.indexOf('?');
        if (qi >= 0) {
            req.path = target.substring(0, qi);
            req.rawQuery = target.substring(qi + 1);
        } else {
            req.path = target;
        }
        int hi = req.path.indexOf('#');
        if (hi >= 0) req.path = req.path.substring(0, hi);
        parseUrlEncoded(req.rawQuery, req.query);

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c <= 0) continue;
            req.headers.put(line.substring(0, c).trim().toLowerCase(),
                    line.substring(c + 1).trim());
        }

        int len = 0;
        try {
            len = Integer.parseInt(req.headers.containsKey("content-length")
                    ? req.headers.get("content-length") : "0");
        } catch (Throwable ignored) {
        }
        if (len > 0) {
            if (len > MAX_BODY) throw new IOException("body too large");
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            req.body = new String(buf, 0, off, "UTF-8");
            String ct = req.headers.get("content-type");
            if (ct != null && ct.toLowerCase().indexOf("application/x-www-form-urlencoded") >= 0) {
                parseUrlEncoded(req.body, req.form);
            }
        }
        return req;
    }

    private boolean write(Socket sock, Req req, Resp resp, boolean keepAlive) {
        try {
            OutputStream out = sock.getOutputStream();
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status))
                    .append("\r\n");
            sb.append("Content-Type: ").append(resp.contentType).append("\r\n");
            sb.append("Content-Length: ").append(resp.body.length).append("\r\n");
            sb.append("Cache-Control: no-store\r\n");
            sb.append("X-Content-Type-Options: nosniff\r\n");
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            if (!"HEAD".equals(req.method)) out.write(resp.body);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void writeQuiet(Socket s, Resp r) {
        try {
            Req fake = new Req();
            write(s, fake, r, false);
        } catch (Throwable ignored) {
        }
    }

    private static void parseUrlEncoded(String s, Map<String, String> into) {
        if (s == null || s.length() == 0) return;
        String[] parts = s.split("&");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            int eq = parts[i].indexOf('=');
            String k = eq < 0 ? parts[i] : parts[i].substring(0, eq);
            String v = eq < 0 ? "" : parts[i].substring(eq + 1);
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignored) {
            }
            into.put(k, v);
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    private static void close(Socket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
