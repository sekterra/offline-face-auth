package com.faceauth.sdk.matcher;

import com.faceauth.sdk.storage.ProfileRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSOT v1: (profile_type, user_id) 별 템플릿·센트로이드 인메모리 캐시.
 * 인증 시 후보 로드 후 한 번 설정하고, 프레임마다 재할당 없이 사용.
 */
public final class TemplateCache {

    private final Map<String, List<ProfileRecord>> templatesByKey = new HashMap<>();
    private final Map<String, float[]> centroidByKey = new HashMap<>();
    private final int embeddingDim;

    public TemplateCache(int embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    /** 캐시 키: profileType + ":" + userId */
    private static String key(String profileType, String userId) {
        return (profileType != null ? profileType : "") + ":" + (userId != null ? userId : "");
    }

    /**
     * 전체 프로파일 목록으로 캐시 갱신. 기존 캐시 클리어 후 (profileType, userId)별로 그룹.
     */
    public void setProfiles(List<ProfileRecord> profiles) {
        templatesByKey.clear();
        centroidByKey.clear();
        if (profiles == null) return;
        for (ProfileRecord p : profiles) {
            if (p == null || p.embedding == null || p.embedding.length != embeddingDim) continue;
            String k = key(p.profileType, p.userId);
            templatesByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        }
    }

    /** 해당 (profileType, userId)의 활성 템플릿 목록 (복사 없이 반환). */
    public List<ProfileRecord> getTemplates(String profileType, String userId) {
        List<ProfileRecord> list = templatesByKey.get(key(profileType, userId));
        return list != null ? list : new ArrayList<>();
    }

    /**
     * 해당 사용자의 센트로이드(요소별 평균) 반환. 없으면 null.
     * 첫 호출 시 계산 후 캐시.
     */
    public float[] getCentroid(String profileType, String userId) {
        String k = key(profileType, userId);
        float[] cached = centroidByKey.get(k);
        if (cached != null) return cached;
        List<ProfileRecord> list = templatesByKey.get(k);
        if (list == null || list.isEmpty()) return null;
        float[] sum = new float[embeddingDim];
        for (ProfileRecord p : list) {
            if (p.embedding == null || p.embedding.length != embeddingDim) continue;
            for (int i = 0; i < embeddingDim; i++) sum[i] += p.embedding[i];
        }
        int n = list.size();
        for (int i = 0; i < embeddingDim; i++) sum[i] /= n;
        centroidByKey.put(k, sum);
        return sum;
    }
}
