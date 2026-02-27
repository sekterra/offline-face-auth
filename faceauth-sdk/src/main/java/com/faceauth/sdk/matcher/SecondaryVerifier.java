package com.faceauth.sdk.matcher;

/**
 * SSOT v1: 그레이존 2차 검증 (top1 사용자 센트로이드 기반).
 * 1차에서 ACCEPT/REJECT이 나지 않은 경우에만 호출.
 */
public final class SecondaryVerifier {

    private SecondaryVerifier() {}

    public enum Decision {
        ACCEPT,       // centroidScore >= T2 && margin >= M2
        UNCERTAIN,    // margin < M_AMBIGUOUS → "다시 시도"
        SECONDARY_FAIL
    }

    public static final class Result {
        public final Decision decision;
        public final float centroidScore;  // Float.NaN if not computed

        Result(Decision decision, float centroidScore) {
            this.decision = decision;
            this.centroidScore = centroidScore;
        }
    }

    /**
     * top1 사용자의 템플릿 센트로이드와 쿼리 임베딩의 코사인 점수로 2차 판단.
     *
     * @param queryEmbedding 라이브 임베딩
     * @param top1UserId     그레이존에서 1위 사용자
     * @param profileType   NORMAL / HELMET
     * @param cache         템플릿·센트로이드 캐시
     * @param T2            centroidScore 통과 기준
     * @param M2            margin 통과 기준
     * @param M_AMBIGUOUS   margin 이하면 UNCERTAIN
     * @param margin        top1 - top2
     */
    public static Result verify(float[] queryEmbedding,
                                String top1UserId,
                                String profileType,
                                TemplateCache cache,
                                float T2,
                                float M2,
                                float M_AMBIGUOUS,
                                float margin) {
        if (queryEmbedding == null || cache == null || top1UserId == null) {
            return new Result(Decision.SECONDARY_FAIL, Float.NaN);
        }
        float[] centroid = cache.getCentroid(profileType, top1UserId);
        if (centroid == null || centroid.length != queryEmbedding.length) {
            return new Result(Decision.SECONDARY_FAIL, Float.NaN);
        }
        float centroidScore = EmbeddingMatcher.cosineSimilarityNorm(queryEmbedding, centroid);

        if (margin < M_AMBIGUOUS) {
            return new Result(Decision.UNCERTAIN, centroidScore);
        }
        if (centroidScore >= T2 && margin >= M2) {
            return new Result(Decision.ACCEPT, centroidScore);
        }
        return new Result(Decision.SECONDARY_FAIL, centroidScore);
    }
}
