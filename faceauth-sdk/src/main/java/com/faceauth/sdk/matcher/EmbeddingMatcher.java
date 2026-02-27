package com.faceauth.sdk.matcher;

import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.storage.ProfileRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * SSOT v1: 사용자 단위 Top-1/Top-2 + margin.
     * 동일 user_id의 여러 템플릿 중 최고 점수로 사용자 순위.
     */
    public static final class TopTwoResult {
        public final String         top1UserId;
        public final float          top1Score;
        public final String         top2UserId;   // null if 단일 사용자
        public final float          top2Score;
        public final float          margin;      // top1Score - top2Score (top2 없으면 1 - top2Score 등 큰 값)
        public final ProfileRecord bestProfile;  // top1을 만든 템플릿(임의 1개)

        TopTwoResult(String top1UserId, float top1Score, String top2UserId, float top2Score,
                     float margin, ProfileRecord bestProfile) {
            this.top1UserId   = top1UserId;
            this.top1Score    = top1Score;
            this.top2UserId   = top2UserId;
            this.top2Score    = top2Score;
            this.margin      = margin;
            this.bestProfile = bestProfile;
        }
    }

    /**
     * 사용자별 최고 점수 계산 후 Top-1/Top-2 사용자와 margin 반환.
     * 모든 템플릿을 고려 (multi-template).
     */
    public static TopTwoResult findTopTwoUsersWithMargin(float[] liveEmbedding,
                                                          List<ProfileRecord> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new TopTwoResult(null, 0f, null, 0f, 0f, null);
        }
        Map<String, Float> byUser = new LinkedHashMap<>();
        for (ProfileRecord c : candidates) {
            if (c.embedding == null || c.embedding.length != liveEmbedding.length) continue;
            float score = cosineSimilarityNorm(liveEmbedding, c.embedding);
            Float cur = byUser.get(c.userId);
            if (cur == null || score > cur) byUser.put(c.userId, score);
        }
        List<Map.Entry<String, Float>> sorted = new ArrayList<>(byUser.entrySet());
        sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        String top1Id = sorted.isEmpty() ? null : sorted.get(0).getKey();
        float top1Score = sorted.isEmpty() ? 0f : sorted.get(0).getValue();
        String top2Id = sorted.size() < 2 ? null : sorted.get(1).getKey();
        float top2Score = sorted.size() < 2 ? 0f : sorted.get(1).getValue();
        float margin = top1Score - top2Score;
        ProfileRecord top1Profile = null;
        if (top1Id != null) {
            for (ProfileRecord c : candidates) {
                if (top1Id.equals(c.userId) && c.embedding != null && c.embedding.length == liveEmbedding.length) {
                    float s = cosineSimilarityNorm(liveEmbedding, c.embedding);
                    if (Math.abs(s - top1Score) < 1e-5f) { top1Profile = c; break; }
                }
            }
        }
        return new TopTwoResult(top1Id, top1Score, top2Id, top2Score, margin, top1Profile);
    }

    /**
     * findTopMatch + 구조화 로그 (auth_match_probe, auth_match_best, auth_match_embedding_stats).
     * 매칭 진단용. threshold는 로그의 decision 계산에만 사용.
     */
    public static MatchResult findTopMatchWithLogging(float[] liveEmbedding,
                                                       List<ProfileRecord> candidates,
                                                       float threshold,
                                                       String tag) {
        if (tag == null) tag = "EmbeddingMatcher";
        MatchResult result = findTopMatch(liveEmbedding, candidates);

        if (candidates == null || candidates.isEmpty()) {
            return result;
        }

        int dim = liveEmbedding.length;
        double queryNorm = l2Norm(liveEmbedding);
        double enrolledNormMin = Double.POSITIVE_INFINITY;
        double enrolledNormMax = Double.NEGATIVE_INFINITY;

        for (ProfileRecord c : candidates) {
            double n = l2Norm(c.embedding);
            if (n < enrolledNormMin) enrolledNormMin = n;
            if (n > enrolledNormMax) enrolledNormMax = n;
        }

        SafeLogger.i(tag, String.format(
                "{\"event\":\"auth_match_embedding_stats\",\"queryNorm\":%.4f,\"enrolledNormMin\":%.4f,\"enrolledNormMax\":%.4f,\"dim\":%d}",
                queryNorm, enrolledNormMin, enrolledNormMax, dim));

        float bestSoFar = -1f;
        for (ProfileRecord candidate : candidates) {
            float score = cosineSimilarityNorm(liveEmbedding, candidate.embedding);
            boolean rankCandidate = score > bestSoFar;
            if (rankCandidate) bestSoFar = score;
            SafeLogger.i(tag, String.format(
                    "{\"event\":\"auth_match_probe\",\"enrolledId\":\"%s\",\"distanceOrScore\":%.4f,\"rankCandidate\":%s}",
                    candidate.userId, score, rankCandidate));
        }

        String bestId = result.bestProfile != null ? result.bestProfile.userId : "";
        String decision = (result.bestProfile != null && result.matchScore >= threshold) ? "MATCH" : "NO_MATCH";
        SafeLogger.i(tag, String.format(
                "{\"event\":\"auth_match_best\",\"bestId\":\"%s\",\"bestScore\":%.4f,\"threshold\":%.4f,\"decision\":\"%s\"}",
                bestId, result.matchScore, threshold, decision));

        if (dim >= 5) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"event\":\"auth_match_embedding_sample\",\"queryFirst5\":[");
            for (int i = 0; i < 5; i++) { if (i > 0) sb.append(","); sb.append(String.format("%.4f", liveEmbedding[i])); }
            sb.append("],\"enrolledFirst5\":{");
            for (int u = 0; u < Math.min(2, candidates.size()); u++) {
                ProfileRecord c = candidates.get(u);
                if (u > 0) sb.append(",");
                sb.append("\"").append(c.userId).append("\":[");
                for (int i = 0; i < Math.min(5, c.embedding.length); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(String.format("%.4f", c.embedding[i]));
                }
                sb.append("]");
            }
            sb.append("}}");
            SafeLogger.i(tag, sb.toString());
        }

        return result;
    }

    private static double l2Norm(float[] a) {
        double sum = 0;
        for (float v : a) sum += (double) v * v;
        return Math.sqrt(sum);
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
            if (candidate.embedding == null || candidate.embedding.length != liveEmbedding.length) {
                continue;
            }
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
     * cosine_sim = dot(a,b) / (||a|| * ||b||) — 정규화 여부와 무관하게 항상 이 식 사용.
     * (모델이 L2 정규화를 하지 않으면 dot만 쓰면 1.0으로 클램프되어 항상 t1만 매칭되는 버그 방지)
     */
    static float cosineSimilarityNorm(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("차원 불일치");
        double dot = 0;
        double normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);
        if (normA < 1e-10 || normB < 1e-10) return 0f;
        double cosineSim = dot / (normA * normB);
        cosineSim = Math.max(-1.0, Math.min(1.0, cosineSim));
        return (float)((cosineSim + 1.0) / 2.0);
    }
}
