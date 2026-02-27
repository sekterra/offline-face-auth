package com.faceauth.sdk.logging;

import android.util.Log;

/**
 * PII-safe 로거.
 * - user_id, embedding, match_score 원값 로그 출력 금지
 * - Release 빌드에서 VERBOSE/DEBUG 출력 억제
 */
public final class SafeLogger {

    private static final String SDK_TAG_PREFIX = "FaceAuth/";
    private static boolean debugEnabled = false;  // initialize()에서 설정

    private SafeLogger() {}

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void v(String tag, String msg) {
        if (debugEnabled) Log.v(SDK_TAG_PREFIX + tag, sanitize(msg));
    }

    public static void d(String tag, String msg) {
        if (debugEnabled) Log.d(SDK_TAG_PREFIX + tag, sanitize(msg));
    }

    public static void i(String tag, String msg) {
        Log.i(SDK_TAG_PREFIX + tag, sanitize(msg));
    }

    public static void w(String tag, String msg) {
        Log.w(SDK_TAG_PREFIX + tag, sanitize(msg));
    }

    public static void e(String tag, String msg) {
        Log.e(SDK_TAG_PREFIX + tag, sanitize(msg));
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(SDK_TAG_PREFIX + tag, sanitize(msg), t);
    }

    /**
     * 민감 정보 패턴 제거.
     * 실제 운영에서는 정규식/어노테이션 기반 필터로 확장 가능.
     */
    private static String sanitize(String msg) {
        if (msg == null) return "(null)";
        // 수치 배열(embedding 출력) 패턴 마스킹: [0.123, -0.456, ...] → [EMBEDDING]
        return msg.replaceAll("\\[-?\\d+\\.\\d+(,\\s*-?\\d+\\.\\d+)+]", "[EMBEDDING_MASKED]");
    }
}
