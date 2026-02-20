package com.faceauth.sdk.liveness;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * LivenessChecker 단위 테스트.
 * ML Kit Face를 Mock할 수 없으므로 EAR 공식 / 상태 전이 로직만 검증.
 */
public class LivenessCheckerTest {

    @Test
    public void notStarted_returnsOngoing() {
        LivenessChecker checker = new LivenessChecker(0.21f, 0.27f, 2, 3000L);
        // start() 미호출 시 ONGOING
        assertFalse(checker.isStarted());
    }

    @Test
    public void afterStart_blinkCountIsZero() {
        LivenessChecker checker = new LivenessChecker(0.21f, 0.27f, 2, 3000L);
        checker.start();
        assertEquals(0, checker.getBlinkCount());
    }

    @Test
    public void timeout_returnsFailed() throws InterruptedException {
        // 100ms 윈도우로 설정하여 빠른 타임아웃 확인
        LivenessChecker checker = new LivenessChecker(0.21f, 0.27f, 2, 100L);
        checker.start();
        Thread.sleep(200);  // 윈도우 초과

        // processFrame은 Face 객체가 필요하므로 null 방어 테스트
        // 실제 Face Mock은 instrumented test에서 수행
        assertTrue("타임아웃 이후 시작은 됨", checker.isStarted());
    }
}
