package com.faceauth.sdk.matcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import com.faceauth.sdk.storage.ProfileRecord;

import static org.junit.Assert.*;

/**
 * SSOT v1: 2차 검증(센트로이드) 단위 테스트.
 */
public class SecondaryVerifierTest {

    private static final int DIM = 3;

    @Test
    public void centroid_singleTemplate_acceptWhenScoreAboveT2() {
        float[] t1 = l2Norm(new float[]{1f, 0f, 0f});
        ProfileRecord p = makeProfile("u1", t1);
        TemplateCache cache = new TemplateCache(DIM);
        cache.setProfiles(Arrays.asList(p));

        float[] query = l2Norm(new float[]{1f, 0.01f, 0f});
        SecondaryVerifier.Result r = SecondaryVerifier.verify(
                query, "u1", "NORMAL", cache, 0.5f, 0.02f, 0.01f, 0.05f);

        assertEquals(SecondaryVerifier.Decision.ACCEPT, r.decision);
        assertTrue(r.centroidScore >= 0.99f);
    }

    @Test
    public void centroid_twoTemplates_meanUsed() {
        float[] a = l2Norm(new float[]{1f, 0f, 0f});
        float[] b = l2Norm(new float[]{1f, 0.1f, 0f});
        List<ProfileRecord> profiles = Arrays.asList(makeProfile("u1", a), makeProfile("u1", b));
        TemplateCache cache = new TemplateCache(DIM);
        cache.setProfiles(profiles);

        float[] centroid = cache.getCentroid("NORMAL", "u1");
        assertNotNull(centroid);
        assertEquals(DIM, centroid.length);
        float[] query = l2Norm(new float[]{1f, 0.05f, 0f});
        float score = EmbeddingMatcher.cosineSimilarityNorm(query, centroid);
        assertTrue(score > 0.9f);
    }

    @Test
    public void verify_lowMargin_returnsUncertain() {
        float[] t1 = l2Norm(new float[]{1f, 0f, 0f});
        TemplateCache cache = new TemplateCache(DIM);
        cache.setProfiles(Arrays.asList(makeProfile("u1", t1)));
        float[] query = l2Norm(new float[]{1f, 0.01f, 0f});
        float margin = 0.01f;
        float M_AMBIGUOUS = 0.03f;

        SecondaryVerifier.Result r = SecondaryVerifier.verify(
                query, "u1", "NORMAL", cache, 0.8f, 0.04f, M_AMBIGUOUS, margin);

        assertEquals(SecondaryVerifier.Decision.UNCERTAIN, r.decision);
    }

    @Test
    public void verify_unknownUser_returnsSecondaryFail() {
        TemplateCache cache = new TemplateCache(DIM);
        cache.setProfiles(Arrays.asList(makeProfile("u1", l2Norm(new float[]{1f, 0f, 0f}))));
        float[] query = l2Norm(new float[]{1f, 0f, 0f});

        SecondaryVerifier.Result r = SecondaryVerifier.verify(
                query, "u_unknown", "NORMAL", cache, 0.5f, 0.02f, 0.01f, 0.1f);

        assertEquals(SecondaryVerifier.Decision.SECONDARY_FAIL, r.decision);
        assertTrue(Float.isNaN(r.centroidScore));
    }

    private static ProfileRecord makeProfile(String userId, float[] emb) {
        return new ProfileRecord(0, userId, "NORMAL", emb, emb.length, 1.0f, System.currentTimeMillis(), "v1");
    }

    private static float[] l2Norm(float[] v) {
        double n = 0;
        for (float x : v) n += (double) x * x;
        n = Math.sqrt(n);
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (float) (v[i] / n);
        return r;
    }
}
