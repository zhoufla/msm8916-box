package com.sp970.smsrelay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 极简 HTTP/1.1 客户端：只用 Socket + 手拼请求行。
 *
 * 为什么不直接用 HttpURLConnection：Android 4.4 的 HttpURLConnection 是 OkHttp 1.x 的实现，
 * 它内部按自己的 ConnectionSpec 重新决定协议和套件，setSSLSocketFactory 里设的
 * TLSv1.2 / AES-GCM 会被盖回去——实测连 api.github.com 一直回
 * 「SSL23_GET_SERVER_HELLO sslv3 alert handshake failure」。自己拿 SSLSocket 才完全可控。
 *
 * 只做 GET（版本元数据和 APK 都是 GET），跟随跳转、支持 chunked 落盘。
 */
public final class Net {

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 40000;

    /** 服务端按这个顺序挑；4.4 支持 AES-GCM 但默认没开，这里显式打开。 */
    private static final String[] CIPHERS = {
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
    };

    public interface Progress {
        void on(long done, long total);
    }

    public static final class Resp {
        public int code;
        public String body = "";
        public long size;
        public String location = "";
        public String transport = "java";
    }

    private Net() {
    }

    /** 取文本（JSON 等）。 */
    public static Resp get(String url, boolean insecure, String accept) throws Exception {
        return request(url, insecure, accept, null, null);
    }

    /** 下载到文件；size 是写盘字节数。 */
    public static Resp download(String url, boolean insecure, String accept, File out,
                                Progress p) throws Exception {
        return request(url, insecure, accept, out, p);
    }

    /**
     * 先把 Java TLS 试一遍，握手失败再退到系统 curl。
     *
     * 这台 4.4 的 Conscrypt 只带 CBC 套件（实测 14 个现代套件里只有 7 个，
     * 且没有 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256），GitHub 只收 AEAD，
     * 所以 java 栈永远握不上手；而 /system/bin/curl 自带 OpenSSL，TLS1.2+GCM 是通的。
     * 退到 curl 时必须带 -k（这台机器没有 CA bundle），但装包前仍会验签名，
     * 中间人换不了包。
     */
    public static Resp getAuto(String url, boolean insecure, String accept) throws Exception {
        try {
            return get(url, insecure, accept);
        } catch (Exception e) {
            if (!curlReady() || !looksLikeTls(e)) throw e;
            return curlText(url, accept);
        }
    }

    public static Resp downloadAuto(String url, boolean insecure, String accept, File out,
                                    Progress p) throws Exception {
        try {
            return download(url, insecure, accept, out, p);
        } catch (Exception e) {
            if (!curlReady() || !looksLikeTls(e)) throw e;
            return curlFile(url, out);
        }
    }

    private static boolean looksLikeTls(Throwable t) {
        String m = String.valueOf(t.getMessage()).toLowerCase(Locale.US);
        return t instanceof javax.net.ssl.SSLException
                || m.contains("ssl") || m.contains("handshake") || m.contains("certificate");
    }

    // 这台机器自带 curl 7.21（不认识 --tlsv1.2），不指定版本，让它自己协商到最高
    private static final String CURL = "/system/bin/curl";

    private static boolean curlReady() {
        try {
            return new File(CURL).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Resp curlText(String url, String accept) throws Exception {
        Resp r = new Resp();
        r.transport = "curl";
        String[] cmd = {CURL, "-sS", "-L", "-k", "-m", "60",
                "-A", "sp970-smsrelay", "-H", "Accept: " + (accept == null ? "*/*" : accept),
                "-w", "\n%{http_code}", url};
        Out res = exec(cmd);
        String txt = res.out;
        int nl = txt.lastIndexOf('\n');
        if (nl >= 0) {
            try {
                r.code = Integer.parseInt(txt.substring(nl + 1).trim());
            } catch (Throwable ignored) {
            }
            txt = txt.substring(0, nl);
        }
        r.body = txt;
        r.size = txt.length();
        if (res.code != 0 && r.code == 0) throw new IOException("curl 退出码 " + res.code + " " + res.err);
        return r;
    }

    private static Resp curlFile(String url, File out) throws Exception {
        Resp r = new Resp();
        r.transport = "curl";
        String[] cmd = {CURL, "-sS", "-L", "-k", "-m", "600",
                "-A", "sp970-smsrelay", "-o", out.getAbsolutePath(),
                "-w", "%{http_code}", url};
        Out res = exec(cmd);
        try {
            r.code = Integer.parseInt(res.out.trim());
        } catch (Throwable ignored) {
        }
        r.size = out.exists() ? out.length() : 0;
        if (res.code != 0 && r.code == 0) throw new IOException("curl 退出码 " + res.code + " " + res.err);
        return r;
    }

    private static final class Out {
        int code;
        String out = "";
        String err = "";
    }

    private static Out exec(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        final StringBuilder so = new StringBuilder();
        final StringBuilder se = new StringBuilder();
        Thread t1 = reader(p.getInputStream(), so);
        Thread t2 = reader(p.getErrorStream(), se);
        Out o = new Out();
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
        while (System.currentTimeMillis() < deadline) {
            try {
                o.code = p.exitValue();
                break;
            } catch (IllegalThreadStateException alive) {
                Thread.sleep(200);
            }
        }
        try {
            p.waitFor();
        } catch (Throwable ignored) {
        }
        t1.join(1500);
        t2.join(1500);
        o.out = so.toString();
        o.err = se.toString();
        return o;
    }

    private static Thread reader(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                } catch (Throwable ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Resp request(String url, boolean insecure, String accept, File out,
                                Progress prog) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            Resp r = once(cur, insecure, accept, out, prog);
            if (r.code == 301 || r.code == 302 || r.code == 303
                    || r.code == 307 || r.code == 308) {
                if (r.location == null || r.location.length() == 0) return r;
                cur = absolute(cur, r.location);
                continue;
            }
            return r;
        }
        throw new IOException("跳转超过 5 次");
    }

    private static Resp once(String url, boolean insecure, String accept, File out,
                             Progress prog) throws Exception {
        URL u = new URL(url);
        String scheme = u.getProtocol().toLowerCase(Locale.US);
        boolean tls = "https".equals(scheme);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = u.getFile();
        if (path == null || path.length() == 0) path = "/";

        Socket raw = null;
        Socket sock = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), CONNECT_MS);
            raw.setSoTimeout(READ_MS);
            if (tls) {
                // 用带 host 的包装，才能带上 SNI（GitHub 按 SNI 发证书）
                SSLSocket ss = (SSLSocket) factory(insecure).createSocket(raw, host, port, true);
                tune(ss);
                ss.startHandshake();
                sock = ss;
            } else {
                sock = raw;
            }

            OutputStream os = sock.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("User-Agent: sp970-smsrelay\r\n");
            req.append("Accept: ").append(accept == null ? "*/*" : accept).append("\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n\r\n");
            os.write(req.toString().getBytes("UTF-8"));
            os.flush();

            InputStream in = new BufferedInputStream(sock.getInputStream(), 16384);
            Resp resp = new Resp();
            String statusLine = line(in);
            if (statusLine == null) throw new IOException("服务端没有响应");
            resp.code = codeOf(statusLine);

            Map<String, String> h = new HashMap<String, String>();
            String l;
            while ((l = line(in)) != null && l.length() > 0) {
                int c = l.indexOf(':');
                if (c > 0) {
                    h.put(l.substring(0, c).trim().toLowerCase(Locale.US),
                            l.substring(c + 1).trim());
                }
            }
            resp.location = h.get("location") == null ? "" : h.get("location");

            boolean chunked = "chunked".equalsIgnoreCase(h.get("transfer-encoding"));
            long len = -1;
            try {
                if (h.get("content-length") != null) {
                    len = Long.parseLong(h.get("content-length").trim());
                }
            } catch (Throwable ignored) {
            }

            if (out == null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(
                        len > 0 && len < 4 * 1024 * 1024 ? (int) len : 8192);
                copy(in, chunked, len, bo, prog);
                byte[] data = bo.toByteArray();
                resp.body = new String(data, "UTF-8");
                resp.size = data.length;
            } else {
                FileOutputStream fo = new FileOutputStream(out);
                try {
                    copy(in, chunked, len, fo, prog);
                } finally {
                    try {
                        fo.close();
                    } catch (Throwable ignored) {
                    }
                }
                resp.size = out.length();
            }
            return resp;
        } finally {
            close(sock);
            close(raw);
        }
    }

    private static void copy(InputStream in, boolean chunked, long len, OutputStream out,
                             Progress prog) throws Exception {
        long done = 0;
        long total = chunked ? -1 : len;
        byte[] buf = new byte[16384];
        if (chunked) {
            while (true) {
                String sz = line(in);
                if (sz == null) break;
                int semi = sz.indexOf(';');
                if (semi > 0) sz = sz.substring(0, semi);
                sz = sz.trim();
                if (sz.length() == 0) continue;
                int n;
                try {
                    n = Integer.parseInt(sz, 16);
                } catch (Throwable t) {
                    break;
                }
                if (n == 0) {
                    String t;
                    while ((t = line(in)) != null && t.length() > 0) {
                        // trailer，忽略
                    }
                    break;
                }
                int left = n;
                while (left > 0) {
                    int r = in.read(buf, 0, Math.min(buf.length, left));
                    if (r < 0) break;
                    out.write(buf, 0, r);
                    left -= r;
                    done += r;
                    if (prog != null) prog.on(done, total);
                }
                line(in);   // 块尾 CRLF
            }
        } else if (len >= 0) {
            while (done < len) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, len - done));
                if (r < 0) break;
                out.write(buf, 0, r);
                done += r;
                if (prog != null) prog.on(done, total);
            }
        } else {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                if (prog != null) prog.on(done, total);
            }
        }
        out.flush();
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int codeOf(String statusLine) {
        try {
            String[] p = statusLine.split(" ");
            if (p.length >= 2) return Integer.parseInt(p[1].trim());
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
        }
        if (!any) return null;
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String absolute(String base, String loc) throws Exception {
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
        URL b = new URL(base);
        String prefix = b.getProtocol() + "://" + b.getHost()
                + (b.getPort() > 0 ? ":" + b.getPort() : "");
        if (loc.startsWith("/")) return prefix + loc;
        String p = b.getPath();
        int slash = p == null ? -1 : p.lastIndexOf('/');
        return prefix + (slash >= 0 ? p.substring(0, slash + 1) : "/") + loc;
    }


    /** 诊断用：报一下这台机器的 TLS 能力，以及跟目标站握手能不能成。 */
    public static String probe(String host) {
        org.json.JSONObject o = new org.json.JSONObject();
        Socket raw = null;
        Socket sock = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(host, 443), CONNECT_MS);
            raw.setSoTimeout(READ_MS);
            SSLSocket ss = (SSLSocket) factory(false).createSocket(raw, host, 443, true);
            sock = ss;
            o.put("supportedProtocols", join(ss.getSupportedProtocols()));
            o.put("defaultProtocols", join(ss.getEnabledProtocols()));
            java.util.List<String> sup = Arrays.asList(ss.getSupportedCipherSuites());
            java.util.List<String> have = new ArrayList<String>();
            for (int i = 0; i < CIPHERS.length; i++) {
                if (sup.contains(CIPHERS[i])) have.add(CIPHERS[i]);
            }
            o.put("modernCiphersPresent", have.size());
            o.put("sample", have.isEmpty() ? "" : have.get(0));
            tune(ss);
            o.put("tunedProtocols", join(ss.getEnabledProtocols()));
            try {
                ss.startHandshake();
                o.put("handshake", "ok");
                o.put("proto", ss.getSession().getProtocol());
                o.put("cipher", ss.getSession().getCipherSuite());
            } catch (Throwable t) {
                o.put("handshake", "fail");
                o.put("error", String.valueOf(t.getMessage()));
            }
        } catch (Throwable t) {
            try {
                o.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        } finally {
            close(sock);
            close(raw);
        }
        return o.toString();
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static SSLSocketFactory factory(boolean insecure) throws Exception {
        if (insecure) {
            final TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            return sc.getSocketFactory();
        }
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, null, null);
        return sc.getSocketFactory();
    }

    /** 4.4 默认只开 TLSv1 + 老套件，两样都显式拉到现代。 */
    private static void tune(SSLSocket s) {
        try {
            s.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"});
        } catch (Throwable ignored) {
        }
        try {
            List<String> sup = Arrays.asList(s.getSupportedCipherSuites());
            List<String> use = new ArrayList<String>();
            for (int i = 0; i < CIPHERS.length; i++) {
                if (sup.contains(CIPHERS[i])) use.add(CIPHERS[i]);
            }
            if (!use.isEmpty()) s.setEnabledCipherSuites(use.toArray(new String[use.size()]));
        } catch (Throwable ignored) {
        }
    }
}
