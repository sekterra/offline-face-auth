package com.faceauth.sdk.api;

/**
 * Dynamic circle guide preset (phone/tablet × portrait/landscape).
 * SSoT: values centralized in config.
 */
public final class GuidePreset {
    public final float guideRatio;
    public final float centerYOffsetRatio;

    public GuidePreset(float guideRatio, float centerYOffsetRatio) {
        this.guideRatio = guideRatio;
        this.centerYOffsetRatio = centerYOffsetRatio;
    }
}
