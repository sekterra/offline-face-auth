package com.faceauth.sdk.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
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
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.detection.FaceDetector;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.overlay.FaceGuideOverlay;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 라이브니스 검증 전용 화면. SSoT: 머리 돌리기(LEFT/RIGHT) 1회만.
 * CENTER → 선택된 방향(LEFT 또는 RIGHT) 도달 시 성공. 60초 제한.
 */
public final class LivenessActivity extends AppCompatActivity {

    private static final String TAG = "LivenessActivity";
    private static final int PERM_REQUEST = 1003;
    private static final int YAW_SMOOTHING_FRAMES = 5;
    private static final int CONSECUTIVE_REQUIRED = 3;
    private static final long WINDOW_MS = 60_000L;

    private FaceDetector faceDetector;
    private FaceAuthConfig config;
    private PreviewView previewView;
    private FaceGuideOverlay guideOverlay;
    private LinearLayout panelResult;
    private TextView tvLivenessResult;
    private Button btnBackToList;

    private enum LivenessState { WAIT_CENTER, TURN, PASSED, FAILED }
    private LivenessState state = LivenessState.WAIT_CENTER;
    private boolean challengeLeft;  // true = LEFT, false = RIGHT
    private int consecutiveFrames = 0;
    private long startTimeMs;
    private final Deque<Float> yawHistory = new ArrayDeque<>(YAW_SMOOTHING_FRAMES);

    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liveness);

        config = FaceAuthSdk.getConfig();
        float centerMax = config.yawCenterMaxAbsDeg;
        float leftMin = config.yawLeftMinDeg;
        float rightMin = config.yawRightMinDeg;

        challengeLeft = Math.random() < 0.5;
        startTimeMs = System.currentTimeMillis();
        long windowMs = config.livenessTimeoutMs > 0 ? config.livenessTimeoutMs : WINDOW_MS;
        if (windowMs < 1000) windowMs = WINDOW_MS;

        SafeLogger.i(TAG, String.format(
                "{\"event\":\"auth_liveness_start\",\"challenge\":\"%s\",\"windowMs\":%d}",
                challengeLeft ? "LEFT" : "RIGHT", windowMs));

        previewView = findViewById(R.id.preview_view);
        guideOverlay = findViewById(R.id.face_guide_overlay);
        panelResult = findViewById(R.id.panel_result);
        tvLivenessResult = findViewById(R.id.tv_liveness_result);
        btnBackToList = findViewById(R.id.btn_back_to_list);
        guideOverlay.setConfig(config);

        btnBackToList.setOnClickListener(v -> onBackToListClicked());

        try {
            faceDetector = new FaceDetector();
        } catch (Exception e) {
            SafeLogger.e(TAG, "FaceDetector 초기화 실패", e);
            showFailed("YAW_UNAVAILABLE");
            return;
        }
        cameraExecutor = Executors.newSingleThreadExecutor();

        guideOverlay.update(FaceGuideOverlay.GuideState.IDLE,
                challengeLeft ? "머리를 왼쪽으로 돌려 주세요." : "머리를 오른쪽으로 돌려 주세요.");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                SafeLogger.e(TAG, "카메라 시작 실패", e);
                showFailed("YAW_UNAVAILABLE");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy proxy) {
        if (state == LivenessState.PASSED || state == LivenessState.FAILED) {
            proxy.close();
            return;
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long windowMs = config.livenessTimeoutMs > 0 ? config.livenessTimeoutMs : WINDOW_MS;
        if (elapsed > windowMs) {
            proxy.close();
            mainHandler.post(() -> showFailed("TIMEOUT"));
            return;
        }
        try {
            if (proxy.getImage() == null) return;
            InputImage inputImage = InputImage.fromMediaImage(
                    proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
            List<Face> faces = faceDetector.detectSync(inputImage);
            if (faces.isEmpty()) {
                mainHandler.post(() -> guideOverlay.update(FaceGuideOverlay.GuideState.IDLE, "얼굴을 가이드에 맞춰 주세요."));
                proxy.close();
                return;
            }
            Face face = faces.get(0);
            float yaw = face.getHeadEulerAngleY();
            Integer tid = face.getTrackingId() != null ? face.getTrackingId() : null;

            yawHistory.addLast(yaw);
            if (yawHistory.size() > YAW_SMOOTHING_FRAMES) yawHistory.removeFirst();
            float smoothed = 0f;
            for (Float y : yawHistory) smoothed += y;
            if (!yawHistory.isEmpty()) smoothed /= yawHistory.size();

            boolean inCenter = Math.abs(smoothed) <= config.yawCenterMaxAbsDeg;
            boolean turnOk = challengeLeft ? smoothed <= config.yawLeftMinDeg : smoothed >= config.yawRightMinDeg;

            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_liveness_progress\",\"yaw\":%.1f,\"state\":\"%s\",\"streaks\":%d,\"selectedTrackingId\":%s,\"roiCandidateCount\":1}",
                    smoothed, state.name(), consecutiveFrames, tid != null ? tid : "null"));

            if (state == LivenessState.WAIT_CENTER) {
                if (inCenter) {
                    state = LivenessState.TURN;
                    consecutiveFrames = 0;
                }
                mainHandler.post(() -> guideOverlay.update(FaceGuideOverlay.GuideState.IDLE,
                        "정면을 보신 뒤, " + (challengeLeft ? "왼쪽" : "오른쪽") + "으로 머리를 돌려 주세요."));
            } else if (state == LivenessState.TURN) {
                if (turnOk) {
                    consecutiveFrames++;
                    if (consecutiveFrames >= CONSECUTIVE_REQUIRED) {
                        state = LivenessState.PASSED;
                        mainHandler.post(this::showPassed);
                    }
                } else {
                    consecutiveFrames = 0;
                }
                mainHandler.post(() -> guideOverlay.update(FaceGuideOverlay.GuideState.LIVENESS,
                        challengeLeft ? "머리를 왼쪽으로 돌려 주세요." : "머리를 오른쪽으로 돌려 주세요."));
            }
        } catch (Exception e) {
            SafeLogger.e(TAG, "라이브니스 프레임 오류", e);
        } finally {
            proxy.close();
        }
    }

    private void showPassed() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        SafeLogger.i(TAG, "{\"event\":\"auth_liveness_passed\",\"elapsedMs\":" + elapsed + "}");
        tvLivenessResult.setText("라이브니스 성공");
        panelResult.setVisibility(View.VISIBLE);
        guideOverlay.update(FaceGuideOverlay.GuideState.QUALITY_OK, "라이브니스 성공");
    }

    private void showFailed(String reason) {
        if (state == LivenessState.FAILED) return;
        state = LivenessState.FAILED;
        long elapsed = System.currentTimeMillis() - startTimeMs;
        SafeLogger.i(TAG, String.format(
                "{\"event\":\"auth_liveness_failed\",\"reason\":\"%s\",\"elapsedMs\":%d}",
                reason, elapsed));
        tvLivenessResult.setText("라이브니스 실패 (" + reason + ")");
        panelResult.setVisibility(View.VISIBLE);
        btnBackToList.setVisibility(View.VISIBLE);
    }

    private void onBackToListClicked() {
        SafeLogger.i(TAG, "{\"event\":\"auth_ui_back_to_list_clicked\",\"screen\":\"LIVENESS\",\"timestamp\":" + System.currentTimeMillis() + "}");
        SafeLogger.i(TAG, "{\"event\":\"auth_ui_back_to_list_done\",\"destination\":\"MAIN\",\"timestamp\":" + System.currentTimeMillis() + "}");
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(rc, perms, grants);
        if (rc == PERM_REQUEST && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showFailed("YAW_UNAVAILABLE");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
    }
}
