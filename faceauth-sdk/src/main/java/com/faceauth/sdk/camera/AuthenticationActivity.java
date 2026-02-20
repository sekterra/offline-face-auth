package com.faceauth.sdk.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.faceauth.sdk.R;
import com.faceauth.sdk.api.AuthCallback;
import com.faceauth.sdk.api.AuthResult;
import com.faceauth.sdk.api.FailureReason;
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.detection.FaceAligner;
import com.faceauth.sdk.detection.FaceDetector;
import com.faceauth.sdk.embedding.FaceEmbedder;
import com.faceauth.sdk.liveness.LivenessChecker;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.matcher.EmbeddingMatcher;
import com.faceauth.sdk.overlay.FaceGuideOverlay;
import com.faceauth.sdk.quality.QualityGate;
import com.faceauth.sdk.storage.ProfileRecord;
import com.faceauth.sdk.storage.StorageManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.face.Face;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 인증 내장 Activity.
 * SSoT §2.2 인증 흐름을 구현.
 *
 * 1. QualityGate 통과
 * 2. Liveness (눈깜빡임 챌린지)
 * 3. Embedding → Top-1 매칭
 * 4. match_score >= 0.80 → SUCCESS
 */
public final class AuthenticationActivity extends AppCompatActivity {

    private static final String TAG          = "AuthActivity";
    private static final int    PERM_REQUEST = 1002;
    private static final int    MAX_RETRIES  = 2;

    private FaceDetector   faceDetector;
    private FaceEmbedder   faceEmbedder;
    private QualityGate    qualityGate;
    private LivenessChecker livenessChecker;
    private StorageManager storageManager;
    private FaceAuthConfig config;

    private PreviewView      previewView;
    private FaceGuideOverlay guideOverlay;

    private List<ProfileRecord> candidates;  // 인증 시작 시 1회 로딩
    private boolean             authDone   = false;
    private int                 livenessRetry = 0;

    private ExecutorService cameraExecutor;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authentication);

        previewView  = findViewById(R.id.preview_view);
        guideOverlay = findViewById(R.id.face_guide_overlay);

        config         = FaceAuthSdk.getConfig();
        storageManager = FaceAuthSdk.getStorageManager();
        qualityGate    = new QualityGate(config);
        livenessChecker = new LivenessChecker(
                config.earCloseThreshold,
                config.earOpenThreshold,
                config.livenessBlinkCount,
                config.livenessWindowMs);

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            faceDetector = new FaceDetector();
            faceEmbedder = new FaceEmbedder(this, config);
        } catch (Exception e) {
            deliverResult(AuthResult.failure(
                    FailureReason.FAIL_INTERNAL, 0f, "초기화 오류가 발생했습니다."));
            return;
        }

        // 후보 프로파일 사전 로딩 (백그라운드)
        cameraExecutor.execute(() -> {
            candidates = storageManager.loadAllActiveProfiles();
            if (candidates.isEmpty()) {
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_INTERNAL, 0f, "등록된 얼굴이 없습니다."));
                return;
            }
            SafeLogger.i(TAG, "후보 프로파일 로딩 완료 (count=" + candidates.size() + ")");
        });

        guideOverlay.update(FaceGuideOverlay.GuideState.IDLE, "얼굴을 원 안에 맞춰주세요.");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                SafeLogger.e(TAG, "카메라 시작 실패", e);
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_CAMERA, 0f, "카메라를 시작할 수 없습니다."));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy proxy) {
        if (authDone || candidates == null) { proxy.close(); return; }

        try {
            Bitmap frame = ImageUtils.toBitmap(proxy);
            if (frame == null) return;

            // [1] 얼굴 검출
            List<Face> faces = faceDetector.detectSync(frame);
            if (faces.isEmpty()) {
                showGuide(FaceGuideOverlay.GuideState.IDLE, "얼굴이 감지되지 않습니다.");
                frame.recycle(); return;
            }
            Face face = faces.get(0);

            // [2] 품질 게이트
            QualityGate.Result qr = qualityGate.check(frame, face, frame.getWidth(), frame.getHeight());
            if (!qr.passed) {
                showGuide(FaceGuideOverlay.GuideState.FAIL, qr.guideMessage);
                frame.recycle(); return;
            }

            showGuide(FaceGuideOverlay.GuideState.QUALITY_OK, "눈을 깜빡여 주세요.");

            // [3] Liveness 챌린지
            if (!livenessChecker.isStarted()) {
                livenessChecker.start();
            }
            LivenessChecker.State lState = livenessChecker.processFrame(face);

            if (lState == LivenessChecker.State.FAILED) {
                livenessRetry++;
                if (livenessRetry >= MAX_RETRIES) {
                    authDone = true;
                    deliverResult(AuthResult.failure(
                            FailureReason.FAIL_LIVENESS, 0f,
                            "눈깜빡임 인식에 실패했습니다. 다시 시도해 주세요."));
                } else {
                    livenessChecker.start();
                    showGuide(FaceGuideOverlay.GuideState.FAIL,
                            "눈깜빡임을 인식하지 못했습니다. 다시 눈을 깜빡여 주세요.");
                }
                frame.recycle(); return;
            }

            if (lState == LivenessChecker.State.ONGOING) {
                int blinks = livenessChecker.getBlinkCount();
                showGuide(FaceGuideOverlay.GuideState.LIVENESS,
                        "눈깜빡임 " + blinks + "/" + config.livenessBlinkCount);
                frame.recycle(); return;
            }

            // lState == PASSED → [4] 임베딩 + 매칭
            showGuide(FaceGuideOverlay.GuideState.LIVENESS, "인식 중...");

            Bitmap aligned = FaceAligner.align(frame, face);
            long t0 = System.nanoTime();
            float[] liveEmb = faceEmbedder.embed(aligned);
            long inferenceMs = (System.nanoTime() - t0) / 1_000_000;
            aligned.recycle();
            frame.recycle();

            Log.d("FaceAuthPOC", "embedding_dim=" + liveEmb.length
                    + " inference_ms=" + inferenceMs);

            EmbeddingMatcher.MatchResult match =
                    EmbeddingMatcher.findTopMatch(liveEmb, candidates);

            float rawCosineSim = 2f * match.matchScore - 1f;
            Log.d("FaceAuthPOC", "raw_cosine_similarity=" + String.format("%.4f", rawCosineSim)
                    + " match_score_normalized=" + String.format("%.4f", match.matchScore));

            // audit 저장
            String auditResult = match.matchScore >= config.matchThreshold
                    ? "SUCCESS" : "FAIL_MATCH";
            storageManager.saveAudit(
                    auditResult,
                    match.bestProfile != null ? match.bestProfile.userId : null,
                    match.matchScore,
                    config.pocMode ? "{score:" + match.matchScore + "}" : null);

            authDone = true;
            if (match.matchScore >= config.matchThreshold && match.bestProfile != null) {
                deliverResult(AuthResult.success(match.bestProfile.userId, match.matchScore));
            } else {
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_MATCH, match.matchScore,
                        "얼굴 인식에 실패했습니다. 다른 방법으로 로그인해 주세요."));
            }

        } catch (Exception e) {
            SafeLogger.e(TAG, "인증 프레임 처리 오류", e);
            if (!authDone) {
                authDone = true;
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_INTERNAL, 0f, "내부 오류가 발생했습니다."));
            }
        } finally {
            proxy.close();
        }
    }

    private void showGuide(FaceGuideOverlay.GuideState state, String msg) {
        mainHandler.post(() -> guideOverlay.update(state, msg));
    }

    private void deliverResult(AuthResult result) {
        mainHandler.post(() -> {
            AuthCallback cb = FaceAuthSdk.consumePendingAuthCallback();
            if (cb != null) cb.onResult(result);
            finish();
        });
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(rc, perms, grants);
        if (rc == PERM_REQUEST && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            deliverResult(AuthResult.failure(
                    FailureReason.FAIL_CAMERA, 0f,
                    "카메라 권한이 거부되었습니다. 설정에서 허용해 주세요."));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
        if (faceEmbedder != null) faceEmbedder.close();
    }
}
