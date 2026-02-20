package com.faceauth.sdk.api;

/** 등록 옵션 */
public final class EnrollmentOptions {
    public final int normalRequired;   // default 3
    public final int helmetRequired;   // default 3

    private EnrollmentOptions(int normal, int helmet) {
        this.normalRequired = normal;
        this.helmetRequired = helmet;
    }

    public static EnrollmentOptions defaults() {
        return new EnrollmentOptions(3, 3);
    }

    public static EnrollmentOptions of(int normal, int helmet) {
        if (normal < 1 || helmet < 1) throw new IllegalArgumentException("최소 1장 이상 필요");
        return new EnrollmentOptions(normal, helmet);
    }
}
