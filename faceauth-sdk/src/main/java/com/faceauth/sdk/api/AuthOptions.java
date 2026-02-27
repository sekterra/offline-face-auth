package com.faceauth.sdk.api;

/** 인증 옵션 (추후 확장 대비) */
public final class AuthOptions {
    /** 최근 사용 우선 후보 필터링 사용 여부 (기본 off) */
    public final boolean recentUserFirst;

    public AuthOptions() { this.recentUserFirst = false; }
    public AuthOptions(boolean recentUserFirst) { this.recentUserFirst = recentUserFirst; }
}
