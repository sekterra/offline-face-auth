package com.faceauth.sdk.api;

/** 등록 완료 콜백 — 항상 Main Thread에서 호출 */
public interface EnrollmentCallback {
    void onResult(EnrollmentResult result);
}
