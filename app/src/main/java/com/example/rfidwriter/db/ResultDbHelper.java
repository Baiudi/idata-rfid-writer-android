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
    private static final int DB_VERSION = 2;
    private static final String TABLE = "write_results";

    public ResultDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "asset_no TEXT PRIMARY KEY, " +
                "asset_name TEXT, dept TEXT, model TEXT, remark TEXT, " +
                "category TEXT, purchase_date TEXT, purchase_price TEXT, " +
                "tid TEXT, epc TEXT, written_data TEXT, " +
                "status TEXT, error TEXT, written_at TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // v2：新增字段，保留既有盘点数据
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN asset_name TEXT");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN category TEXT");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN purchase_date TEXT");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN purchase_price TEXT");
        }
    }

    public void upsert(WriteResult r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("asset_no", r.assetNo);
        v.put("asset_name", r.assetName);
        v.put("dept", r.dept);
        v.put("model", r.model);
        v.put("remark", r.remark);
        v.put("category", r.category);
        v.put("purchase_date", r.purchaseDate);
        v.put("purchase_price", r.purchasePrice);
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
            r.assetNo = str(c, "asset_no");
            r.assetName = str(c, "asset_name");
            r.dept = str(c, "dept");
            r.model = str(c, "model");
            r.remark = str(c, "remark");
            r.category = str(c, "category");
            r.purchaseDate = str(c, "purchase_date");
            r.purchasePrice = str(c, "purchase_price");
            r.tid = str(c, "tid");
            r.epc = str(c, "epc");
            r.writtenData = str(c, "written_data");
            r.status = str(c, "status");
            r.error = str(c, "error");
            r.writtenAt = str(c, "written_at");
            list.add(r);
        }
        c.close();
        db.close();
        return list;
    }

    /** 容错读取：列不存在或值为 null 时返回空串，避免旧版本数据库残留导致启动崩溃 */
    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    /** 编码是否已存在（用于自动生成时查重） */
    public boolean exists(String assetNo) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"asset_no"}, "asset_no=?", new String[]{assetNo}, null, null, null);
        boolean ok = c.getCount() > 0;
        c.close();
        db.close();
        return ok;
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
