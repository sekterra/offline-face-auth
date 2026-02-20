package com.faceauth.sdk.api;

/**
 * SDK 초기화 설정값.
 * FaceAuthSdk.initialize() 시 전달.
 * 모든 임계값은 SSoT 초기값으로 설정되며, POC 단계에서 조정 가능.
 *
 * ── MobileFaceNet 기본값 ─────────────────────────────────────────────────
 *   tfliteModelAsset : "mobilefacenet.tflite"
 *   embeddingDim     : 192
 *   inputSize        : 112 (고정)
 *   inputMean        : 127.5  → 정규화 공식: (pixel - inputMean) / inputStd
 *   inputStd         : 128.0  → 결과 범위: [-1, 1]
 *   modelVersion     : "mobilefacenet-v1.0"
 */
public final class FaceAuthConfig {

    // ── 매칭 ─────────────────────────────────────────────────────────────
    /** match_score 통과 기준 (SSoT 고정: 0.80) */
    public final float matchThreshold;

    // ── 품질 게이트 ───────────────────────────────────────────────────────
    /** 얼굴 bbox 면적 비율 최솟값 (0.08) */
    public final float qualityBboxRatioMin;
    /** Yaw 허용 최대 각도 (15°) */
    public final float qualityYawMaxDeg;
    /** Pitch 허용 최대 각도 (15°) */
    public final float qualityPitchMaxDeg;
    /** Laplacian blur score 최솟값 */
    public final float qualityBlurMin;
    /** 밝기 최솟값 (0~255) */
    public final int   qualityBrightnessMin;
    /** 밝기 최댓값 (0~255) */
    public final int   qualityBrightnessMax;

    // ── Liveness ─────────────────────────────────────────────────────────
    /** 챌린지 시간 제한 (ms) */
    public final long  livenessWindowMs;
    /** 필요 깜빡임 횟수 */
    public final int   livenessBlinkCount;
    /** EAR closed 임계값 */
    public final float earCloseThreshold;
    /** EAR open 임계값 */
    public final float earOpenThreshold;

    // ── MobileFaceNet 모델 설정 ───────────────────────────────────────────
    /** TFLite 모델 파일명 (assets/ 기준) */
    public final String tfliteModelAsset;
    /**
     * 입력 이미지 정규화 평균값.
     * MobileFaceNet: 127.5  → (pixel - 127.5) / 128.0 = [-1, 1]
     */
    public final float  inputMean;
    /**
     * 입력 이미지 정규화 표준편차.
     * MobileFaceNet: 128.0
     */
    public final float  inputStd;
    /**
     * 임베딩 벡터 차원.
     * MobileFaceNet: 192
     */
    public final int    embeddingDim;
    /**
     * MobileFaceNet은 추론 결과가 이미 L2 정규화된 상태로 출력됨.
     * true = embed() 내부에서 추가 L2 정규화 생략.
     * false = 추가 L2 정규화 수행 (다른 모델 사용 시).
     */
    public final boolean modelOutputIsNormalized;
    /** 모델 버전 문자열 (DB 저장용) */
    public final String  modelVersion;
    /** POC 모드 (audit debug_json 허용) */
    public final boolean pocMode;

    private FaceAuthConfig(Builder b) {
        this.matchThreshold          = b.matchThreshold;
        this.qualityBboxRatioMin     = b.qualityBboxRatioMin;
        this.qualityYawMaxDeg        = b.qualityYawMaxDeg;
        this.qualityPitchMaxDeg      = b.qualityPitchMaxDeg;
        this.qualityBlurMin          = b.qualityBlurMin;
        this.qualityBrightnessMin    = b.qualityBrightnessMin;
        this.qualityBrightnessMax    = b.qualityBrightnessMax;
        this.livenessWindowMs        = b.livenessWindowMs;
        this.livenessBlinkCount      = b.livenessBlinkCount;
        this.earCloseThreshold       = b.earCloseThreshold;
        this.earOpenThreshold        = b.earOpenThreshold;
        this.tfliteModelAsset        = b.tfliteModelAsset;
        this.inputMean               = b.inputMean;
        this.inputStd                = b.inputStd;
        this.embeddingDim            = b.embeddingDim;
        this.modelOutputIsNormalized = b.modelOutputIsNormalized;
        this.modelVersion            = b.modelVersion;
        this.pocMode                 = b.pocMode;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        // ── 매칭
        float   matchThreshold          = 0.80f;
        // ── 품질 게이트
        float   qualityBboxRatioMin     = 0.08f;
        float   qualityYawMaxDeg        = 15f;
        float   qualityPitchMaxDeg      = 15f;
        float   qualityBlurMin          = 80f;
        int     qualityBrightnessMin    = 40;
        int     qualityBrightnessMax    = 220;
        // ── Liveness
        long    livenessWindowMs        = 3_000L;
        int     livenessBlinkCount      = 2;
        float   earCloseThreshold       = 0.21f;
        float   earOpenThreshold        = 0.27f;
        // ── MobileFaceNet 기본값
        String  tfliteModelAsset        = "mobilefacenet.tflite";
        float   inputMean               = 127.5f;   // MobileFaceNet 정규화 평균
        float   inputStd                = 128.0f;   // MobileFaceNet 정규화 표준편차
        int     embeddingDim            = 192;      // MobileFaceNet 출력 차원
        boolean modelOutputIsNormalized = true;     // MobileFaceNet은 L2 정규화 출력
        String  modelVersion            = "mobilefacenet-v1.0";
        boolean pocMode                 = false;

        public Builder matchThreshold(float v)          { matchThreshold = v;          return this; }
        public Builder qualityBboxRatioMin(float v)     { qualityBboxRatioMin = v;     return this; }
        public Builder qualityYawMaxDeg(float v)        { qualityYawMaxDeg = v;        return this; }
        public Builder qualityPitchMaxDeg(float v)      { qualityPitchMaxDeg = v;      return this; }
        public Builder qualityBlurMin(float v)          { qualityBlurMin = v;          return this; }
        public Builder qualityBrightness(int min, int max) {
            qualityBrightnessMin = min; qualityBrightnessMax = max; return this;
        }
        public Builder livenessWindowMs(long v)         { livenessWindowMs = v;        return this; }
        public Builder livenessBlinkCount(int v)        { livenessBlinkCount = v;      return this; }
        public Builder earThresholds(float close, float open) {
            earCloseThreshold = close; earOpenThreshold = open; return this;
        }
        public Builder tfliteModelAsset(String v)       { tfliteModelAsset = v;        return this; }
        public Builder inputNormalization(float mean, float std) {
            inputMean = mean; inputStd = std;          return this;
        }
        public Builder embeddingDim(int v)              { embeddingDim = v;            return this; }
        public Builder modelOutputIsNormalized(boolean v){ modelOutputIsNormalized = v; return this; }
        public Builder modelVersion(String v)           { modelVersion = v;            return this; }
        public Builder pocMode(boolean v)               { pocMode = v;                 return this; }

        public FaceAuthConfig build() { return new FaceAuthConfig(this); }
    }
}
