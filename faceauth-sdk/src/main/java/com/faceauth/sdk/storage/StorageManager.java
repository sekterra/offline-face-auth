package com.faceauth.sdk.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.logging.SafeLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQLite + Keystore 암호화 통합 관리자.
 * 모든 메서드는 백그라운드 스레드에서 호출 가능 (직렬화 보장).
 */
public final class StorageManager {

    private static final String TAG = "StorageManager";

    private static volatile StorageManager INSTANCE;

    private final FaceAuthDatabase db;
    private final EmbeddingCrypto  crypto;
    private final FaceAuthConfig   config;
    private final ExecutorService  ioExecutor;  // 직렬 I/O

    private StorageManager(Context ctx, FaceAuthConfig config) throws CryptoException {
        this.config     = config;
        this.db         = new FaceAuthDatabase(ctx);
        this.crypto     = new EmbeddingCrypto();
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FaceAuth-IO");
            t.setDaemon(true);
            return t;
        });
    }

    public static StorageManager getInstance(Context ctx, FaceAuthConfig config) {
        if (INSTANCE == null) {
            synchronized (StorageManager.class) {
                if (INSTANCE == null) {
                    try {
                        INSTANCE = new StorageManager(ctx.getApplicationContext(), config);
                    } catch (CryptoException e) {
                        throw new RuntimeException("StorageManager 초기화 실패", e);
                    }
                }
            }
        }
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 쓰기
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 얼굴 프로파일 저장 (embedding 암호화 후 BLOB 저장).
     *
     * @param userId      로그인 ID
     * @param profileType "NORMAL" | "HELMET"
     * @param embedding   L2 정규화된 float[] (원본, 저장 후 메모리 유지 금지)
     * @param qualityScore 품질 점수
     * @return 삽입된 profile_id
     */
    public long saveProfile(String userId, String profileType,
                             float[] embedding, float qualityScore) {
        try {
            byte[] encBlob = crypto.encrypt(embedding);
            ContentValues cv = new ContentValues();
            cv.put(FaceAuthDatabase.COL_USER_ID,       userId);
            cv.put(FaceAuthDatabase.COL_PROFILE_TYPE,  profileType);
            cv.put(FaceAuthDatabase.COL_EMBEDDING,     encBlob);
            cv.put(FaceAuthDatabase.COL_EMBEDDING_DIM, embedding.length);
            cv.put(FaceAuthDatabase.COL_QUALITY_SCORE, qualityScore);
            cv.put(FaceAuthDatabase.COL_CREATED_AT,    System.currentTimeMillis());
            cv.put(FaceAuthDatabase.COL_MODEL_VERSION, config.modelVersion);
            cv.put(FaceAuthDatabase.COL_IS_ACTIVE,     1);

            long id = db.getWritableDatabase().insert(FaceAuthDatabase.TABLE_PROFILE, null, cv);
            SafeLogger.d(TAG, "프로파일 저장 완료 (profileId=" + id + ", type=" + profileType + ")");
            return id;
        } catch (CryptoException e) {
            SafeLogger.e(TAG, "프로파일 암호화 실패", e);
            return -1;
        }
    }

    /** auth_audit 기록 (POC 모드에서만 debug_json 저장) */
    public void saveAudit(String result, String matchedUserId,
                           float matchScore, String debugJson) {
        ContentValues cv = new ContentValues();
        cv.put(FaceAuthDatabase.COL_TS,           System.currentTimeMillis());
        cv.put(FaceAuthDatabase.COL_RESULT,        result);
        cv.put(FaceAuthDatabase.COL_MATCHED_USER,  matchedUserId);
        cv.put(FaceAuthDatabase.COL_MATCH_SCORE,   matchScore);
        if (config.pocMode && debugJson != null) {
            cv.put(FaceAuthDatabase.COL_DEBUG_JSON, debugJson);
        }
        db.getWritableDatabase().insert(FaceAuthDatabase.TABLE_AUDIT, null, cv);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 읽기
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 모든 활성 프로파일 조회 (인증 시 사용).
     * 임베딩을 복호화하여 ProfileRecord로 반환.
     */
    public List<ProfileRecord> loadAllActiveProfiles() {
        List<ProfileRecord> list = new ArrayList<>();
        SQLiteDatabase rdb = db.getReadableDatabase();

        String[] cols = {
                FaceAuthDatabase.COL_PROFILE_ID,
                FaceAuthDatabase.COL_USER_ID,
                FaceAuthDatabase.COL_PROFILE_TYPE,
                FaceAuthDatabase.COL_EMBEDDING,
                FaceAuthDatabase.COL_EMBEDDING_DIM,
                FaceAuthDatabase.COL_QUALITY_SCORE,
                FaceAuthDatabase.COL_CREATED_AT,
                FaceAuthDatabase.COL_MODEL_VERSION
        };

        try (Cursor c = rdb.query(
                FaceAuthDatabase.TABLE_PROFILE, cols,
                FaceAuthDatabase.COL_IS_ACTIVE + "=1", null,
                null, null, null)) {

            while (c.moveToNext()) {
                try {
                    byte[]  blob     = c.getBlob(3);
                    float[] emb      = crypto.decrypt(blob);
                    int     dim      = c.getInt(4);

                    // 차원 검증
                    if (emb.length != dim) {
                        SafeLogger.w(TAG, "임베딩 차원 불일치, 스킵");
                        continue;
                    }

                    list.add(new ProfileRecord(
                            c.getLong(0),   // profileId
                            c.getString(1), // userId
                            c.getString(2), // profileType
                            emb,
                            dim,
                            c.getFloat(5),  // qualityScore
                            c.getLong(6),   // createdAt
                            c.getString(7)  // modelVersion
                    ));
                } catch (CryptoException e) {
                    SafeLogger.e(TAG, "임베딩 복호화 실패, 스킵");
                }
            }
        }
        return list;
    }

    /** 등록된 user_id 목록 (중복 제거) */
    public List<String> listEnrolledUsers() {
        List<String> users = new ArrayList<>();
        SQLiteDatabase rdb = db.getReadableDatabase();
        try (Cursor c = rdb.rawQuery(
                "SELECT DISTINCT " + FaceAuthDatabase.COL_USER_ID
                        + " FROM " + FaceAuthDatabase.TABLE_PROFILE
                        + " WHERE " + FaceAuthDatabase.COL_IS_ACTIVE + "=1", null)) {
            while (c.moveToNext()) users.add(c.getString(0));
        }
        return users;
    }

    /** 특정 userId의 NORMAL/HELMET 카운트 반환 */
    public int[] getProfileCount(String userId) {
        // [0]=NORMAL, [1]=HELMET
        int[] counts = {0, 0};
        SQLiteDatabase rdb = db.getReadableDatabase();
        for (int i = 0; i < 2; i++) {
            String type = (i == 0) ? "NORMAL" : "HELMET";
            try (Cursor c = rdb.rawQuery(
                    "SELECT COUNT(*) FROM " + FaceAuthDatabase.TABLE_PROFILE
                            + " WHERE " + FaceAuthDatabase.COL_USER_ID + "=?"
                            + " AND "   + FaceAuthDatabase.COL_PROFILE_TYPE + "=?"
                            + " AND "   + FaceAuthDatabase.COL_IS_ACTIVE + "=1",
                    new String[]{userId, type})) {
                if (c.moveToFirst()) counts[i] = c.getInt(0);
            }
        }
        return counts;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 삭제
    // ─────────────────────────────────────────────────────────────────────

    /** 논리 삭제 (is_active = 0) */
    public void deleteUser(String userId) {
        ContentValues cv = new ContentValues();
        cv.put(FaceAuthDatabase.COL_IS_ACTIVE, 0);
        db.getWritableDatabase().update(
                FaceAuthDatabase.TABLE_PROFILE, cv,
                FaceAuthDatabase.COL_USER_ID + "=?",
                new String[]{userId});
    }

    /** 전체 데이터 물리 삭제 */
    public void resetAll() {
        SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.delete(FaceAuthDatabase.TABLE_PROFILE, null, null);
        wdb.delete(FaceAuthDatabase.TABLE_AUDIT,   null, null);
    }
}
