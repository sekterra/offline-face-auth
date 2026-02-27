package com.faceauth.sdk.api;

// ─────────────────────────────────────────────────────────────────────────────
// 인증 실패 원인
// ─────────────────────────────────────────────────────────────────────────────
public enum FailureReason {
    FAIL_QUALITY,    // 품질 게이트 미통과
    FAIL_LIVENESS,   // 눈깜빡임 챌린지 실패
    FAIL_MATCH,      // match_score 미달 또는 2차 검증 실패
    FAIL_LOW_MARGIN, // 그레이존에서 margin < M_AMBIGUOUS → "다시 시도"
    FAIL_CAMERA,     // 카메라 권한 없음 / 초기화 실패
    FAIL_INTERNAL    // TFLite/DB 내부 오류
}
