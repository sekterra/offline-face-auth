package com.faceauth.sdk.api;

/**
 * SSOT: 실시간 검증 파이프라인 상태.
 * UI 상태 라벨에 표시할 메시지는 getDisplayMessage()로 통일.
 */
public enum VerificationState {
    IDLE,
    /** 얼굴/정면은 만족하나 안정 프레임 미달. 상태 메시지 영역용. */
    STABILIZING,
    FIRST_VERIFY_RUNNING,
    SECONDARY_VERIFY_RUNNING,
    SECONDARY_VERIFY_DONE,
    ACCEPT,
    REJECT,
    UNCERTAIN;

    public String getDisplayMessage() {
        switch (this) {
            case FIRST_VERIFY_RUNNING:
                return "1차 검증 수행 중";
            case SECONDARY_VERIFY_RUNNING:
                return "2차 검증 수행";
            case SECONDARY_VERIFY_DONE:
                return "2차 검증 수행 완료";
            case ACCEPT:
                return "인식 완료";
            case REJECT:
                return "인식 실패";
            case UNCERTAIN:
                return "다시 시도";
            case STABILIZING:
                return "안정화 대기 중";
            case IDLE:
            default:
                return "";
        }
    }
}
