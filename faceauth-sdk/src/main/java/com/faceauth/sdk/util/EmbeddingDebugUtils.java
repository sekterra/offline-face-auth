package com.faceauth.sdk.util;

/**
 * 디버그 패널용 임베딩 유틸 (norm, first5, embeddingHash).
 * Evidence debug panel에서 등록/인증 임베딩 비교 증거 표시에 사용.
 */
public final class EmbeddingDebugUtils {

    private EmbeddingDebugUtils() {}

    /** L2 norm of embedding vector. */
    public static double norm(float[] vec) {
        if (vec == null) return 0;
        double sum = 0;
        for (float v : vec) sum += (double) v * v;
        return Math.sqrt(sum);
    }

    /** First 5 floats as string, e.g. "[0.123, -0.456, ...]" (3 decimals). */
    public static String first5(float[] vec, int decimals) {
        if (vec == null || vec.length == 0) return "[]";
        int n = Math.min(5, vec.length);
        StringBuilder sb = new StringBuilder("[");
        String fmt = "%." + decimals + "f";
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(fmt, vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /** Checksum string for embedding (deterministic, same DAO as matching). Sum of first 16 floats, 4 decimals. */
    public static String embeddingHash(float[] vec) {
        if (vec == null) return "null";
        double sum = 0;
        for (int i = 0; i < Math.min(16, vec.length); i++) {
            sum += vec[i];
        }
        return String.format("%.4f", sum);
    }
}
