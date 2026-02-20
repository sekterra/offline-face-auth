package com.faceauth.sdk.api;

/** 인증 완료 콜백 — 항상 Main Thread에서 호출 */
public interface AuthCallback {
    void onResult(AuthResult result);
}
