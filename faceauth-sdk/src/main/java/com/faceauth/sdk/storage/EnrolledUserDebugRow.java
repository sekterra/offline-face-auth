package com.faceauth.sdk.storage;

/**
 * 등록 DB 증거 디버그용 한 사용자 행 (embeddingCount, dim, norm, hash, first5).
 * loadEnrolledUserDebugRows()와 동일 DAO/쿼리로 로드 (SSoT).
 */
public final class EnrolledUserDebugRow {
    public final String id;
    public final int    embeddingCount;
    public final int    embeddingDim;
    public final double norm;
    public final String embeddingHash;
    public final String first5;

    public EnrolledUserDebugRow(String id, int embeddingCount, int embeddingDim,
                                double norm, String embeddingHash, String first5) {
        this.id             = id;
        this.embeddingCount = embeddingCount;
        this.embeddingDim   = embeddingDim;
        this.norm           = norm;
        this.embeddingHash  = embeddingHash;
        this.first5         = first5;
    }
}
