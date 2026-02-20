package com.faceauth.sdk.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import com.faceauth.sdk.api.EnrollmentCallback;
import com.faceauth.sdk.api.EnrollmentResult;
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.detection.FaceAligner;
import com.faceauth.sdk.detection.FaceDetector;
import com.faceauth.sdk.embedding.FaceEmbedder;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.overlay.FaceGuideOverlay;
import com.faceauth.sdk.quality.QualityGate;
import com.faceauth.sdk.storage.StorageManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.face.Face;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 등록 내장 Activity.
 * SDK가 직접 관리하며 호출 앱은 참조하지 않음.
 *
 * 등록 순서:
 *   NORMAL 3장 → (안내) → HELMET 3장 → 완료
 */
public final class EnrollmentActivity extends AppCompatActivity {

    public static final String EXTRA_USER_ID         = "user_id";
    public static final String EXTRA_NORMAL_REQUIRED = "normal_required";
    public static final String EXTRA_HELMET_REQUIRED = "helmet_required";

    private static final String TAG              = "EnrollmentActivity";
    private static final int    PERM_REQUEST     = 1001;
    private static final long   CAPTURE_DELAY_MS = 1200L;  // 프레임 간 최소 간격

    // ── SDK 컴포넌트 ─────────────────────────────────────────────────────
    private FaceDetector   faceDetector;
    private FaceEmbedder   faceEmbedder;
    private QualityGate    qualityGate;
    private StorageManager storageManager;
    private FaceAuthConfig config;

    // ── UI ───────────────────────────────────────────────────────────────
    private PreviewView     previewView;
    private FaceGuideOverlay guideOverlay;
    private TextView        tvStatus;
    private ProgressBar     progressBar;

    // ── 등록 상태 ─────────────────────────────────────────────────────────
    private String  userId;
    private int     normalRequired, helmetRequired;
    private int     normalCount = 0, helmetCount = 0;
    private boolean capturingHelmet = false;
    private boolean enrollmentDone  = false;

    private long lastCaptureMs = 0;

    private ExecutorService cameraExecutor;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enrollment);

        userId          = getIntent().getStringExtra(EXTRA_USER_ID);
        normalRequired  = getIntent().getIntExtra(EXTRA_NORMAL_REQUIRED, 3);
        helmetRequired  = getIntent().getIntExtra(EXTRA_HELMET_REQUIRED, 3);

        previewView   = findViewById(R.id.preview_view);
        guideOverlay  = findViewById(R.id.face_guide_overlay);
        tvStatus      = findViewById(R.id.tv_status);
        progressBar   = findViewById(R.id.progress_bar);

        config         = FaceAuthSdk.getConfig();
        storageManager = FaceAuthSdk.getStorageManager();
        qualityGate    = new QualityGate(config);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            faceDetector = new FaceDetector();
            faceEmbedder = new FaceEmbedder(this, config);
        } catch (Exception e) {
            SafeLogger.e(TAG, "컴포넌트 초기화 실패", e);
            deliverFailure("초기화 오류가 발생했습니다.");
            return;
        }

        updateStatus();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector front = CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, front, preview, analysis);

            } catch (Exception e) {
                SafeLogger.e(TAG, "카메라 시작 실패", e);
                deliverFailure("카메라를 시작할 수 없습니다.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * CameraX Analyzer 콜백.
     * 백그라운드 스레드(cameraExecutor)에서 실행.
     */
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (enrollmentDone) { imageProxy.close(); return; }

        // 쓰로틀링
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < CAPTURE_DELAY_MS) { imageProxy.close(); return; }

        try {
            Bitmap frame = ImageUtils.toBitmap(imageProxy);
            if (frame == null) return;

            // 얼굴 검출
            List<Face> faces = faceDetector.detectSync(frame);
            if (faces.isEmpty()) {
                showGuide(FaceGuideOverlay.GuideState.IDLE, "얼굴이 감지되지 않습니다.");
                frame.recycle();
                return;
            }
            Face face = faces.get(0);

            // 품질 게이트
            QualityGate.Result qr = qualityGate.check(frame, face, frame.getWidth(), frame.getHeight());
            if (!qr.passed) {
                showGuide(FaceGuideOverlay.GuideState.FAIL, qr.guideMessage);
                frame.recycle();
                return;
            }

            // 정렬 + 임베딩
            Bitmap aligned = FaceAligner.align(frame, face);
            float[] embedding = faceEmbedder.embed(aligned);
            aligned.recycle();
            frame.recycle();

            // 저장
            String profileType = capturingHelmet ? "HELMET" : "NORMAL";
            storageManager.saveProfile(userId, profileType, embedding, qr.passed ? 1.0f : 0.8f);

            lastCaptureMs = now;
            if (capturingHelmet) {
                helmetCount++;
            } else {
                normalCount++;
                if (normalCount >= normalRequired) {
                    capturingHelmet = true;
                    showGuide(FaceGuideOverlay.GuideState.IDLE,
                            "안전모를 착용한 후 카메라를 바라봐 주세요.");
                }
            }

            mainHandler.post(this::updateStatus);

            // 완료 확인
            if (normalCount >= normalRequired && helmetCount >= helmetRequired) {
                enrollmentDone = true;
                deliverSuccess();
            }

        } catch (Exception e) {
            SafeLogger.e(TAG, "프레임 분석 오류", e);
        } finally {
            imageProxy.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    private void updateStatus() {
        String mode  = capturingHelmet ? "안전모" : "일반";
        int    done  = capturingHelmet ? helmetCount : normalCount;
        int    total = capturingHelmet ? helmetRequired : normalRequired;
        tvStatus.setText(mode + " 얼굴 등록 중 (" + done + "/" + total + ")");
        progressBar.setMax(normalRequired + helmetRequired);
        progressBar.setProgress(normalCount + helmetCount);
    }

    private void showGuide(FaceGuideOverlay.GuideState state, String msg) {
        mainHandler.post(() -> guideOverlay.update(state, msg));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 결과 전달
    // ─────────────────────────────────────────────────────────────────────

    private void deliverSuccess() {
        EnrollmentResult result = new EnrollmentResult(
                userId, normalCount, helmetCount,
                EnrollmentResult.Status.SUCCESS,
                "등록이 완료되었습니다. (일반 " + normalCount + "장, 안전모 " + helmetCount + "장)");
        mainHandler.post(() -> {
            EnrollmentCallback cb = FaceAuthSdk.consumePendingEnrollCallback();
            if (cb != null) cb.onResult(result);
            finish();
        });
    }

    private void deliverFailure(String message) {
        EnrollmentResult result = new EnrollmentResult(
                userId, normalCount, helmetCount,
                EnrollmentResult.Status.FAILED, message);
        mainHandler.post(() -> {
            EnrollmentCallback cb = FaceAuthSdk.consumePendingEnrollCallback();
            if (cb != null) cb.onResult(result);
            finish();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 권한
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            deliverFailure("카메라 권한이 거부되었습니다. 설정에서 허용해 주세요.");
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
