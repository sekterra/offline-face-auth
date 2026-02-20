package com.faceauth.sdk.storage;

/**
 * DB 조회 결과 모델 (face_profile 한 행).
 * embedding은 복호화 후 float[] 상태.
 */
public final class ProfileRecord {
    public final long   profileId;
    public final String userId;
    public final String profileType;   // NORMAL | HELMET
    public final float[] embedding;
    public final int    embeddingDim;
    public final float  qualityScore;
    public final long   createdAt;
    public final String modelVersion;

    public ProfileRecord(long profileId, String userId, String profileType,
                         float[] embedding, int embeddingDim,
                         float qualityScore, long createdAt, String modelVersion) {
        this.profileId    = profileId;
        this.userId       = userId;
        this.profileType  = profileType;
        this.embedding    = embedding;
        this.embeddingDim = embeddingDim;
        this.qualityScore = qualityScore;
        this.createdAt    = createdAt;
        this.modelVersion = modelVersion;
    }
}
