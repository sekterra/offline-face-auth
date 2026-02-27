package com.faceauth.sdk.api;

/**
 * SSoT: /docs/FACEAUTH_EXCEPTION_HANDLING_SSOT.md
 * 표준 오류 코드. auth_error 로그에 사용.
 */
public enum ErrorCode {
    DETECTION_FAIL,
    EMBEDDING_FAIL,
    STORAGE_FAIL,
    CRYPTO_FAIL,
    LIVENESS_FAIL,
    INTERNAL,
    UNKNOWN
}
