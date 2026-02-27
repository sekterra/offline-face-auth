package com.faceauth.sdk.embedding;

public class EmbeddingException extends Exception {
    /** TFLITE_INFER_FAIL, TFLITE_PRECHECK_FAIL, TFLITE_INPUT_SHAPE_MISMATCH 등 */
    public final String errorCode;

    public EmbeddingException(String msg) { super(msg); this.errorCode = null; }
    public EmbeddingException(String msg, Throwable cause) { super(msg, cause); this.errorCode = null; }
    public EmbeddingException(String errorCode, String msg, Throwable cause) {
        super(msg, cause);
        this.errorCode = errorCode;
    }
    public EmbeddingException(String errorCode, String msg) {
        super(msg);
        this.errorCode = errorCode;
    }
}
