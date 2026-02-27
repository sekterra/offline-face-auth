package com.faceauth.sdk.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
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
import com.faceauth.sdk.api.EnrollmentResult;
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.detection.FaceAligner;
import com.faceauth.sdk.detection.FaceDetector;
import com.faceauth.sdk.embedding.EmbeddingException;
import com.faceauth.sdk.embedding.FaceEmbedder;
import com.faceauth.sdk.logging.AuthErrorLogger;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.overlay.FaceGuideOverlay;

import java.util.LinkedHashMap;
import java.util.Map;
import com.faceauth.sdk.storage.StorageManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 등록 Activity — FACEAUTH_REGISTRATION_STATE_MACHINE SSoT 구현.
 * 상태: IDLE → ARMED → CAPTURING → COMMITTING → SUCCESS/FAILED.
 * Guide 내부 최대 bbox 1인만 선택, usedYaw만 판정에 사용.
 */
public final class EnrollmentActivity extends AppCompatActivity {

    public static final String EXTRA_USER_ID         = "user_id";
    public static final String EXTRA_NORMAL_REQUIRED  = "normal_required";
    public static final String EXTRA_HELMET_REQUIRED  = "helmet_required";
    /** Activity result: 등록 성공 후 메인으로 돌아갈 때 true */
    public static final String EXTRA_ENROLL_SUCCESS   = "enroll_success";
    /** Activity result: 방금 등록된 사용자 ID */
    public static final String EXTRA_ENROLLED_ID      = "enrolled_id";

    private static final String TAG = "EnrollmentActivity";
    private static final int PERM_REQUEST = 1001;
    private static final long GUIDE_METRICS_LOG_INTERVAL_MS = 1000L;

    enum RegState { IDLE, ARMED, CAPTURING, COMMITTING, SUCCESS, FAILED }

    private FaceDetector   faceDetector;
    private FaceEmbedder   faceEmbedder;
    private StorageManager storageManager;
    private FaceAuthConfig config;

    private PreviewView      previewView;
    private FaceGuideOverlay guideOverlay;
    private TextView         tvStatus;
    private TextView         tvMetrics;
    private Button           btnGoMain;
    private Button           btnReset;

    private String  userId;
    private RegState regState       = RegState.IDLE;
    private String  currentEnrollId = null;
    private int     stableFrames    = 0;
    private Integer lastTrackingId  = null;
    private final AtomicBoolean commitGuard = new AtomicBoolean(false);
    private EnrollmentFrameSnapshot lastSnapshot = null;
    private String failedReason = "";

    /** Cached guide geometry (main thread writes, analyzer reads). */
    private volatile int   lastGuideViewW;
    private volatile int   lastGuideViewH;
    private volatile float lastGuideCx;
    private volatile float lastGuideCy;
    private volatile float lastGuideR;
    private volatile float lastGuideInnerMargin;

    private final Runnable guideMetricsLogger = this::logGuideMetricsOnce;
    private ExecutorService cameraExecutor;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enrollment);

        userId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (userId == null || userId.isEmpty()) userId = "user";

        previewView  = findViewById(R.id.preview_view);
        guideOverlay = findViewById(R.id.face_guide_overlay);
        tvStatus     = findViewById(R.id.tv_status);
        tvMetrics    = findViewById(R.id.tv_metrics);
        btnGoMain    = findViewById(R.id.btn_go_main);
        btnReset     = findViewById(R.id.btn_reset);

        config         = FaceAuthSdk.getConfig();
        storageManager = FaceAuthSdk.getStorageManager();
        guideOverlay.setConfig(config);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            faceDetector = new FaceDetector();
            faceEmbedder = new FaceEmbedder(this, config);
        } catch (Exception e) {
            SafeLogger.e(TAG, "컴포넌트 초기화 실패", e);
            deliverFailure("초기화 오류가 발생했습니다.");
            return;
        }

        btnReset.setOnClickListener(v -> onUserReset());
        updateUI();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
        } else {
            startCamera();
        }
    }

    private void onUserStartEnroll() {
        if (regState != RegState.IDLE) return;
        currentEnrollId = userId;
        stableFrames = 0;
        lastTrackingId = null;
        regState = RegState.ARMED;
        logEvent("auth_register_start", "state", "ARMED",
                "selectedTrackingId", (Object) (lastSnapshot != null ? lastSnapshot.selectedTrackingId : null),
                "facesCount", lastSnapshot != null ? lastSnapshot.facesCount : 0,
                "roiCandidateCount", lastSnapshot != null ? lastSnapshot.roiCandidateCount : 0);
        updateUI();
    }

    /** Camera ready 시 자동으로 ARMED 시작 (등록 시작 버튼 없음). */
    private void autoStartEnrollmentIfReady() {
        if (regState != RegState.IDLE) return;
        if (userId == null || userId.isEmpty()) return;
        onUserStartEnroll();
    }

    private void onUserReset() {
        regState = RegState.IDLE;
        currentEnrollId = null;
        stableFrames = 0;
        lastTrackingId = null;
        commitGuard.set(false);
        updateUI();
        guideOverlay.update(FaceGuideOverlay.GuideState.IDLE, "초기화됨. 뒤로가기로 나갈 수 있습니다.");
    }

    /** SUCCESS 시 "메인으로" 탭: 결과 전달 후 finish. */
    private void onGoMain() {
        if (regState != RegState.SUCCESS || currentEnrollId == null) return;
        logEvent("auth_ui_go_main_clicked", "id", currentEnrollId);
        logEvent("auth_ui_back_to_list_clicked", "screen", "ENROLLMENT", "state", regState.name(), "timestamp", System.currentTimeMillis());
        Intent data = new Intent();
        data.putExtra(EXTRA_ENROLL_SUCCESS, true);
        data.putExtra(EXTRA_ENROLLED_ID, currentEnrollId);
        setResult(RESULT_OK, data);
        if (FaceAuthSdk.pendingEnrollCallback != null) {
            FaceAuthSdk.pendingEnrollCallback.onResult(
                    new EnrollmentResult(currentEnrollId, 1, 0, EnrollmentResult.Status.SUCCESS, "등록 성공"));
            FaceAuthSdk.pendingEnrollCallback = null;
        }
        logEvent("auth_ui_back_to_list_done", "destination", "MAIN", "timestamp", System.currentTimeMillis());
        finish();
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

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
                scheduleGuideMetricsLog();
                mainHandler.post(EnrollmentActivity.this::autoStartEnrollmentIfReady);
            } catch (Exception e) {
                SafeLogger.e(TAG, "카메라 시작 실패", e);
                deliverFailure("카메라를 시작할 수 없습니다.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void scheduleGuideMetricsLog() {
        mainHandler.removeCallbacks(guideMetricsLogger);
        mainHandler.postDelayed(guideMetricsLogger, GUIDE_METRICS_LOG_INTERVAL_MS);
    }

    private void logGuideMetricsOnce() {
        if (regState == RegState.IDLE && lastSnapshot == null) {
            mainHandler.postDelayed(guideMetricsLogger, GUIDE_METRICS_LOG_INTERVAL_MS);
            return;
        }
        float cx = guideOverlay.getCircleCenterX();
        float cy = guideOverlay.getCircleCenterY();
        float r = guideOverlay.getCircleRadiusPx();
        float ratio = guideOverlay.getGuideRatio();
        boolean landscape = guideOverlay.getIsLandscape();
        boolean tablet = guideOverlay.getIsTablet();
        SafeLogger.i(TAG, String.format(
                "guide metrics: guideRatio=%.2f guideCenterX=%.1f guideCenterY=%.1f guideRadius=%.1f isLandscape=%s isTablet=%s state=%s usedYaw=%.1f bboxAreaRatio=%.3f",
                ratio, cx, cy, r, landscape, tablet,
                regState != null ? regState.name() : "null",
                lastSnapshot != null ? lastSnapshot.usedYaw : 0f,
                lastSnapshot != null ? lastSnapshot.bboxAreaRatio : 0f));
        mainHandler.postDelayed(guideMetricsLogger, GUIDE_METRICS_LOG_INTERVAL_MS);
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (regState == RegState.IDLE || regState == RegState.SUCCESS || regState == RegState.FAILED) {
            imageProxy.close();
            return;
        }
        if (regState == RegState.COMMITTING) {
            imageProxy.close();
            return;
        }

        boolean handedOffToCommit = false;
        try {
            Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) return;
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);
            List<Face> faces = faceDetector.detectSync(inputImage);
            int w = imageProxy.getWidth();
            int h = imageProxy.getHeight();
            EnrollmentFrameSnapshot snapshot = EnrollmentFrameSnapshot.fromFaces(
                    faces, w, h,
                    lastGuideViewW, lastGuideViewH, lastGuideCx, lastGuideCy, lastGuideR,
                    config.guideInnerMarginRatio, config.guideCircleRadiusRatio);

            lastSnapshot = snapshot;
            onFrameUpdate(snapshot);
            if (regState == RegState.COMMITTING && snapshot.selectedFace != null && commitGuard.compareAndSet(false, true)) {
                startCommit(imageProxy, snapshot.selectedFace);
                handedOffToCommit = true;
            }
            mainHandler.post(this::updateUI);

        } catch (Exception e) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("screen", "enrollment");
            ctx.put("state", regState != null ? regState.name() : null);
            if (lastSnapshot != null) {
                ctx.put("trackingId", lastSnapshot.selectedTrackingId);
                ctx.put("facesCount", lastSnapshot.facesCount);
                ctx.put("roiCandidateCount", lastSnapshot.roiCandidateCount);
                ctx.put("bboxAreaRatio", lastSnapshot.bboxAreaRatio);
                ctx.put("usedYaw", lastSnapshot.usedYaw);
            }
            AuthErrorLogger.log(e, "EnrollmentActivity.analyzeFrame", ctx);
            SafeLogger.e(TAG, "프레임 분석 오류", e);
            mainHandler.post(() -> android.widget.Toast.makeText(EnrollmentActivity.this,
                    "오류가 발생했습니다. 다시 시도해 주세요.", android.widget.Toast.LENGTH_LONG).show());
        } finally {
            if (!handedOffToCommit) {
                imageProxy.close();
            }
        }
    }

    private void onFrameUpdate(EnrollmentFrameSnapshot s) {
        if (regState == RegState.ARMED) {
            if (s.roiCandidateCount >= 1 && s.isInsideGuide && s.selectedTrackingId != null
                    && s.bboxAreaRatio >= config.enrollMinFaceAreaRatio
                    && (config.enrollMaxAbsYaw <= 0 || Math.abs(s.usedYaw) <= config.enrollMaxAbsYaw)) {
                stableFrames = 1;
                lastTrackingId = s.selectedTrackingId;
                regState = RegState.CAPTURING;
            } else {
                String reason = reasonBlocked(s);
                logEvent("auth_register_blocked", "reason", reason, "yaw", s.usedYaw, "ratio", s.bboxAreaRatio,
                        "stableFrames", stableFrames, "tid", s.selectedTrackingId != null ? s.selectedTrackingId : "null", "roiCandidateCount", s.roiCandidateCount);
            }
            return;
        }

        if (regState == RegState.CAPTURING) {
            boolean sameId = s.selectedTrackingId != null && s.selectedTrackingId.equals(lastTrackingId);
            boolean ratioOk = s.bboxAreaRatio >= config.enrollMinFaceAreaRatio;
            boolean insideOk = s.isInsideGuide;
            boolean yawOk = config.enrollMaxAbsYaw <= 0 || Math.abs(s.usedYaw) <= config.enrollMaxAbsYaw;

            if (sameId && ratioOk && insideOk && yawOk) {
                stableFrames++;
                logEvent("auth_register_stable_progress", "stableFrames", stableFrames,
                        "required", config.requiredStableFrames);
                if (stableFrames >= config.requiredStableFrames) {
                    regState = RegState.COMMITTING;
                    logEvent("auth_register_captured", "stableFrames", stableFrames);
                }
            } else {
                int prevStable = stableFrames;
                stableFrames = 0;
                lastTrackingId = null;
                regState = RegState.ARMED;
                String reason = reasonUnstable(s, sameId, ratioOk, insideOk, yawOk);
                logEvent("auth_register_blocked", "reason", reason, "yaw", s.usedYaw, "ratio", s.bboxAreaRatio,
                        "stableFrames", prevStable, "tid", s.selectedTrackingId != null ? s.selectedTrackingId : "null", "roiCandidateCount", s.roiCandidateCount);
            }
        }
    }

    private String reasonBlocked(EnrollmentFrameSnapshot s) {
        if (s.roiCandidateCount < 1) return "NO_ROI_CANDIDATE";
        if (!s.isInsideGuide) return "OUTSIDE_GUIDE";
        if (s.selectedTrackingId == null) return "TARGET_UNSTABLE";
        if (s.bboxAreaRatio < config.enrollMinFaceAreaRatio) return "FACE_TOO_SMALL";
        if (config.enrollMaxAbsYaw > 0 && Math.abs(s.usedYaw) > config.enrollMaxAbsYaw) return "YAW_TOO_LARGE";
        return "UNKNOWN";
    }

    private String reasonUnstable(EnrollmentFrameSnapshot s, boolean sameId, boolean ratioOk,
                                  boolean insideOk, boolean yawOk) {
        if (!sameId) return "TARGET_UNSTABLE";
        if (!ratioOk) return "FACE_TOO_SMALL";
        if (!insideOk) return "OUTSIDE_GUIDE";
        if (!yawOk) return "YAW_TOO_LARGE";
        return "UNKNOWN";
    }

    private void startCommit(ImageProxy imageProxy, Face face) {
        final long commitStartTimeMs = System.currentTimeMillis();
        cameraExecutor.execute(() -> {
            Bitmap frame = null;
            try {
                frame = ImageUtils.toBitmapFromYuvNoJpeg(imageProxy);
                if (frame == null) {
                    mainHandler.post(() -> {
                        failedReason = "EXTRACT_FAIL";
                        regState = RegState.FAILED;
                        commitGuard.set(false);
                        updateUI();
                        guideOverlay.update(FaceGuideOverlay.GuideState.FAIL, "등록 실패(이미지 변환 오류)");
                        android.widget.Toast.makeText(EnrollmentActivity.this, "오류: 등록에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                Bitmap aligned = FaceAligner.align(frame, face);
                float[] embedding = faceEmbedder.embed(aligned, commitStartTimeMs);
                aligned.recycle();
                frame.recycle();
                frame = null;

                long id = storageManager.saveProfile(currentEnrollId, "NORMAL", embedding, 1.0f);
                if (id >= 0) {
                    logEvent("auth_register_persisted", "storageKey", String.valueOf(id));
                    mainHandler.post(() -> {
                        regState = RegState.SUCCESS;
                        commitGuard.set(false);
                        logEvent("auth_ui_enroll_success", "id", currentEnrollId, "action", "SHOW_SUCCESS_UI");
                        updateUI();
                        guideOverlay.update(FaceGuideOverlay.GuideState.QUALITY_OK, "등록 성공");
                    });
                } else {
                    failedReason = "PERSIST_FAIL";
                    logEvent("auth_register_failed", "reason", failedReason);
                    mainHandler.post(() -> {
                        regState = RegState.FAILED;
                        commitGuard.set(false);
                        updateUI();
                        guideOverlay.update(FaceGuideOverlay.GuideState.FAIL, "등록 실패(저장 오류)");
                        android.widget.Toast.makeText(EnrollmentActivity.this, "오류: 등록에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            } catch (EmbeddingException e) {
                String code = e.errorCode != null ? e.errorCode : "TFLITE_INFER_FAIL";
                failedReason = code;
                logEvent("auth_register_failed", "reason", code, "details", e.getMessage());
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("screen", "enrollment");
                ctx.put("state", "COMMITTING");
                ctx.put("errorCode", code);
                AuthErrorLogger.log(e, "EnrollmentActivity.startCommit", ctx);
                SafeLogger.e(TAG, "commit 실패: " + code, e);
                if (frame != null) frame.recycle();
                final String failMsg = "등록 실패: TFLite 추론 실패 (" + code + ")";
                mainHandler.post(() -> {
                    regState = RegState.FAILED;
                    commitGuard.set(false);
                    updateUI();
                    guideOverlay.update(FaceGuideOverlay.GuideState.FAIL, failMsg);
                    android.widget.Toast.makeText(EnrollmentActivity.this, "오류: 등록에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("screen", "enrollment");
                ctx.put("state", "COMMITTING");
                AuthErrorLogger.log(e, "EnrollmentActivity.startCommit", ctx);
                SafeLogger.e(TAG, "commit 실패", e);
                failedReason = "EXTRACT_FAIL";
                logEvent("auth_register_failed", "reason", failedReason);
                if (frame != null) frame.recycle();
                final String failMsg = e.getMessage() != null ? e.getMessage() : "등록 실패";
                mainHandler.post(() -> {
                    regState = RegState.FAILED;
                    commitGuard.set(false);
                    updateUI();
                    guideOverlay.update(FaceGuideOverlay.GuideState.FAIL, "등록 실패: " + failMsg);
                    android.widget.Toast.makeText(EnrollmentActivity.this, "오류: 등록에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show();
                });
            } finally {
                imageProxy.close();
            }
        });
    }

    private void logEvent(String event, Object... kvs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"event\":\"").append(event).append("\"");
        for (int i = 0; i < kvs.length - 1; i += 2) {
            sb.append(",\"").append(kvs[i]).append("\":");
            Object v = kvs[i + 1];
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(String.valueOf(v)).append("\"");
        }
        sb.append("}");
        SafeLogger.i(TAG, sb.toString());
    }

    private void updateUI() {
        switch (regState) {
            case IDLE:
                tvStatus.setText("대기");
                break;
            case ARMED:
                tvStatus.setText("진행중 (얼굴을 가이드에 맞춰 주세요)");
                break;
            case CAPTURING:
                tvStatus.setText("진행중 (" + stableFrames + "/" + config.requiredStableFrames + ")");
                break;
            case COMMITTING:
                tvStatus.setText("저장 중...");
                break;
            case SUCCESS:
                tvStatus.setText("성공");
                break;
            case FAILED:
                tvStatus.setText("실패(" + failedReason + ")");
                break;
        }
        if (lastSnapshot != null && regState != RegState.IDLE) {
            tvMetrics.setText(String.format("state=%s\nyaw=%.1f ratio=%.3f\nroi=%d faces=%d\ntid=%s\nstable=%d/%d",
                    regState != null ? regState.name() : "-",
                    lastSnapshot.usedYaw,
                    lastSnapshot.bboxAreaRatio,
                    lastSnapshot.roiCandidateCount,
                    lastSnapshot.facesCount,
                    lastSnapshot.selectedTrackingId != null ? lastSnapshot.selectedTrackingId : "-",
                    stableFrames,
                    config.requiredStableFrames));
        } else {
            tvMetrics.setText("");
        }
        btnReset.setVisibility(regState != RegState.IDLE ? View.VISIBLE : View.GONE);
        btnGoMain.setVisibility(regState == RegState.SUCCESS ? View.VISIBLE : View.GONE);
        // Cache guide geometry for analyzer (main thread)
        lastGuideViewW = guideOverlay.getWidth();
        lastGuideViewH = guideOverlay.getHeight();
        lastGuideCx = guideOverlay.getCircleCenterX();
        lastGuideCy = guideOverlay.getCircleCenterY();
        lastGuideR = guideOverlay.getCircleRadiusPx();
        lastGuideInnerMargin = config.guideInnerMarginRatio;
    }

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

    private void deliverFailure(String message) {
        EnrollmentResult result = new EnrollmentResult(
                userId, 0, 0, EnrollmentResult.Status.FAILED, message);
        mainHandler.post(() -> {
            if (FaceAuthSdk.pendingEnrollCallback != null) {
                FaceAuthSdk.pendingEnrollCallback.onResult(result);
                FaceAuthSdk.pendingEnrollCallback = null;
            }
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(guideMetricsLogger);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(guideMetricsLogger);
        cameraExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
        if (faceEmbedder != null) faceEmbedder.close();
    }
}