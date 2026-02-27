package com.faceauth.sdk.logging;

import com.faceauth.sdk.api.FaceAuthException;
import com.faceauth.sdk.api.ErrorCode;
import com.faceauth.sdk.detection.DetectionException;
import com.faceauth.sdk.embedding.EmbeddingException;
import com.faceauth.sdk.storage.CryptoException;

/**
 * SSoT: /docs/FACEAUTH_EXCEPTION_HANDLING_SSOT.md
 * Throwable + context → ErrorCode.
 */
public final class ErrorMapper {

    private ErrorMapper() {}

    /**
     * @param t       예외
     * @param context 선택적 위치 (where), null 가능
     * @return 매핑된 ErrorCode
     */
    public static ErrorCode map(Throwable t, String context) {
        if (t instanceof FaceAuthException) {
            return ((FaceAuthException) t).getErrorCode();
        }
        if (t instanceof DetectionException) return ErrorCode.DETECTION_FAIL;
        if (t instanceof EmbeddingException) return ErrorCode.EMBEDDING_FAIL;
        if (t instanceof CryptoException) return ErrorCode.CRYPTO_FAIL;
        if (t.getCause() != null) return map(t.getCause(), context);
        return ErrorCode.UNKNOWN;
    }

    public static ErrorCode map(Throwable t) {
        return map(t, null);
    }
}
