package com.sp970.smsrelay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 三张表：
 *   ev  收到的短信本体（hash 去重）
 *   dl  消息 × 通道 的投递状态，各自退避重试
 *   out 从面板发出的短信（含发送结果）
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "relay.db";
    private static final int VER = 4;

    private static final String CREATE_EV =
            "CREATE TABLE IF NOT EXISTS ev (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "hash TEXT UNIQUE, ts INTEGER, peer TEXT, body TEXT, code TEXT," +
                    "rule TEXT DEFAULT '')";
    private static final String CREATE_DL =
            "CREATE TABLE IF NOT EXISTS dl (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ev_id INTEGER, ch TEXT, status INTEGER DEFAULT 0, tries INTEGER DEFAULT 0," +
                    "next_at INTEGER DEFAULT 0, err TEXT DEFAULT ''," +
                    "prio INTEGER DEFAULT 0, template TEXT DEFAULT '', UNIQUE(ev_id, ch))";
    private static final String CREATE_OUT =
            "CREATE TABLE IF NOT EXISTS out (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts INTEGER, peer TEXT, body TEXT, parts INTEGER DEFAULT 1," +
                    "status INTEGER DEFAULT 0, err TEXT DEFAULT '')";

    private static volatile Db sInstance;

    /** 进程内共用一个 SQLiteOpenHelper：每个 receiver / 页面各 new 一个会各开一条连接。 */
    public static Db get(Context c) {
        if (sInstance == null) {
            synchronized (Db.class) {
                if (sInstance == null) sInstance = new Db(c.getApplicationContext());
            }
        }
        return sInstance;
    }

    /** 保留构造器仅为兼容；新代码一律用 {@link #get(Context)}。 */
    public Db(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_EV);
        db.execSQL(CREATE_DL);
        db.execSQL(CREATE_OUT);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dl ON dl(status, next_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1 结构差别太大，历史无保留价值
            db.execSQL("DROP TABLE IF EXISTS ev");
            db.execSQL("DROP TABLE IF EXISTS dl");
            db.execSQL("DROP TABLE IF EXISTS out");
            onCreate(db);
            return;
        }
        if (oldVersion < 3) db.execSQL(CREATE_OUT);
        if (oldVersion < 4) {
            // P0-A 规则引擎：投递优先级 + 模板覆盖（dl），丢弃规则标记（ev.rule）
            db.execSQL("ALTER TABLE dl ADD COLUMN prio INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE dl ADD COLUMN template TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE ev ADD COLUMN rule TEXT DEFAULT ''");
        }
    }

    // ---------- 收信 ----------

    /**
     * 同一条短信的判定窗口。
     *
     * 广播路径的 ts 来自短信中心时间戳，从系统收件箱补收时用的是 provider 里的 date，
     * 两者可能差几秒（实测同一条 10086 回复差了 2 秒），所以只比 peer+body 不够，
     * 必须给时间戳留余量，否则每次「补收」都会把已转发的短信再转一遍。
     */
    private static final long DUP_WINDOW_MS = 90L * 1000L;

    /** 入队消息本体，重复则返回已有 id。 */
    public long insertEvent(SmsEvent e) {
        try {
            long near = findNearDuplicate(e);
            if (near > 0) return near;

            ContentValues v = new ContentValues();
            v.put("hash", e.hash());
            v.put("ts", e.ts);
            v.put("peer", e.peer);
            v.put("body", e.body);
            v.put("code", e.code);
            long id = getWritableDatabase().insertWithOnConflict("ev", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (id > 0) return id;
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id FROM ev WHERE hash=?", new String[]{e.hash()});
            try {
                if (c.moveToFirst()) return c.getLong(0);
            } finally {
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 同号码同年内容、时间相差不超过窗口期，视为同一条。 */
    private long findNearDuplicate(SmsEvent e) {
        return nearDuplicateId(e.peer, e.body, e.ts);
    }

    /**
     * 面板用：判断某条短信（号码+内容+时间）是否已经在转发队列里。
     * 系统收件箱据此标「已转发」，单条转发据此防重——判定口径与入库判重完全一致。
     */
    public long nearDuplicateId(String peer, String body, long ts) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id FROM ev WHERE peer=? AND body=? AND ts BETWEEN ? AND ? " +
                            "ORDER BY id LIMIT 1",
                    new String[]{nz(peer), nz(body),
                            String.valueOf(ts - DUP_WINDOW_MS),
                            String.valueOf(ts + DUP_WINDOW_MS)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /**
     * 清掉指向已不存在事件的投递。
     * 手工还原数据库、或者写事务被打断时会留下这种行，due() 是 INNER JOIN 取不到它们，
     * 结果面板上的「待发送」永远清不了。
     */
    public int purgeOrphans() {
        try {
            int n = getWritableDatabase().delete("dl",
                    "ev_id NOT IN (SELECT id FROM ev)", null);
            if (n > 0) Logs.add("db", "清理孤儿投递 " + n + " 条");
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 为每个通道建一条投递记录（已存在则跳过）。 */
    public void ensureDeliveries(long evId, List<String> channels) {
        if (channels == null) return;
        List<Router.Target> ts = new ArrayList<Router.Target>();
        for (int i = 0; i < channels.size(); i++) {
            ts.add(new Router.Target(channels.get(i), 0, null));
        }
        ensureTargets(evId, ts);
    }

    /** 规则路由结果建投递：带优先级与模板覆盖（已存在则跳过）。 */
    public void ensureTargets(long evId, List<Router.Target> targets) {
        if (evId <= 0) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            for (int i = 0; i < targets.size(); i++) {
                Router.Target t = targets.get(i);
                ContentValues v = new ContentValues();
                v.put("ev_id", evId);
                v.put("ch", t.ch);
                v.put("status", 0);
                v.put("tries", 0);
                v.put("next_at", 0);
                v.put("prio", t.prio);
                if (t.template != null && t.template.length() > 0) v.put("template", t.template);
                db.insertWithOnConflict("dl", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 记录丢弃规则名（action=drop 的消息仍入库，面板可见、可人工重发）。 */
    public void setEventRule(long evId, String rule) {
        if (evId <= 0) return;
        try {
            ContentValues v = new ContentValues();
            v.put("rule", rule == null ? "" : rule);
            getWritableDatabase().update("ev", v, "id=?",
                    new String[]{String.valueOf(evId)});
        } catch (Throwable ignored) {
        }
    }

    public List<Deliver> due(int limit) {
        List<Deliver> out = new ArrayList<Deliver>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT d.id,d.ev_id,d.ch,e.ts,e.peer,e.body,e.code,d.tries,d.err,d.prio,d.template " +
                            "FROM dl d JOIN ev e ON e.id=d.ev_id " +
                            "WHERE d.status=0 AND d.next_at<=? " +
                            "ORDER BY d.prio DESC, d.next_at, d.id LIMIT " + limit,
                    new String[]{String.valueOf(System.currentTimeMillis())});
            while (c.moveToNext()) {
                Deliver d = new Deliver();
                d.dlId = c.getLong(0);
                d.evId = c.getLong(1);
                d.ch = c.getString(2);
                d.ts = c.getLong(3);
                d.peer = c.getString(4);
                d.body = c.getString(5);
                d.code = c.getString(6);
                d.tries = c.getInt(7);
                d.err = c.getString(8);
                d.prio = c.getInt(9);
                d.template = c.getString(10) == null ? "" : c.getString(10);
                out.add(d);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public void markOk(long dlId) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", 1);
            v.put("next_at", 0);
            v.put("err", "");
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Throwable ignored) {
        }
    }

    /** 失败退避：1/2/4/8/16/30 分钟，默认 10 次后放弃（记录保留便于排查）。 */
    public void markFail(long dlId, int tries, String err) {
        markFail(dlId, tries, err, 10);
    }

    /** 同上，次数上限由通道配置决定。 */
    public void markFail(long dlId, int tries, String err, int maxTries) {
        try {
            int t = tries + 1;
            long backoff = Retry.backoffMs(t);
            ContentValues v = new ContentValues();
            v.put("tries", t);
            v.put("err", err == null ? "" : (err.length() > 300 ? err.substring(0, 300) : err));
            v.put("next_at", System.currentTimeMillis() + backoff);
            v.put("status", Retry.giveUp(tries, maxTries) ? 2 : 0);
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Throwable ignored) {
        }
    }

    /** 面板「重发」：把该消息所有失败/放弃的投递重置为待发，立即重试。 */
    public int retryEvent(long evId) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", 0);
            v.put("tries", 0);
            v.put("next_at", 0);
            v.put("err", "");
            return getWritableDatabase().update("dl", v, "ev_id=? AND status<>1",
                    new String[]{String.valueOf(evId)});
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 面板重发时补充新加的通道（配置里后来才加的通道也能收历史消息）。 */
    public void retryEventWithChannels(long evId, List<String> channels) {
        ensureDeliveries(evId, channels);
        try {
            ContentValues v = new ContentValues();
            v.put("status", 0);
            v.put("tries", 0);
            v.put("next_at", 0);
            v.put("err", "");
            getWritableDatabase().update("dl", v, "ev_id=? AND status<>1",
                    new String[]{String.valueOf(evId)});
        } catch (Throwable ignored) {
        }
    }

    public int pending() {
        return count("SELECT COUNT(*) FROM dl d JOIN ev e ON e.id=d.ev_id WHERE d.status=0");
    }

    public int sent() {
        return count("SELECT COUNT(*) FROM dl WHERE status=1");
    }

    public int failed() {
        return count("SELECT COUNT(*) FROM dl WHERE status=2");
    }

    public int events() {
        return count("SELECT COUNT(*) FROM ev");
    }

    /** 面板分页用：发件记录总数。 */
    public int countOut() {
        return count("SELECT COUNT(*) FROM out");
    }

    private int count(String sql) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(sql, null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    public String lastError() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT err FROM dl WHERE err<>'' ORDER BY id DESC LIMIT 1", null);
            if (c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "";
    }

    /** 面板用：最近的收信 + 每个通道的投递状态。 */
    public JSONArray recentJson(int limit) {
        return recentJson(limit, 0);
    }

    /** 分页：offset 从 0 起；limit/offset 由调用方钳制（WebPanel 侧 size 1~100）。 */
    public JSONArray recentJson(int limit, int offset) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,ts,peer,body,code,rule FROM ev ORDER BY id DESC LIMIT " + limit
                            + " OFFSET " + offset, null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long id = c.getLong(0);
                long ts = c.getLong(1);
                o.put("id", id);
                o.put("ts", ts);
                o.put("iso", iso(ts));
                o.put("peer", nz(c.getString(2)));
                o.put("body", nz(c.getString(3)));
                o.put("code", nz(c.getString(4)));
                String rule = nz(c.getString(5));
                if (rule.length() > 0) o.put("rule", rule);
                o.put("deliveries", deliveries(id));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    private JSONArray deliveries(long evId) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ch,status,tries,err,next_at FROM dl WHERE ev_id=? ORDER BY id",
                    new String[]{String.valueOf(evId)});
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ch", nz(c.getString(0)));
                o.put("status", c.getInt(1));       // 0 待发 / 1 已送达 / 2 放弃
                o.put("tries", c.getInt(2));
                o.put("err", nz(c.getString(3)));
                o.put("next_at", c.getLong(4));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    // ---------- 发信 ----------

    public long insertOut(String peer, String body, int parts) {
        try {
            ContentValues v = new ContentValues();
            v.put("ts", System.currentTimeMillis());
            v.put("peer", peer);
            v.put("body", body);
            v.put("parts", parts);
            v.put("status", 0);
            return getWritableDatabase().insert("out", null, v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** status: 1 已发出，2 失败。 */
    public void markOut(long id, int status, String err) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", status);
            v.put("err", err == null ? "" : (err.length() > 200 ? err.substring(0, 200) : err));
            getWritableDatabase().update("out", v, "id=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {
        }
    }

    public JSONArray outboxJson(int limit) {
        return outboxJson(limit, 0);
    }

    /** 分页：offset 从 0 起；limit/offset 由调用方钳制。 */
    public JSONArray outboxJson(int limit, int offset) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,ts,peer,body,parts,status,err FROM out ORDER BY id DESC LIMIT "
                            + limit + " OFFSET " + offset, null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long ts = c.getLong(1);
                o.put("id", c.getLong(0));
                o.put("ts", ts);
                o.put("iso", iso(ts));
                o.put("peer", nz(c.getString(2)));
                o.put("body", nz(c.getString(3)));
                o.put("parts", c.getInt(4));
                o.put("status", c.getInt(5));
                o.put("err", nz(c.getString(6)));
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    // ---------- 删除 ----------
    //
    // 设备上没有 sqlite3，面板是唯一能改库的入口，所以这里给到最小可用的清理能力：
    // 单条删（连它的投递一起删）、把「已放弃」的投递连根清掉、单条删发件记录。
    // 删 ev 前一定要先删 dl，否则 recentJson 的 LEFT 语义没做，会留下孤儿投递。

    /** 删一条收信及其全部投递记录。返回是否真的删掉了。 */
    public boolean deleteEvent(long id) {
        if (id <= 0) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            String[] a = new String[]{String.valueOf(id)};
            db.delete("dl", "ev_id=?", a);
            return db.delete("ev", "id=?", a) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 清掉所有「已放弃」的投递；连带删掉因此变成空壳的消息本体。
     * 只处理确实有过放弃投递的消息，绝不动「还没来得及建投递」的新消息。
     * 返回 {dl: 删掉的投递数, ev: 删掉的消息数}。
     */
    public int[] deleteGivenUp() {
        int dl = 0, ev = 0;
        List<Long> touched = new ArrayList<Long>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT ev_id FROM dl WHERE status=2", null);
            while (c.moveToNext()) touched.add(c.getLong(0));
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            dl = db.delete("dl", "status=2", null);
            for (int i = 0; i < touched.size(); i++) {
                String id = String.valueOf(touched.get(i));
                Cursor k = null;
                try {
                    k = db.rawQuery("SELECT COUNT(1) FROM dl WHERE ev_id=?", new String[]{id});
                    if (k.moveToFirst() && k.getInt(0) == 0) {
                        ev += db.delete("ev", "id=?", new String[]{id});
                    }
                } finally {
                    if (k != null) k.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{dl, ev};
    }

    /** 删一条发件记录。返回是否真的删掉了。 */
    public boolean deleteOut(long id) {
        if (id <= 0) return false;
        try {
            return getWritableDatabase().delete("out", "id=?",
                    new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已完成的接收记录保留 3 天；发件记录同样保留 3 天。 */
    public void prune() {
        try {
            long cut = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            SQLiteDatabase db = getWritableDatabase();
            db.delete("dl", "status=1 AND ev_id IN (SELECT id FROM ev WHERE ts<?)",
                    new String[]{String.valueOf(cut)});
            db.delete("ev", "ts<? AND id NOT IN (SELECT ev_id FROM dl WHERE status=0)",
                    new String[]{String.valueOf(cut)});
            db.delete("out", "ts<? AND status<>0", new String[]{String.valueOf(cut)});
        } catch (Throwable ignored) {
        }
    }

    /** 时间格式化（SysInbox 复用）。 */
    public static String iso(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(ts > 0 ? ts : System.currentTimeMillis()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
