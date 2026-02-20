package com.faceauth.sdk.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.faceauth.sdk.camera.AuthenticationActivity;
import com.faceauth.sdk.camera.EnrollmentActivity;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.storage.StorageManager;

import java.util.List;

/**
 * Offline FaceAuth SDK — Public Facade (SSoT 공개 API).
 *
 * <pre>
 * 초기화:
 *   FaceAuthSdk.initialize(context, FaceAuthConfig.builder().build());
 *
 * 등록:
 *   FaceAuthSdk.startEnrollment(activity, "user01",
 *       EnrollmentOptions.defaults(), result -> { ... });
 *
 * 인증:
 *   FaceAuthSdk.startAuthentication(activity, new AuthOptions(), result -> { ... });
 * </pre>
 *
 * ⚠️ 모든 Activity 시작 결과(callback)는 SDK 내부 Activity가 처리하므로
 *    호출 앱은 onActivityResult를 구현할 필요 없음.
 */
public final class FaceAuthSdk {

    private static volatile FaceAuthSdk INSTANCE;

    private final Context        appContext;
    private final FaceAuthConfig config;
    private final StorageManager storageManager;

    // ── 내부 콜백 보관 (Activity 간 전달) ────────────────────────────────
    private static volatile EnrollmentCallback pendingEnrollCallback;
    private static volatile AuthCallback       pendingAuthCallback;

    /** 내부용: 등록 콜백 설정 */
    public static void setPendingEnrollCallback(EnrollmentCallback cb) {
        pendingEnrollCallback = cb;
    }

    /** 내부용: 등록 콜백 반환 후 null로 초기화 (한 번만 소비) */
    public static EnrollmentCallback consumePendingEnrollCallback() {
        EnrollmentCallback cb = pendingEnrollCallback;
        pendingEnrollCallback = null;
        return cb;
    }

    /** 내부용: 인증 콜백 설정 */
    public static void setPendingAuthCallback(AuthCallback cb) {
        pendingAuthCallback = cb;
    }

    /** 내부용: 인증 콜백 반환 후 null로 초기화 (한 번만 소비) */
    public static AuthCallback consumePendingAuthCallback() {
        AuthCallback cb = pendingAuthCallback;
        pendingAuthCallback = null;
        return cb;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 초기화
    // ─────────────────────────────────────────────────────────────────────

    /**
     * SDK 초기화. Application.onCreate() 또는 최초 사용 전 1회 호출.
     *
     * @param context ApplicationContext (or any context)
     * @param config  설정값
     */
    public static void initialize(Context context, FaceAuthConfig config) {
        if (INSTANCE != null) {
            SafeLogger.w("FaceAuthSdk", "이미 초기화되었습니다. 중복 호출 무시.");
            return;
        }
        synchronized (FaceAuthSdk.class) {
            if (INSTANCE == null) {
                INSTANCE = new FaceAuthSdk(context.getApplicationContext(), config);
                SafeLogger.i("FaceAuthSdk", "SDK 초기화 완료 (modelVersion=" + config.modelVersion + ")");
            }
        }
    }

    private FaceAuthSdk(Context ctx, FaceAuthConfig cfg) {
        this.appContext     = ctx;
        this.config         = cfg;
        this.storageManager = StorageManager.getInstance(ctx, cfg);
    }

    private static FaceAuthSdk get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("FaceAuthSdk.initialize()를 먼저 호출하세요.");
        }
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 등록 시작 — 내장 EnrollmentActivity를 시작함.
     *
     * @param activity  호출 Activity
     * @param userId    등록할 로그인 ID
     * @param options   촬영 옵션 (EnrollmentOptions.defaults() 권장)
     * @param callback  완료 콜백 (Main Thread)
     */
    public static void startEnrollment(Activity activity,
                                       String userId,
                                       EnrollmentOptions options,
                                       EnrollmentCallback callback) {
        get(); // initialized check
        setPendingEnrollCallback(callback);

        Intent intent = new Intent(activity, EnrollmentActivity.class);
        intent.putExtra(EnrollmentActivity.EXTRA_USER_ID,         userId);
        intent.putExtra(EnrollmentActivity.EXTRA_NORMAL_REQUIRED, options.normalRequired);
        intent.putExtra(EnrollmentActivity.EXTRA_HELMET_REQUIRED, options.helmetRequired);
        activity.startActivity(intent);
    }

    /**
     * 인증 시작 — 내장 AuthenticationActivity를 시작함.
     *
     * @param activity 호출 Activity
     * @param options  인증 옵션
     * @param callback 완료 콜백 (Main Thread)
     */
    public static void startAuthentication(Activity activity,
                                           AuthOptions options,
                                           AuthCallback callback) {
        get();
        setPendingAuthCallback(callback);

        Intent intent = new Intent(activity, AuthenticationActivity.class);
        activity.startActivity(intent);
    }

    /**
     * 등록된 사용자 ID 목록 반환.
     * I/O 포함 → 백그라운드 스레드에서 호출 권장.
     */
    public static List<String> listEnrolledUsers() {
        return get().storageManager.listEnrolledUsers();
    }

    /**
     * 특정 사용자 데이터 삭제 (face_profile is_active = 0).
     */
    public static void deleteUser(String userId) {
        get().storageManager.deleteUser(userId);
        SafeLogger.i("FaceAuthSdk", "사용자 삭제 완료");
    }

    /**
     * 전체 데이터 초기화.
     */
    public static void resetAll() {
        get().storageManager.resetAll();
        SafeLogger.w("FaceAuthSdk", "전체 데이터 초기화 완료");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal accessors (SDK 내부 전용)
    // ─────────────────────────────────────────────────────────────────────

    /** @hide */
    public static FaceAuthConfig getConfig() { return get().config; }

    /** @hide */
    public static StorageManager getStorageManager() { return get().storageManager; }
}
