package com.sp970.smsrelay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息表 ev + 投递表 dl。
 *
 * 一条短信对应多个通道各一条 dl，某通道失败不影响其它通道，
 * 失败通道按自己的退避节奏重试。
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "relay.db";
    private static final int VER = 2;

    public Db(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ev (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "hash TEXT UNIQUE, ts INTEGER, peer TEXT, body TEXT, code TEXT)");
        db.execSQL("CREATE TABLE dl (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ev_id INTEGER, ch TEXT, status INTEGER DEFAULT 0, tries INTEGER DEFAULT 0," +
                "next_at INTEGER DEFAULT 0, err TEXT DEFAULT '', UNIQUE(ev_id, ch))");
        db.execSQL("CREATE INDEX idx_dl ON dl(status, next_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS ev");
            db.execSQL("DROP TABLE IF EXISTS dl");
            onCreate(db);
        }
    }

    /** 入队消息本体，重复则返回已有 id。 */
    public long insertEvent(SmsEvent e) {
        try {
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

    /** 为每个通道建一条投递记录（已存在则跳过）。 */
    public void ensureDeliveries(long evId, List<String> channels) {
        if (evId <= 0) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            for (int i = 0; i < channels.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("ev_id", evId);
                v.put("ch", channels.get(i));
                v.put("status", 0);
                v.put("tries", 0);
                v.put("next_at", 0);
                db.insertWithOnConflict("dl", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Throwable ignored) {
        }
    }

    public List<Deliver> due(int limit) {
        List<Deliver> out = new ArrayList<Deliver>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT d.id,d.ev_id,d.ch,e.ts,e.peer,e.body,e.code,d.tries,d.err " +
                            "FROM dl d JOIN ev e ON e.id=d.ev_id " +
                            "WHERE d.status=0 AND d.next_at<=? ORDER BY d.id LIMIT " + limit,
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

    /** 失败退避：1/2/4/8/16/30 分钟，10 次后放弃（记录保留便于排查）。 */
    public void markFail(long dlId, int tries, String err) {
        try {
            int t = tries + 1;
            long backoff = 60L * 1000L * Math.min(30, 1 << Math.min(t - 1, 5));
            ContentValues v = new ContentValues();
            v.put("tries", t);
            v.put("err", err == null ? "" : (err.length() > 300 ? err.substring(0, 300) : err));
            v.put("next_at", System.currentTimeMillis() + backoff);
            v.put("status", t >= 10 ? 2 : 0);
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Throwable ignored) {
        }
    }

    public int pending() {
        return count("SELECT COUNT(*) FROM dl WHERE status=0");
    }

    public int sent() {
        return count("SELECT COUNT(*) FROM dl WHERE status=1");
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

    /** 已完成的记录保留 3 天。 */
    public void prune() {
        try {
            long cut = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            SQLiteDatabase db = getWritableDatabase();
            db.delete("dl", "status=1 AND ev_id IN (SELECT id FROM ev WHERE ts<?)",
                    new String[]{String.valueOf(cut)});
            db.delete("ev", "ts<? AND id NOT IN (SELECT ev_id FROM dl WHERE status=0)",
                    new String[]{String.valueOf(cut)});
        } catch (Throwable ignored) {
        }
    }
}
