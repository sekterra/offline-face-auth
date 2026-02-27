package com.faceauth.sdk.logging;

import com.faceauth.sdk.api.ErrorCode;
import com.faceauth.sdk.api.FaceAuthException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSoT: /docs/FACEAUTH_EXCEPTION_HANDLING_SSOT.md
 * auth_error 표준 JSON 이벤트 로깅. 예외 삼키지 않음(호출자가 재전파/실패 처리).
 */
public final class AuthErrorLogger {

    private static final String TAG = "AuthError";
    private static final String EVENT = "auth_error";

    private AuthErrorLogger() {}

    /**
     * auth_error 한 건 로그. 단일 라인 JSON.
     *
     * @param errorCode 필수
     * @param message   예외 메시지 요약 (PII 제외)
     * @param context   선택: where, screen, state, trackingId, facesCount, roiCandidateCount, bboxAreaRatio, usedYaw 등
     */
    public static void log(ErrorCode errorCode, String message, Map<String, Object> context) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", EVENT);
        map.put("errorCode", errorCode.name());
        map.put("message", message != null ? message : "");
        if (context != null) {
            for (Map.Entry<String, Object> e : context.entrySet()) {
                if (e.getValue() != null) map.put(e.getKey(), e.getValue());
            }
        }
        String json = toJson(map);
        SafeLogger.e(TAG, json);
    }

    /**
     * Throwable 기반: ErrorMapper로 ErrorCode 결정 후 로그.
     */
    public static void log(Throwable t, String where, Map<String, Object> context) {
        ErrorCode code = ErrorMapper.map(t, where);
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (context != null) {
            for (Map.Entry<String, Object> e : context.entrySet()) {
                if (e.getValue() != null) ctx.put(e.getKey(), e.getValue());
            }
        }
        if (where != null) ctx.put("where", where);
        if (t instanceof FaceAuthException && ((FaceAuthException) t).getWhere() != null) {
            ctx.put("where", ((FaceAuthException) t).getWhere());
        }
        log(code, msg, ctx);
    }

    public static void log(Throwable t, String where) {
        log(t, where, null);
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(escape(String.valueOf(v))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
