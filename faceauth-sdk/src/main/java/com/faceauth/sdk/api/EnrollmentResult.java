package com.faceauth.sdk.api;

// ─────────────────────────────────────────────────────────────────────────────
// 등록 결과
// ─────────────────────────────────────────────────────────────────────────────
public final class EnrollmentResult {

    public enum Status { SUCCESS, PARTIAL, FAILED }

    public final String userId;
    public final int    normalCount;   // 0~3
    public final int    helmetCount;   // 0~3
    public final Status status;
    public final String message;       // 사용자 안내 메시지 (한국어)

    public EnrollmentResult(String userId, int normalCount, int helmetCount,
                            Status status, String message) {
        this.userId       = userId;
        this.normalCount  = normalCount;
        this.helmetCount  = helmetCount;
        this.status       = status;
        this.message      = message;
    }

    @Override public String toString() {
        return "EnrollmentResult{userId=" + userId
                + ", normal=" + normalCount + "/3"
                + ", helmet=" + helmetCount + "/3"
                + ", status=" + status + "}";
    }
}
