package com.faceauth.sdk.api;

/**
 * SDK 초기화 설정값.
 * FaceAuthSdk.initialize() 시 전달.
 * 모든 임계값은 SSoT 초기값으로 설정되며, POC 단계에서 조정 가능.
 *
 * ── face_embedder 기본값 ─────────────────────────────────────────────────
 *   tfliteModelAsset : "face_embedder.tflite"
 *   embeddingDim     : 192
 *   inputSize        : 112 (고정)
 *   inputMean        : 127.5  → 정규화 공식: (pixel - inputMean) / inputStd
 *   inputStd         : 128.0  → 결과 범위: [-1, 1]
 *   modelVersion     : "face_embedder-v1.0"
 */
public final class FaceAuthConfig {

    // ── 매칭 (단일 config, 매직넘버 금지) ─────────────────────────────────
    /** 코사인 match_score 통과 기준 (0.80). COSINE_MATCH_THRESHOLD. */
    public final float matchThreshold;

    // ── Enrollment (SSoT) ───────────────────────────────────────────────
    /** 연속 안정 프레임 수 (5~10 권장, 기본 7) */
    public final int   requiredStableFrames;
    /** bbox 면적 비율 최솟값 (레거시/기본 0.05). 등록 게이트는 enrollMinFaceAreaRatio 사용. */
    public final float minBboxAreaRatio;
    /** 등록 시 yaw 허용 최대 절대값 ENROLL_YAW_MAX_DEG (10도). 0 이하면 미적용. */
    public final float enrollMaxAbsYaw;
    /** 등록 품질 게이트: 최소 얼굴 면적 비율 ENROLL_MIN_FACE_AREA_RATIO (0.14). */
    public final float enrollMinFaceAreaRatio;

    // ── Authentication 품질 게이트 ─────────────────────────────────────
    /** 인증 시 yaw 허용 최대 절대값 AUTH_YAW_MAX_DEG (18도). */
    public final float authYawMaxDeg;
    /** 인증 품질 게이트: 최소 얼굴 면적 비율 AUTH_MIN_FACE_AREA_RATIO (0.12). */
    public final float authMinFaceAreaRatio;
    /** 인증 안정 프레임 수 AUTH_STABLE_FRAMES_REQUIRED (3~5). */
    public final int   authStableFramesRequired;

    // ── Yaw / Liveness (SSoT) ─────────────────────────────────────────────
    /** Yaw Verification CENTER: abs(usedYaw) <= this (10) */
    public final float yawCenterMaxAbsDeg;
    /** LEFT: usedYaw <= this (-13) */
    public final float yawLeftMinDeg;
    /** RIGHT: usedYaw >= this (+13) */
    public final float yawRightMinDeg;
    /** Liveness 제한 시간 (ms). 0 이하면 미사용. */
    public final long  livenessTimeoutMs;

    // ── Guide Geometry (SSoT) ───────────────────────────────────────────
    /** 원형 가이드 반지름 비율 (레거시/폴백). 동적 preset 사용 시 getGuidePreset() 사용. */
    public final float guideCircleRadiusRatio;
    /** Phone Portrait: guideRatio, centerYOffsetRatio */
    public final float guidePhonePortraitRatio;
    public final float guidePhonePortraitCenterYOffset;
    /** Phone Landscape */
    public final float guidePhoneLandscapeRatio;
    public final float guidePhoneLandscapeCenterYOffset;
    /** Tablet Portrait */
    public final float guideTabletPortraitRatio;
    public final float guideTabletPortraitCenterYOffset;
    /** Tablet Landscape */
    public final float guideTabletLandscapeRatio;
    public final float guideTabletLandscapeCenterYOffset;
    /** Inside-check inner margin: effectiveR = r * innerMarginRatio (기본 0.92) */
    public final float guideInnerMarginRatio;

    // ── 품질 게이트 (기존/공통) ───────────────────────────────────────────
    /** 얼굴 bbox 면적 비율 최솟값 (0.08) — 품질 게이트용. 등록은 minBboxAreaRatio 사용. */
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

    // ── face_embedder 모델 설정 ───────────────────────────────────────────
    /** TFLite 모델 파일명 (assets/ 기준) */
    public final String tfliteModelAsset;
    /**
     * 입력 이미지 정규화 평균값.
     * face_embedder: 127.5  → (pixel - 127.5) / 128.0 = [-1, 1]
     */
    public final float  inputMean;
    /**
     * 입력 이미지 정규화 표준편차.
     * face_embedder: 128.0
     */
    public final float  inputStd;
    /**
     * 임베딩 벡터 차원.
     * face_embedder: 192
     */
    public final int    embeddingDim;
    /**
     * face_embedder은 추론 결과가 이미 L2 정규화된 상태로 출력됨.
     * true = embed() 내부에서 추가 L2 정규화 생략.
     * false = 추가 L2 정규화 수행 (다른 모델 사용 시).
     */
    public final boolean modelOutputIsNormalized;
    /** 모델 버전 문자열 (DB 저장용) */
    public final String  modelVersion;
    /** POC 모드 (audit debug_json 허용) */
    public final boolean pocMode;

    private FaceAuthConfig(Builder b) {
        this.matchThreshold           = b.matchThreshold;
        this.requiredStableFrames      = b.requiredStableFrames;
        this.minBboxAreaRatio          = b.minBboxAreaRatio;
        this.enrollMaxAbsYaw           = b.enrollMaxAbsYaw;
        this.enrollMinFaceAreaRatio    = b.enrollMinFaceAreaRatio;
        this.authYawMaxDeg             = b.authYawMaxDeg;
        this.authMinFaceAreaRatio      = b.authMinFaceAreaRatio;
        this.authStableFramesRequired  = b.authStableFramesRequired;
        this.yawCenterMaxAbsDeg        = b.yawCenterMaxAbsDeg;
        this.yawLeftMinDeg             = b.yawLeftMinDeg;
        this.yawRightMinDeg            = b.yawRightMinDeg;
        this.livenessTimeoutMs        = b.livenessTimeoutMs;
        this.guideCircleRadiusRatio    = b.guideCircleRadiusRatio;
        this.guidePhonePortraitRatio   = b.guidePhonePortraitRatio;
        this.guidePhonePortraitCenterYOffset = b.guidePhonePortraitCenterYOffset;
        this.guidePhoneLandscapeRatio  = b.guidePhoneLandscapeRatio;
        this.guidePhoneLandscapeCenterYOffset = b.guidePhoneLandscapeCenterYOffset;
        this.guideTabletPortraitRatio  = b.guideTabletPortraitRatio;
        this.guideTabletPortraitCenterYOffset = b.guideTabletPortraitCenterYOffset;
        this.guideTabletLandscapeRatio = b.guideTabletLandscapeRatio;
        this.guideTabletLandscapeCenterYOffset = b.guideTabletLandscapeCenterYOffset;
        this.guideInnerMarginRatio     = b.guideInnerMarginRatio;
        this.qualityBboxRatioMin       = b.qualityBboxRatioMin;
        this.qualityYawMaxDeg          = b.qualityYawMaxDeg;
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

    /** Device: tablet if smallestWidthDp >= 600, else phone. */
    public static boolean isTablet(int smallestWidthDp) {
        return smallestWidthDp >= 600;
    }

    /** Orientation: landscape if viewWidth > viewHeight. */
    public static boolean isLandscape(int viewWidth, int viewHeight) {
        return viewWidth > viewHeight;
    }

    /** Preset for current device and orientation. */
    public GuidePreset getGuidePreset(boolean isTablet, boolean isLandscape) {
        if (isTablet) {
            return isLandscape
                    ? new GuidePreset(guideTabletLandscapeRatio, guideTabletLandscapeCenterYOffset)
                    : new GuidePreset(guideTabletPortraitRatio, guideTabletPortraitCenterYOffset);
        }
        return isLandscape
                ? new GuidePreset(guidePhoneLandscapeRatio, guidePhoneLandscapeCenterYOffset)
                : new GuidePreset(guidePhonePortraitRatio, guidePhonePortraitCenterYOffset);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        // ── 매칭
        float   matchThreshold          = 0.80f;
        // ── Enrollment (SSoT)
        int     requiredStableFrames   = 7;
        float   minBboxAreaRatio        = 0.05f;
        float   enrollMaxAbsYaw         = 10f;
        float   enrollMinFaceAreaRatio  = 0.14f;
        // ── Authentication 품질 게이트
        float   authYawMaxDeg           = 18f;
        float   authMinFaceAreaRatio    = 0.12f;
        int     authStableFramesRequired = 5;
        // ── Yaw / Liveness (SSoT)
        float   yawCenterMaxAbsDeg      = 10f;
        float   yawLeftMinDeg           = -13f;
        float   yawRightMinDeg          = 13f;
        long    livenessTimeoutMs      = 60_000L;
        // ── Guide
        float   guideCircleRadiusRatio  = 0.35f;
        float   guidePhonePortraitRatio  = 0.80f;
        float   guidePhonePortraitCenterYOffset = -0.06f;
        float   guidePhoneLandscapeRatio = 0.68f;
        float   guidePhoneLandscapeCenterYOffset = -0.04f;
        float   guideTabletPortraitRatio = 0.72f;
        float   guideTabletPortraitCenterYOffset = -0.04f;
        float   guideTabletLandscapeRatio = 0.62f;
        float   guideTabletLandscapeCenterYOffset = -0.02f;
        float   guideInnerMarginRatio   = 0.92f;
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
        // ── face_embedder 기본값
        String  tfliteModelAsset        = "face_embedder.tflite";
        float   inputMean               = 127.5f;   // face_embedder 정규화 평균
        float   inputStd                = 128.0f;   // face_embedder 정규화 표준편차
        int     embeddingDim            = 192;      // face_embedder 출력 차원
        boolean modelOutputIsNormalized = true;     // face_embedder은 L2 정규화 출력
        String  modelVersion            = "face_embedder-v1.0";
        boolean pocMode                 = false;

        public Builder matchThreshold(float v)          { matchThreshold = v;          return this; }
        public Builder requiredStableFrames(int v)      { requiredStableFrames = v;    return this; }
        public Builder minBboxAreaRatio(float v)        { minBboxAreaRatio = v;         return this; }
        public Builder enrollMaxAbsYaw(float v)         { enrollMaxAbsYaw = v;          return this; }
        public Builder enrollMinFaceAreaRatio(float v)  { enrollMinFaceAreaRatio = v;   return this; }
        public Builder authYawMaxDeg(float v)           { authYawMaxDeg = v;            return this; }
        public Builder authMinFaceAreaRatio(float v)    { authMinFaceAreaRatio = v;     return this; }
        public Builder authStableFramesRequired(int v)  { authStableFramesRequired = v; return this; }
        public Builder yawCenterMaxAbsDeg(float v)      { yawCenterMaxAbsDeg = v;      return this; }
        public Builder yawLeftMinDeg(float v)           { yawLeftMinDeg = v;           return this; }
        public Builder yawRightMinDeg(float v)          { yawRightMinDeg = v;          return this; }
        public Builder livenessTimeoutMs(long v)       { livenessTimeoutMs = v;       return this; }
        public Builder guideCircleRadiusRatio(float v) { guideCircleRadiusRatio = v;  return this; }
        public Builder guidePhonePortrait(float ratio, float centerYOffset) {
            guidePhonePortraitRatio = ratio; guidePhonePortraitCenterYOffset = centerYOffset; return this;
        }
        public Builder guidePhoneLandscape(float ratio, float centerYOffset) {
            guidePhoneLandscapeRatio = ratio; guidePhoneLandscapeCenterYOffset = centerYOffset; return this;
        }
        public Builder guideTabletPortrait(float ratio, float centerYOffset) {
            guideTabletPortraitRatio = ratio; guideTabletPortraitCenterYOffset = centerYOffset; return this;
        }
        public Builder guideTabletLandscape(float ratio, float centerYOffset) {
            guideTabletLandscapeRatio = ratio; guideTabletLandscapeCenterYOffset = centerYOffset; return this;
        }
        public Builder guideInnerMarginRatio(float v) { guideInnerMarginRatio = v; return this; }
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
