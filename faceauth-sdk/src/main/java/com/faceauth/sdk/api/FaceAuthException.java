package com.faceauth.sdk.api;

import androidx.annotation.Nullable;

/**
 * SSoT: /docs/FACEAUTH_EXCEPTION_HANDLING_SSOT.md
 * FaceAuth 표준 런타임 예외. errorCode + where(선택) + cause.
 */
public class FaceAuthException extends RuntimeException {

    private final ErrorCode errorCode;
    @Nullable private final String where;

    public FaceAuthException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.where = null;
    }

    public FaceAuthException(ErrorCode errorCode, String message, @Nullable String where) {
        super(message);
        this.errorCode = errorCode;
        this.where = where;
    }

    public FaceAuthException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.where = null;
    }

    public FaceAuthException(ErrorCode errorCode, String message, @Nullable String where, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.where = where;
    }

    public ErrorCode getErrorCode() { return errorCode; }
    @Nullable public String getWhere() { return where; }
}
