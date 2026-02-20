package com.faceauth.sdk.embedding;

import android.content.Context;
import android.graphics.Bitmap;

import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.api.FaceAuthConfig;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;

/**
 * TFLite 기반 얼굴 임베딩 추출기 — MobileFaceNet 최적화.
 *
 * ── MobileFaceNet 스펙 ──────────────────────────────────────────────────
 *   입력  : float32[1, 112, 112, 3]
 *   정규화: (pixel - 127.5) / 128.0  →  범위 [-1, 1]
 *           ※ /127.5 가 아닌 /128.0 임에 주의 (sirius-ai 원 구현 기준)
 *   출력  : float32[1, 192]  — 모델 내부에서 이미 L2 정규화 완료
 *   파일  : assets/mobilefacenet.tflite  (~5.2 MB)
 *
 * ── 다른 모델로 교체 시 ─────────────────────────────────────────────────
 *   FaceAuthConfig.Builder 에서 아래 값만 변경:
 *     .tfliteModelAsset("other_model.tflite")
 *     .inputNormalization(mean, std)
 *     .embeddingDim(512)
 *     .modelOutputIsNormalized(false)   ← 모델이 L2 정규화 미포함 시
 */
public final class FaceEmbedder {

    private static final String TAG        = "FaceEmbedder";
    private static final int    INPUT_SIZE = 112;   // MobileFaceNet 고정 입력 크기

    private final Interpreter  interpreter;
    private final int          embeddingDim;
    private final float        inputMean;           // MobileFaceNet: 127.5
    private final float        inputStd;            // MobileFaceNet: 128.0
    private final boolean      outputIsNormalized;  // MobileFaceNet: true
    private final ByteBuffer   inputBuffer;         // 재사용 (스레드 비안전 — 호출자가 직렬화)

    public FaceEmbedder(Context context, FaceAuthConfig config) throws EmbeddingException {
        this.embeddingDim       = config.embeddingDim;
        this.inputMean          = config.inputMean;
        this.inputStd           = config.inputStd;
        this.outputIsNormalized = config.modelOutputIsNormalized;

        try {
            MappedByteBuffer modelFile = FileUtil.loadMappedFile(context, config.tfliteModelAsset);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(2);
            // GPU delegate (선택): options.addDelegate(new GpuDelegate());
            this.interpreter = new Interpreter(modelFile, options);

            // 입력 버퍼 사전 할당: 1 × 112 × 112 × 3 × 4bytes (float32)
            int bufSize = INPUT_SIZE * INPUT_SIZE * 3 * Float.BYTES;
            this.inputBuffer = ByteBuffer.allocateDirect(bufSize);
            this.inputBuffer.order(ByteOrder.nativeOrder());

            // 실제 모델 입출력 스펙 로그 (POC 검증용)
            SafeLogger.i(TAG, "MobileFaceNet 로드 완료"
                    + " | 입력=" + INPUT_SIZE + "×" + INPUT_SIZE
                    + " | 출력=" + embeddingDim + "-d"
                    + " | 정규화=(" + inputMean + ", " + inputStd + ")"
                    + " | L2정규화출력=" + outputIsNormalized);

        } catch (IOException e) {
            throw new EmbeddingException(
                    "TFLite 모델 로드 실패: " + config.tfliteModelAsset
                    + " (assets/ 폴더에 mobilefacenet.tflite 배치 여부 확인)", e);
        }
    }

    /**
     * 정렬된 112×112 Bitmap → float[] 임베딩.
     *
     * MobileFaceNet은 이미 L2 정규화된 임베딩을 출력하므로
     * {@code config.modelOutputIsNormalized == true} 이면 추가 정규화 생략.
     *
     * @param alignedFace FaceAligner.align() 결과 112×112 Bitmap
     * @return L2 정규화된 임베딩 벡터 (EmbeddingMatcher에 바로 전달 가능)
     * @throws EmbeddingException 추론 실패 시
     */
    public float[] embed(Bitmap alignedFace) throws EmbeddingException {
        if (alignedFace.getWidth()  != INPUT_SIZE
                || alignedFace.getHeight() != INPUT_SIZE) {
            throw new EmbeddingException(
                    "입력 크기 오류: " + alignedFace.getWidth()
                    + "×" + alignedFace.getHeight()
                    + " (필요: " + INPUT_SIZE + "×" + INPUT_SIZE + ")");
        }

        // ── Bitmap → ByteBuffer ───────────────────────────────────────────
        // MobileFaceNet 정규화: (pixel - 127.5) / 128.0
        inputBuffer.rewind();
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        alignedFace.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        for (int px : pixels) {
            float r = (((px >> 16) & 0xFF) - inputMean) / inputStd;
            float g = (((px >>  8) & 0xFF) - inputMean) / inputStd;
            float b = (( px        & 0xFF) - inputMean) / inputStd;
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }

        // ── TFLite 추론 ───────────────────────────────────────────────────
        float[][] outputBuffer = new float[1][embeddingDim];
        try {
            interpreter.run(inputBuffer, outputBuffer);
        } catch (Exception e) {
            throw new EmbeddingException("TFLite 추론 실패", e);
        }

        float[] embedding = outputBuffer[0];

        // MobileFaceNet은 출력이 이미 L2 정규화됨 → 추가 정규화 불필요
        // 다른 모델 사용 시 config.modelOutputIsNormalized = false 로 설정
        return outputIsNormalized ? embedding : l2Normalize(embedding);
    }

    /**
     * 수동 L2 정규화.
     * MobileFaceNet 사용 시 호출되지 않음. 다른 모델 교체 대비용.
     */
    private static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return v;  // zero vector 방어
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) result[i] = (float)(v[i] / norm);
        return result;
    }

    public void close() {
        if (interpreter != null) interpreter.close();
    }
}
