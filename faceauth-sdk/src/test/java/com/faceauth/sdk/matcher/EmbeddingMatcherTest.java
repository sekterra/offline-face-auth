package com.faceauth.sdk.matcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.faceauth.sdk.storage.ProfileRecord;

import static org.junit.Assert.*;

/**
 * EmbeddingMatcher 단위 테스트.
 * SSoT §3: match_score = (cosine_sim + 1) / 2
 */
public class EmbeddingMatcherTest {

    // ── 동일 벡터: cosine_sim=1 → match_score=1.0 ─────────────────────
    @Test
    public void identicalVectors_scoreIs1() {
        float[] v = l2Normalize(new float[]{1f, 0f, 0f});
        float score = EmbeddingMatcher.cosineSimilarityNorm(v, v);
        assertEquals(1.0f, score, 1e-5f);
    }

    // ── 정반대 벡터: cosine_sim=-1 → match_score=0.0 ──────────────────
    @Test
    public void oppositeVectors_scoreIs0() {
        float[] a = l2Normalize(new float[]{1f, 0f, 0f});
        float[] b = l2Normalize(new float[]{-1f, 0f, 0f});
        float score = EmbeddingMatcher.cosineSimilarityNorm(a, b);
        assertEquals(0.0f, score, 1e-5f);
    }

    // ── 직교 벡터: cosine_sim=0 → match_score=0.5 ─────────────────────
    @Test
    public void orthogonalVectors_scoreIs0point5() {
        float[] a = l2Normalize(new float[]{1f, 0f, 0f});
        float[] b = l2Normalize(new float[]{0f, 1f, 0f});
        float score = EmbeddingMatcher.cosineSimilarityNorm(a, b);
        assertEquals(0.5f, score, 1e-5f);
    }

    // ── 통과 기준(0.80) 검증 ──────────────────────────────────────────
    @Test
    public void matchScore_aboveThreshold_passes() {
        float threshold = 0.80f;
        float[] live  = l2Normalize(new float[]{1f, 0.1f, 0f});
        float[] saved = l2Normalize(new float[]{1f, 0.15f, 0f});
        float score = EmbeddingMatcher.cosineSimilarityNorm(live, saved);
        assertTrue("동일 사용자 점수가 임계값 이상이어야 함", score >= threshold);
    }

    // ── 후보 없을 때 score=0 ──────────────────────────────────────────
    @Test
    public void emptyCandidates_returnsZeroScore() {
        float[] live = l2Normalize(new float[]{1f, 0f, 0f});
        EmbeddingMatcher.MatchResult result =
                EmbeddingMatcher.findTopMatch(live, Collections.emptyList());
        assertNull(result.bestProfile);
        assertEquals(0f, result.matchScore, 1e-5f);
    }

    // ── Top-1 선택 정확성 ─────────────────────────────────────────────
    @Test
    public void findTopMatch_returnsHighestScore() {
        float[] live = l2Normalize(new float[]{1f, 0f, 0f});

        ProfileRecord farProfile  = makeProfile("user_far",  new float[]{0f, 1f, 0f});
        ProfileRecord nearProfile = makeProfile("user_near", new float[]{1f, 0.01f, 0f});

        List<ProfileRecord> candidates = Arrays.asList(farProfile, nearProfile);
        EmbeddingMatcher.MatchResult result = EmbeddingMatcher.findTopMatch(live, candidates);

        assertEquals("user_near", result.bestProfile.userId);
        assertTrue(result.matchScore > 0.8f);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────

    private static ProfileRecord makeProfile(String userId, float[] emb) {
        float[] normalized = l2Normalize(emb);
        return new ProfileRecord(0, userId, "NORMAL",
                normalized, normalized.length, 1.0f,
                System.currentTimeMillis(), "v1.0");
    }

    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (float)(v[i] / norm);
        return r;
    }
}
