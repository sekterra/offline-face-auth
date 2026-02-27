package com.faceauth.sdk.liveness;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import android.graphics.PointF;

/**
 * EAR(Eye Aspect Ratio) 기반 눈깜빡임 감지기.
 *
 * SSoT §7 정책:
 *   - 챌린지: 3초 내 2회 깜빡임
 *   - EAR < earCloseThreshold → closed
 *   - EAR > earOpenThreshold  → open
 *   - closed → open 전이 = blink 1회
 *   - 실패: 최대 2회 재시도 후 FAIL_LIVENESS
 *
 * 호출 방식:
 *   LivenessChecker checker = new LivenessChecker(config);
 *   checker.start();  // 챌린지 시작 (타이머 시작)
 *   // 매 프레임마다:
 *   LivenessChecker.State state = checker.processFrame(face);
 *   // state == PASSED → 성공
 *   // state == FAILED → 실패
 *   // state == ONGOING → 계속 진행
 */
public final class LivenessChecker {

    public enum State { ONGOING, PASSED, FAILED }

    private final float earCloseThreshold;
    private final float earOpenThreshold;
    private final int   requiredBlinks;
    private final long  windowMs;

    private int   blinkCount   = 0;
    private boolean eyesClosed = false;
    private long  startTimeMs  = -1;

    public LivenessChecker(float earClose, float earOpen, int requiredBlinks, long windowMs) {
        this.earCloseThreshold = earClose;
        this.earOpenThreshold  = earOpen;
        this.requiredBlinks    = requiredBlinks;
        this.windowMs          = windowMs;
    }

    /** 챌린지 시작 (타이머 리셋) */
    public void start() {
        blinkCount  = 0;
        eyesClosed  = false;
        startTimeMs = System.currentTimeMillis();
    }

    public boolean isStarted() { return startTimeMs >= 0; }

    /**
     * 프레임 처리 — MediaPipe/MLKit Face에서 랜드마크 기반 EAR 계산.
     *
     * ML Kit의 경우 정밀한 눈 윤곽 랜드마크가 없으므로,
     * leftEyeOpenProbability / rightEyeOpenProbability를 대체로 사용.
     *
     * 정밀 구현: MediaPipe Face Mesh (468 랜드마크)로 교체 권장 (TODO).
     */
    public State processFrame(Face face) {
        if (startTimeMs < 0) return State.ONGOING;  // start() 미호출

        // 타임아웃 체크
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed > windowMs) return State.FAILED;

        // ML Kit 분류기 기반 EAR 대체
        Float leftOpen  = face.getLeftEyeOpenProbability();
        Float rightOpen = face.getRightEyeOpenProbability();

        if (leftOpen == null || rightOpen == null) return State.ONGOING;

        // 양 눈 평균으로 단일 EAR 대체
        float avgOpen = (leftOpen + rightOpen) / 2f;

        // EAR < close → 눈 감음
        // 단순 매핑: openProbability < 0.2 → closed, > 0.7 → open
        boolean isClosed = avgOpen < (1f - earOpenThreshold);   // ~0.73
        boolean isOpen   = avgOpen > (1f - earCloseThreshold);  // ~0.79

        if (isClosed && !eyesClosed) {
            eyesClosed = true;
        } else if (isOpen && eyesClosed) {
            eyesClosed = false;
            blinkCount++;
            if (blinkCount >= requiredBlinks) return State.PASSED;
        }

        return State.ONGOING;
    }

    public int getBlinkCount() { return blinkCount; }

    // ── 참고: 정밀 EAR 공식 (MediaPipe 468 랜드마크 사용 시) ──────────────
    // EAR = (||p2-p6|| + ||p3-p5||) / (2 * ||p1-p4||)
    // p1~p6: 눈 외곽 6개 랜드마크 좌표
    @SuppressWarnings("unused")
    private static float computeEar(PointF p1, PointF p2, PointF p3,
                                    PointF p4, PointF p5, PointF p6) {
        float A = dist(p2, p6);
        float B = dist(p3, p5);
        float C = dist(p1, p4);
        return (A + B) / (2f * C + 1e-6f);
    }

    private static float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
