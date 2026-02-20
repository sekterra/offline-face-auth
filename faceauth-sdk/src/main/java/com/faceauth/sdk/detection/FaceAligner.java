package com.faceauth.sdk.detection;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

/**
 * 얼굴 정렬 + 크롭.
 * 랜드마크가 있으면 눈 기준으로 affine 회전 정렬.
 * 최종 출력: 112×112 (TFLite 입력 크기)
 */
public final class FaceAligner {

    public static final int OUTPUT_SIZE = 112;

    private FaceAligner() {}

    /**
     * Face bounding box 기준으로 크롭 후 리사이즈.
     * 고급 affine 정렬은 TODO(POC Phase 2)로 남겨둠.
     *
     * @param src  원본 프레임 Bitmap
     * @param face 검출된 얼굴
     * @return 112×112 정렬 Bitmap (호출자가 .recycle() 책임)
     */
    public static Bitmap align(Bitmap src, Face face) {
        Rect bbox = face.getBoundingBox();

        // 프레임 경계 클리핑
        int left   = Math.max(0, bbox.left);
        int top    = Math.max(0, bbox.top);
        int right  = Math.min(src.getWidth(),  bbox.right);
        int bottom = Math.min(src.getHeight(), bbox.bottom);

        if (right <= left || bottom <= top) {
            // bbox가 프레임 밖이면 전체 리사이즈로 폴백
            return Bitmap.createScaledBitmap(src, OUTPUT_SIZE, OUTPUT_SIZE, true);
        }

        Bitmap cropped = Bitmap.createBitmap(src, left, top, right - left, bottom - top);

        // (옵션) 눈 기준 회전 정렬 — 랜드마크 사용 가능 시
        Bitmap aligned = tryEyeAlign(src, face, cropped);

        Bitmap resized = Bitmap.createScaledBitmap(aligned, OUTPUT_SIZE, OUTPUT_SIZE, true);

        // 중간 비트맵 해제
        if (aligned != cropped) aligned.recycle();
        cropped.recycle();

        return resized;
    }

    /** 눈 랜드마크가 있으면 회전 정렬 적용 */
    private static Bitmap tryEyeAlign(Bitmap src, Face face, Bitmap fallback) {
        FaceLandmark leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);

        if (leftEye == null || rightEye == null) return fallback;

        float lx = leftEye.getPosition().x,  ly = leftEye.getPosition().y;
        float rx = rightEye.getPosition().x, ry = rightEye.getPosition().y;

        float angle = (float) Math.toDegrees(Math.atan2(ry - ly, rx - lx));
        float cx = (lx + rx) / 2f;
        float cy = (ly + ry) / 2f;

        Matrix m = new Matrix();
        m.postRotate(-angle, cx, cy);

        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);

        // 회전 후 bbox 재크롭
        Rect bbox = face.getBoundingBox();
        int left   = Math.max(0, bbox.left);
        int top    = Math.max(0, bbox.top);
        int right  = Math.min(rotated.getWidth(),  bbox.right);
        int bottom = Math.min(rotated.getHeight(), bbox.bottom);

        Bitmap result = (right > left && bottom > top)
                ? Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
                : fallback;

        rotated.recycle();
        return result;
    }
}
