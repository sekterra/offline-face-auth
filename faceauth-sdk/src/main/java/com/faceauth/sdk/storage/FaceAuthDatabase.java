package com.faceauth.sdk.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite 스키마 관리 — SSoT §6.1 기준.
 */
public final class FaceAuthDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME    = "faceauth.db";
    private static final int    DB_VERSION = 1;

    // ── 테이블: face_profile ─────────────────────────────────────────────
    static final String TABLE_PROFILE = "face_profile";
    static final String COL_PROFILE_ID   = "profile_id";
    static final String COL_USER_ID      = "user_id";
    static final String COL_PROFILE_TYPE = "profile_type";
    static final String COL_EMBEDDING    = "embedding";
    static final String COL_EMBEDDING_DIM = "embedding_dim";
    static final String COL_QUALITY_SCORE = "quality_score";
    static final String COL_CREATED_AT   = "created_at";
    static final String COL_MODEL_VERSION = "model_version";
    static final String COL_DEVICE_FP    = "device_fingerprint";
    static final String COL_IS_ACTIVE    = "is_active";

    // ── 테이블: auth_audit ───────────────────────────────────────────────
    static final String TABLE_AUDIT   = "auth_audit";
    static final String COL_AUDIT_ID  = "audit_id";
    static final String COL_TS        = "ts";
    static final String COL_RESULT    = "result";
    static final String COL_MATCHED_USER = "matched_user_id";
    static final String COL_MATCH_SCORE  = "match_score";
    static final String COL_DEBUG_JSON   = "debug_json";

    private static final String CREATE_PROFILE =
            "CREATE TABLE " + TABLE_PROFILE + " ("
                    + COL_PROFILE_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_USER_ID       + " TEXT NOT NULL, "
                    + COL_PROFILE_TYPE  + " TEXT NOT NULL CHECK(" + COL_PROFILE_TYPE + " IN ('NORMAL','HELMET')), "
                    + COL_EMBEDDING     + " BLOB NOT NULL, "
                    + COL_EMBEDDING_DIM + " INTEGER NOT NULL, "
                    + COL_QUALITY_SCORE + " REAL NOT NULL, "
                    + COL_CREATED_AT    + " INTEGER NOT NULL, "
                    + COL_MODEL_VERSION + " TEXT NOT NULL, "
                    + COL_DEVICE_FP     + " TEXT NULL, "
                    + COL_IS_ACTIVE     + " INTEGER NOT NULL DEFAULT 1"
                    + ")";

    private static final String CREATE_PROFILE_INDEX =
            "CREATE INDEX idx_fp_user ON " + TABLE_PROFILE
                    + "(" + COL_USER_ID + ", " + COL_IS_ACTIVE + ")";

    private static final String CREATE_AUDIT =
            "CREATE TABLE " + TABLE_AUDIT + " ("
                    + COL_AUDIT_ID     + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_TS           + " INTEGER NOT NULL, "
                    + COL_RESULT       + " TEXT NOT NULL, "
                    + COL_MATCHED_USER + " TEXT NULL, "
                    + COL_MATCH_SCORE  + " REAL NULL, "
                    + COL_DEBUG_JSON   + " TEXT NULL"
                    + ")";

    public FaceAuthDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        // WAL 모드 활성화 (읽기/쓰기 동시성 개선)
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_PROFILE);
        db.execSQL(CREATE_PROFILE_INDEX);
        db.execSQL(CREATE_AUDIT);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: 마이그레이션 전략 (model_version 관리)
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PROFILE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_AUDIT);
        onCreate(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
}
