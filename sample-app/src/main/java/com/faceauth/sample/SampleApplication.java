package com.faceauth.sample;

import android.app.Application;

import com.faceauth.sample.BuildConfig;
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;

public class SampleApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // ── SDK 초기화 (Application.onCreate에서 1회) ──────────────────
        // MobileFaceNet 기본값은 builder()에 이미 설정되어 있음.
        // 아래는 명시적으로 기재한 예시 — 기본값과 동일하므로 생략 가능.
        FaceAuthConfig config = FaceAuthConfig.builder()
                // ── 매칭 (SSoT 고정 — 변경 금지) ──────────────────────
                .matchThreshold(0.80f)
                // ── MobileFaceNet 모델 설정 ────────────────────────────
                .tfliteModelAsset("mobilefacenet.tflite") // assets/ 에 배치 필요
                .inputNormalization(127.5f, 128.0f)       // (pixel - 127.5) / 128.0
                .embeddingDim(192)                        // MobileFaceNet 출력 차원
                .modelOutputIsNormalized(true)            // 모델 출력이 이미 L2 정규화됨
                .modelVersion("mobilefacenet-v1.0")
                // ── 품질 게이트 ────────────────────────────────────────
                .qualityBboxRatioMin(0.08f)
                .qualityYawMaxDeg(15f)
                .qualityPitchMaxDeg(15f)
                .qualityBlurMin(80f)
                .qualityBrightness(40, 220)
                // ── Liveness ───────────────────────────────────────────
                .livenessWindowMs(3_000L)
                .livenessBlinkCount(2)
                .earThresholds(0.21f, 0.27f)
                // ── 운영 모드 ──────────────────────────────────────────
                .pocMode(BuildConfig.DEBUG)               // DEBUG 빌드에서 POC 모드 ON
                .build();

        FaceAuthSdk.initialize(this, config);
    }
}
