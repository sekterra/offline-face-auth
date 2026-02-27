package com.faceauth.sdk.detection;

import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ML Kit Face Detection 래퍼.
 * - 랜드마크 + Euler Angle + Classification 활성화 (EAR 계산용)
 * - 동기식 호출을 지원하기 위해 CountDownLatch 사용
 *   (Camera Analyzer는 이미 백그라운드 스레드에서 실행됨)
 */
public final class FaceDetector {

    private final com.google.mlkit.vision.face.FaceDetector detector;

    public FaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();
        detector = FaceDetection.getClient(options);
    }

    /**
     * 동기식 얼굴 검출 (SSoT: 분석기 경로용). MediaImage 기반 InputImage 사용.
     *
     * @param inputImage InputImage.fromMediaImage(mediaImage, rotation) 로 생성한 이미지
     * @return 검출된 얼굴 목록 (빈 리스트 = 얼굴 없음)
     * @throws DetectionException 처리 실패 시
     */
    public List<Face> detectSync(InputImage inputImage) throws DetectionException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<Face>>  result = new AtomicReference<>();
        AtomicReference<Exception>   error  = new AtomicReference<>();

        detector.process(inputImage)
                .addOnSuccessListener(faces -> { result.set(faces); latch.countDown(); })
                .addOnFailureListener(e     -> { error.set(e);      latch.countDown(); });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DetectionException("얼굴 검출 인터럽트", e);
        }

        if (error.get() != null) {
            throw new DetectionException("얼굴 검출 실패", error.get());
        }
        return result.get();
    }

    /**
     * 동기식 얼굴 검출 (Bitmap). COMMITTING/품질·정렬 등 Bitmap 필요 시에만 사용.
     *
     * @param bitmap 분석할 Bitmap (회전 보정 완료 상태)
     * @return 검출된 얼굴 목록 (빈 리스트 = 얼굴 없음)
     * @throws DetectionException 처리 실패 시
     */
    public List<Face> detectSync(Bitmap bitmap) throws DetectionException {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<Face>>  result = new AtomicReference<>();
        AtomicReference<Exception>   error  = new AtomicReference<>();

        detector.process(image)
                .addOnSuccessListener(faces -> { result.set(faces); latch.countDown(); })
                .addOnFailureListener(e     -> { error.set(e);      latch.countDown(); });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DetectionException("얼굴 검출 인터럽트", e);
        }

        if (error.get() != null) {
            throw new DetectionException("얼굴 검출 실패", error.get());
        }
        return result.get();
    }

    public void close() {
        detector.close();
    }
}
