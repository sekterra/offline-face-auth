package com.faceauth.sdk.matcher;

import com.faceauth.sdk.storage.ProfileRecord;

import java.util.List;

/**
 * 코사인 유사도 기반 Top-1 매처.
 *
 * SSoT §3 정의:
 *   cosine_sim = cos(live, saved)               ∈ [-1, 1]
 *   match_score = (cosine_sim + 1) / 2          ∈ [0,  1]
 *   통과 조건: match_score >= config.matchThreshold (0.80)
 */
public final class EmbeddingMatcher {

    private EmbeddingMatcher() {}

    public static final class MatchResult {
        public final ProfileRecord bestProfile;   // null = 후보 없음
        public final float         matchScore;    // 0.0 ~ 1.0

        MatchResult(ProfileRecord profile, float score) {
            this.bestProfile = profile;
            this.matchScore  = score;
        }
    }

    /**
     * 라이브 임베딩과 저장된 프로파일 목록 비교 → Top-1 반환.
     *
     * @param liveEmbedding L2 정규화된 라이브 임베딩
     * @param candidates    DB에서 조회한 활성 프로파일 목록
     * @return MatchResult (candidates가 비어있으면 score=0, profile=null)
     */
    public static MatchResult findTopMatch(float[] liveEmbedding, List<ProfileRecord> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new MatchResult(null, 0f);
        }

        ProfileRecord bestProfile = null;
        float bestScore = -1f;

        for (ProfileRecord candidate : candidates) {
            float score = cosineSimilarityNorm(liveEmbedding, candidate.embedding);
            if (score > bestScore) {
                bestScore   = score;
                bestProfile = candidate;
            }
        }

        return new MatchResult(bestProfile, bestScore);
    }

    /**
     * SSoT 정의: match_score = (cosine_sim + 1) / 2
     * 두 벡터가 L2 정규화된 경우: cosine_sim = dot product
     */
    static float cosineSimilarityNorm(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("차원 불일치");
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        // L2 정규화된 벡터에서 dot = cosine_sim ∈ [-1, 1]
        double cosineSim = Math.max(-1.0, Math.min(1.0, dot));
        return (float)((cosineSim + 1.0) / 2.0);
    }
}
