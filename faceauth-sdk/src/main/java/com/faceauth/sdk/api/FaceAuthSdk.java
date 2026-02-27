package com.faceauth.sdk.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.faceauth.sdk.camera.AuthenticationActivity;
import com.faceauth.sdk.camera.EnrollmentActivity;
import com.faceauth.sdk.logging.FileLogger;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.storage.EnrolledUserDebugRow;
import com.faceauth.sdk.storage.StorageManager;

import java.util.List;
import java.util.concurrent.Executors;

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
    public static volatile EnrollmentCallback pendingEnrollCallback;
    public static volatile AuthCallback       pendingAuthCallback;

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
                FileLogger.init(context.getApplicationContext());
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
        pendingEnrollCallback = callback;
        Intent intent = getEnrollmentIntent(activity, userId, options);
        activity.startActivity(intent);
    }

    /**
     * 등록 화면 Intent 반환. Activity Result API 사용 시 사용.
     * registerForActivityResult(StartActivityForResult(), ...) 후 이 Intent로 launch 하면
     * 등록 성공 시 setResult(EXTRA_ENROLL_SUCCESS, EXTRA_ENROLLED_ID) 로 결과 수신 가능.
     */
    public static Intent getEnrollmentIntent(Activity activity,
                                             String userId,
                                             EnrollmentOptions options) {
        get();
        Intent intent = new Intent(activity, EnrollmentActivity.class);
        intent.putExtra(EnrollmentActivity.EXTRA_USER_ID,         userId);
        intent.putExtra(EnrollmentActivity.EXTRA_NORMAL_REQUIRED, options.normalRequired);
        intent.putExtra(EnrollmentActivity.EXTRA_HELMET_REQUIRED, options.helmetRequired);
        return intent;
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
        pendingAuthCallback = callback;

        Intent intent = new Intent(activity, AuthenticationActivity.class);
        activity.startActivity(intent);
    }

    /**
     * 라이브니스 검증 시작 — 머리 돌리기(LEFT/RIGHT) 전용 LivenessActivity.
     */
    public static void startLiveness(Activity activity) {
        get();
        Intent intent = new Intent(activity, com.faceauth.sdk.camera.LivenessActivity.class);
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
     * 등록된 인물 DB 데이터를 TXT 내보내기용 문자열로 반환.
     * I/O 포함 → 백그라운드 스레드에서 호출 권장.
     */
    public static String getEnrolledDataExportText() {
        return get().storageManager.exportEnrolledDataAsText();
    }

    /**
     * 등록 DB 증거 디버그용. loadEnrolledUserDebugRows와 동일 DAO 사용 (SSoT).
     * 콜백은 메인 스레드에서 호출됨.
     */
    public static void getEnrolledUserDebugRows(int maxUsers, java.util.function.Consumer<List<EnrolledUserDebugRow>> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<EnrolledUserDebugRow> rows = get().storageManager.loadEnrolledUserDebugRows(maxUsers);
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(rows));
        });
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
