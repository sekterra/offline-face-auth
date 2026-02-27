package com.faceauth.sdk.quality;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.faceauth.sdk.api.FaceAuthConfig;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

/**
 * 품질 게이트 — SSoT §4 기준.
 * 통과 실패 시 한국어 안내 메시지를 포함한 Result 반환.
 */
public final class QualityGate {

    private final FaceAuthConfig config;

    public QualityGate(FaceAuthConfig config) {
        this.config = config;
    }

    public static final class Result {
        public final boolean passed;
        public final String  guideMessage;  // 한국어 안내

        Result(boolean passed, String guideMessage) {
            this.passed       = passed;
            this.guideMessage = guideMessage;
        }

        public static Result ok() { return new Result(true, ""); }
    }

    /**
     * 품질 검사 수행.
     *
     * @param frame      현재 프레임 Bitmap (검사 후 참조 해제 책임은 호출자)
     * @param face       ML Kit 검출 결과
     * @param frameW     프레임 너비 (px)
     * @param frameH     프레임 높이 (px)
     */
    public Result check(Bitmap frame, Face face, int frameW, int frameH) {

        // 1. 얼굴 검출 여부
        if (face == null) {
            return new Result(false, "얼굴이 감지되지 않습니다. 카메라 정면을 바라봐 주세요.");
        }

        // 2. bbox 면적 비율
        RectF bbox = new RectF(face.getBoundingBox());
        float bboxArea   = bbox.width() * bbox.height();
        float frameArea  = (float) frameW * frameH;
        float bboxRatio  = bboxArea / frameArea;
        if (bboxRatio < config.qualityBboxRatioMin) {
            return new Result(false, "카메라에 더 가까이 이동해 주세요.");
        }

        // 3. Yaw / Pitch (ML Kit HeadEulerAngle 사용)
        float yaw   = Math.abs(face.getHeadEulerAngleY());  // Y축 = yaw
        float pitch = Math.abs(face.getHeadEulerAngleX());  // X축 = pitch
        if (yaw > config.qualityYawMaxDeg) {
            return new Result(false, "정면을 바라봐 주세요. (좌우 조정)");
        }
        if (pitch > config.qualityPitchMaxDeg) {
            return new Result(false, "정면을 바라봐 주세요. (상하 조정)");
        }

        // 4. Blur (Laplacian variance)
        float blurScore = computeBlurScore(frame);
        if (blurScore < config.qualityBlurMin) {
            return new Result(false, "흔들림 없이 카메라를 고정해 주세요.");
        }

        // 5. 밝기
        float brightness = computeBrightness(frame);
        if (brightness < config.qualityBrightnessMin) {
            return new Result(false, "더 밝은 곳으로 이동해 주세요.");
        }
        if (brightness > config.qualityBrightnessMax) {
            return new Result(false, "직사광선을 피해 주세요.");
        }

        return Result.ok();
    }

    // ── 내부 계산 메서드 ──────────────────────────────────────────────────

    /**
     * Laplacian variance 기반 blur 점수.
     * 값이 낮을수록 blur가 심함.
     * getPixels 호환: stride = 비트맵 실제 너비, 버퍼 크기 = stride * readH.
     */
    private static float computeBlurScore(Bitmap bmp) {
        int bmpW = bmp.getWidth();
        int bmpH = bmp.getHeight();
        if (bmpW <= 0 || bmpH <= 0) return 0f;
        int readW = Math.min(bmpW, 320);
        int readH = Math.min(bmpH, 320);
        if (readW < 3 || readH < 3) return 0f;
        int stride = bmpW;
        int[] pixels = new int[stride * readH];
        try {
            bmp.getPixels(pixels, 0, stride, 0, 0, readW, readH);
        } catch (Exception e) {
            return 0f;
        }

        double sum = 0, sumSq = 0;
        int count = 0;

        for (int y = 1; y < readH - 1; y++) {
            for (int x = 1; x < readW - 1; x++) {
                int idx = y * stride + x;
                double center = gray(pixels[idx]);
                double lap = 4 * center
                        - gray(pixels[idx - 1])
                        - gray(pixels[idx + 1])
                        - gray(pixels[idx - stride])
                        - gray(pixels[idx + stride]);
                sum   += lap;
                sumSq += lap * lap;
                count++;
            }
        }

        if (count == 0) return 0f;
        double mean = sum / count;
        double var  = sumSq / count - mean * mean;
        return (float) var;
    }

    private static double gray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    /** 평균 밝기 (0~255). getPixels 호환: stride = 비트맵 너비, 버퍼 = stride * readH. */
    private static float computeBrightness(Bitmap bmp) {
        int bmpW = bmp.getWidth();
        int bmpH = bmp.getHeight();
        if (bmpW <= 0 || bmpH <= 0) return 0f;
        int readW = Math.min(bmpW, 160);
        int readH = Math.min(bmpH, 160);
        int stride = bmpW;
        int[] pixels = new int[stride * readH];
        try {
            bmp.getPixels(pixels, 0, stride, 0, 0, readW, readH);
        } catch (Exception e) {
            return 0f;
        }
        double sum = 0;
        int count = 0;
        for (int y = 0; y < readH; y++) {
            for (int x = 0; x < readW; x++) {
                sum += gray(pixels[y * stride + x]);
                count++;
            }
        }
        return count > 0 ? (float)(sum / count) : 0f;
    }
}
