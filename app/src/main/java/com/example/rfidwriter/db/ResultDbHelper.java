package com.example.rfidwriter.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 写标结果本地持久化（SQLite）。
 * 作用：写标中途退出不丢进度；导出 JSON 供 dws 同步；后续复盘/补写。
 */
public class ResultDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "rfid_writer.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "write_results";

    public ResultDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "asset_no TEXT PRIMARY KEY, " +
                "dept TEXT, model TEXT, remark TEXT, " +
                "tid TEXT, epc TEXT, written_data TEXT, " +
                "status TEXT, error TEXT, written_at TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void upsert(WriteResult r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("asset_no", r.assetNo);
        v.put("dept", r.dept);
        v.put("model", r.model);
        v.put("remark", r.remark);
        v.put("tid", r.tid);
        v.put("epc", r.epc);
        v.put("written_data", r.writtenData);
        v.put("status", r.status);
        v.put("error", r.error);
        v.put("written_at", r.writtenAt);
        db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    public List<WriteResult> getAll() {
        List<WriteResult> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "asset_no ASC");
        while (c.moveToNext()) {
            WriteResult r = new WriteResult();
            r.assetNo = c.getString(c.getColumnIndexOrThrow("asset_no"));
            r.dept = c.getString(c.getColumnIndexOrThrow("dept"));
            r.model = c.getString(c.getColumnIndexOrThrow("model"));
            r.remark = c.getString(c.getColumnIndexOrThrow("remark"));
            r.tid = c.getString(c.getColumnIndexOrThrow("tid"));
            r.epc = c.getString(c.getColumnIndexOrThrow("epc"));
            r.writtenData = c.getString(c.getColumnIndexOrThrow("written_data"));
            r.status = c.getString(c.getColumnIndexOrThrow("status"));
            r.error = c.getString(c.getColumnIndexOrThrow("error"));
            r.writtenAt = c.getString(c.getColumnIndexOrThrow("written_at"));
            list.add(r);
        }
        c.close();
        db.close();
        return list;
    }

    public int countByStatus(String status) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"asset_no"}, "status=?", new String[]{status}, null, null, null);
        int n = c.getCount();
        c.close();
        db.close();
        return n;
    }

    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, null, null);
        db.close();
    }
}
