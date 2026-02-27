package com.faceauth.sdk.api;

/**
 * 인증 결과.
 * status == SUCCESS  → matchedUserId, matchScore 유효
 * status == FAILED   → failureReason, message 참조
 */
public final class AuthResult {

    public enum Status { SUCCESS, FAILED }

    public final Status        status;
    public final String        matchedUserId;  // SUCCESS 시 non-null
    public final float         matchScore;     // 0.0 ~ 1.0
    public final FailureReason failureReason;  // FAILED 시 non-null
    public final String        message;        // 사용자 안내 메시지 (한국어)

    /** 성공 결과 생성 */
    public static AuthResult success(String userId, float score) {
        return new AuthResult(Status.SUCCESS, userId, score, null,
                "안면 인식에 성공했습니다.");
    }

    /** 실패 결과 생성 */
    public static AuthResult failure(FailureReason reason, float score, String message) {
        return new AuthResult(Status.FAILED, null, score, reason, message);
    }

    private AuthResult(Status status, String matchedUserId, float matchScore,
                       FailureReason failureReason, String message) {
        this.status        = status;
        this.matchedUserId = matchedUserId;
        this.matchScore    = matchScore;
        this.failureReason = failureReason;
        this.message       = message;
    }

    @Override public String toString() {
        return "AuthResult{status=" + status
                + (matchedUserId != null ? ", user=" + matchedUserId : "")
                + ", score=" + String.format("%.3f", matchScore)
                + (failureReason != null ? ", reason=" + failureReason : "")
                + "}";
    }
}
