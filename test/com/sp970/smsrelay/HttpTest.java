package com.sp970.smsrelay;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

/** 纯 JVM 冒烟测试：验证 Http 的解析、keep-alive、POST、错误码、并发。 */
public class HttpTest {

    public static void main(String[] a) throws Exception {
        Http h = new Http(18080, new Http.Handler() {
            public Http.Resp serve(Http.Req req) {
                if ("/json".equals(req.path)) {
                    return Http.Resp.json("{\"ok\":true,\"q\":\"" + req.q("x", "none")
                            + "\",\"n\":" + req.body.length() + "}");
                }
                if ("/echo".equals(req.path)) {
                    return Http.Resp.json("{\"ok\":true,\"body\":\"" + req.body.replace("\"", "'")
                            + "\",\"form\":" + req.form.size() + "}");
                }
                if ("/err".equals(req.path)) return Http.Resp.error(400, "bad thing");
                return Http.Resp.error(404, "nope");
            }
        });
        if (!h.start()) {
            System.out.println("FAIL start");
            return;
        }
        System.out.println("listening " + h.port());

        System.out.println("1 GET+query  : " + get("http://127.0.0.1:18080/json?x=%E4%B8%AD%E6%96%87"));

        String keep = get("http://127.0.0.1:18080/json?x=1") + " | " + get("http://127.0.0.1:18080/json?x=2");
        System.out.println("2 twice      : " + keep);

        System.out.println("3 POST json  : " + post("http://127.0.0.1:18080/echo",
                "{\"to\":\"10086\"}", "application/json"));
        System.out.println("4 POST form  : " + post("http://127.0.0.1:18080/echo",
                "to=10086&body=%E4%BD%A0%E5%A5%BD", "application/x-www-form-urlencoded"));

        HttpURLConnection c4 = (HttpURLConnection) new URL("http://127.0.0.1:18080/err").openConnection();
        System.out.println("5 400        : " + c4.getResponseCode() + " " + read(c4));

        try {
            Socket s = new Socket("127.0.0.1", 18080);
            StringBuilder sb = new StringBuilder("GET /json HTTP/1.1\r\nHost: x\r\n");
            for (int i = 0; i < 600; i++) {
                sb.append("X-H").append(i).append(": 0123456789012345678901234567890123456789\r\n");
            }
            sb.append("\r\n");
            s.getOutputStream().write(sb.toString().getBytes());
            s.getOutputStream().flush();
            byte[] buf = new byte[80];
            int n = s.getInputStream().read(buf);
            System.out.println("6 bigheader  : " + (n > 0
                    ? new String(buf, 0, n, "UTF-8").split("\r\n")[0] : "closed"));
            s.close();
        } catch (Exception e) {
            System.out.println("6 ex         : " + e);
        }

        final int[] ok = {0};
        Thread[] ts = new Thread[20];
        for (int i = 0; i < 20; i++) {
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        if (get("http://127.0.0.1:18080/json?x=" + System.nanoTime())
                                .contains("\"ok\":true")) {
                            synchronized (ok) { ok[0]++; }
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            ts[i].start();
        }
        for (int i = 0; i < 20; i++) ts[i].join();
        System.out.println("7 concurrent : " + ok[0] + "/20 ok");

        h.stop();
        Thread.sleep(300);
        System.out.println("8 stopped    : " + (!h.isRunning()));
    }

    static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setRequestProperty("Connection", "keep-alive");
        return "[" + c.getResponseCode() + "] " + read(c);
    }

    static String post(String u, String body, String ct) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", ct);
        c.getOutputStream().write(body.getBytes("UTF-8"));
        return "[" + c.getResponseCode() + "] " + read(c);
    }

    static String read(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "(null)";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }
}
