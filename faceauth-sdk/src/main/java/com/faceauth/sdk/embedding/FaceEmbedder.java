package com.faceauth.sdk.embedding;

import android.content.Context;
import android.graphics.Bitmap;

import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.api.FaceAuthConfig;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.Arrays;

/**
 * TFLite 기반 얼굴 임베딩 추출기 — face_embedder 모델.
 *
 * ── face_embedder 스펙 ─────────────────────────────────────────────────
 *   입력  : float32[1, 112, 112, 3]
 *   정규화: (pixel - 127.5) / 128.0  →  범위 [-1, 1]
 *   출력  : float32[1, 192]  — 모델 내부에서 이미 L2 정규화 완료
 *   파일  : assets/face_embedder.tflite
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
    private static final int    INPUT_SIZE = 112;   // face_embedder 고정 입력 크기

    private final FaceAuthConfig config;
    private final Interpreter  interpreter;
    private volatile boolean   closed;
    private final int         embeddingDim;
    private final float        inputMean;           // face_embedder: 127.5
    private final float        inputStd;             // face_embedder: 128.0
    private final boolean      outputIsNormalized;   // face_embedder: true
    private final ByteBuffer   inputBuffer;         // 재사용 (스레드 비안전 — 호출자가 직렬화)

    public FaceEmbedder(Context context, FaceAuthConfig config) throws EmbeddingException {
        this.config             = config;
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
            SafeLogger.i(TAG, "face_embedder 로드 완료"
                    + " | 입력=" + INPUT_SIZE + "×" + INPUT_SIZE
                    + " | 출력=" + embeddingDim + "-d"
                    + " | 정규화=(" + inputMean + ", " + inputStd + ")"
                    + " | L2정규화출력=" + outputIsNormalized);

        } catch (IOException e) {
            throw new EmbeddingException(
                    "TFLite 모델 로드 실패: " + config.tfliteModelAsset
                    + " (assets/ 폴더에 face_embedder.tflite 배치 여부 확인)", e);
        }
    }

    /**
     * 정렬된 112×112 Bitmap → float[] 임베딩.
     * @param alignedFace FaceAligner.align() 결과 112×112 Bitmap
     * @return L2 정규화된 임베딩 벡터
     * @throws EmbeddingException 추론/사전검사 실패 시
     */
    public float[] embed(Bitmap alignedFace) throws EmbeddingException {
        return embed(alignedFace, null);
    }

    /**
     * commit 시작 시각 전달 시 auth_tflite_infer_start에 elapsedMsFromCommitStart 로깅.
     */
    public float[] embed(Bitmap alignedFace, Long commitStartTimeMs) throws EmbeddingException {
        if (closed) throw new EmbeddingException("EMBEDDER_CLOSED", "FaceEmbedder already closed", null);
        long t0 = System.currentTimeMillis();
        int bitmapW = alignedFace != null ? alignedFace.getWidth() : 0;
        int bitmapH = alignedFace != null ? alignedFace.getHeight() : 0;
        String bitmapConfig = alignedFace != null ? String.valueOf(alignedFace.getConfig()) : "null";
        String threadName = Thread.currentThread().getName();

        if (alignedFace == null) {
            logTfliteFailPrecheck("TFLITE_PRECHECK_FAIL", "alignedFace is null", null);
            throw new EmbeddingException("TFLITE_PRECHECK_FAIL", "alignedFace is null");
        }
        if (bitmapW != INPUT_SIZE || bitmapH != INPUT_SIZE) {
            logTfliteFailPrecheck("TFLITE_INPUT_SHAPE_MISMATCH",
                    "bitmap " + bitmapW + "x" + bitmapH + " need " + INPUT_SIZE + "x" + INPUT_SIZE, null);
            throw new EmbeddingException("TFLITE_INPUT_SHAPE_MISMATCH",
                    "입력 크기 오류: " + bitmapW + "×" + bitmapH + " (필요: " + INPUT_SIZE + "×" + INPUT_SIZE + ")");
        }

        Tensor inputTensor = null;
        try {
            inputTensor = interpreter.getInputTensor(0);
        } catch (Exception e) {
            SafeLogger.w(TAG, "getInputTensor(0) 미지원 또는 실패, 사전검사 생략: " + e.getMessage());
        }
        int[] inputShape = inputTensor != null ? inputTensor.shape() : null;
        String inputDtype = inputTensor != null && inputTensor.dataType() != null ? inputTensor.dataType().name() : "?";
        int inputNumBytes = inputTensor != null ? inputTensor.numBytes() : 0;
        int expectedBytes = INPUT_SIZE * INPUT_SIZE * 3 * Float.BYTES;
        if (inputTensor != null && inputNumBytes != expectedBytes) {
            logEvent("auth_tflite_precheck",
                    "modelName", config.tfliteModelAsset,
                    "inputShape", inputShape != null ? Arrays.toString(inputShape) : "?",
                    "inputDtype", inputDtype,
                    "inputNumBytes", inputNumBytes, "expectedBytes", expectedBytes,
                    "bitmapW", bitmapW, "bitmapH", bitmapH, "bitmapConfig", bitmapConfig,
                    "threadName", threadName);
            logTfliteFailPrecheck("TFLITE_INPUT_SHAPE_MISMATCH",
                    "input tensor bytes " + inputNumBytes + " != expected " + expectedBytes, null);
            throw new EmbeddingException("TFLITE_INPUT_SHAPE_MISMATCH",
                    "모델 입력 버퍼 크기 불일치: " + inputNumBytes + " != " + expectedBytes);
        }

        logEvent("auth_tflite_precheck",
                "modelName", config.tfliteModelAsset,
                "inputShape", inputShape != null ? Arrays.toString(inputShape) : "?",
                "inputDtype", inputDtype,
                "bitmapW", bitmapW, "bitmapH", bitmapH, "bitmapConfig", bitmapConfig,
                "threadName", threadName);

        long elapsedFromCommit = commitStartTimeMs != null ? t0 - commitStartTimeMs : -1L;
        logEvent("auth_tflite_infer_start", "elapsedMsFromCommitStart", elapsedFromCommit);

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
        inputBuffer.rewind();

        // 모델 실제 출력 shape 사용 (mobileFaceNet [1,512], face_embedder [1,192] 등)
        int outputDim = embeddingDim;
        try {
            Tensor outputTensor = interpreter.getOutputTensor(0);
            if (outputTensor != null) {
                int[] outShape = outputTensor.shape();
                if (outShape != null && outShape.length >= 2) outputDim = outShape[1];
                else if (outShape != null && outShape.length == 1) outputDim = outShape[0];
            }
        } catch (Exception e) {
            SafeLogger.w(TAG, "getOutputTensor(0) 실패, config.embeddingDim 사용: " + e.getMessage());
        }
        float[][] outputBuffer = new float[1][outputDim];

        try {
            interpreter.run(inputBuffer, outputBuffer);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            String messageTrunc = msg.length() > 200 ? msg.substring(0, 200) : msg;
            int stackHash = (e.getStackTrace() != null && e.getStackTrace().length > 0)
                    ? e.getStackTrace()[0].hashCode() : 0;
            logEvent("auth_tflite_infer_fail",
                    "errorCode", "TFLITE_INFER_FAIL",
                    "exceptionType", e.getClass().getName(),
                    "messageTrunc", messageTrunc,
                    "stackHash", stackHash,
                    "bitmapW", bitmapW, "bitmapH", bitmapH,
                    "inputShape", inputShape != null ? Arrays.toString(inputShape) : "?",
                    "inputDtype", inputDtype,
                    "outputDimExpected", outputDim);
            SafeLogger.e(TAG, "TFLite 추론 실패", e);
            throw new EmbeddingException("TFLITE_INFER_FAIL", "TFLite 추론 실패", e);
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        float[] embedding = outputBuffer[0];
        double norm = 0;
        for (float x : embedding) norm += (double) x * x;
        norm = Math.sqrt(norm);
        logEvent("auth_tflite_infer_success",
                "elapsedMs", elapsedMs, "embeddingDim", embedding.length, "embeddingNorm", (float) norm);

        return outputIsNormalized ? embedding : l2Normalize(embedding);
    }

    private void logTfliteFailPrecheck(String reason, String details, String extra) {
        logEvent("auth_register_failed", "reason", reason, "details", details != null ? details : "");
    }

    private void logEvent(String event, Object... kvs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"event\":\"").append(event).append("\"");
        for (int i = 0; i < kvs.length - 1; i += 2) {
            sb.append(",\"").append(kvs[i]).append("\":");
            Object v = kvs[i + 1];
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(String.valueOf(v).replace("\"", "'")).append("\"");
        }
        sb.append("}");
        SafeLogger.i(TAG, sb.toString());
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
        closed = true;
        if (interpreter != null) interpreter.close();
    }
}
