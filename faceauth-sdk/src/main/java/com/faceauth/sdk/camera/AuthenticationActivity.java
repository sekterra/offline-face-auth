package com.faceauth.sdk.camera;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import com.faceauth.sdk.api.AuthResult;
import com.faceauth.sdk.api.FailureReason;
import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.detection.FaceAligner;
import com.faceauth.sdk.detection.FaceDetector;
import com.faceauth.sdk.embedding.FaceEmbedder;
import com.faceauth.sdk.logging.AuthErrorLogger;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.matcher.EmbeddingMatcher;
import com.faceauth.sdk.overlay.FaceGuideOverlay;
import com.faceauth.sdk.quality.QualityGate;
import com.faceauth.sdk.storage.ProfileRecord;
import com.faceauth.sdk.storage.StorageManager;
import com.faceauth.sdk.util.EmbeddingDebugUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/**
 * 인증 내장 Activity.
 * SSoT: 얼굴 인식만 수행. 라이브니스는 별도 LivenessActivity.
 * 매칭은 gate_face_ready + gate_stable_ready 후에만 수행 (즉시 가짜 매칭 방지).
 */
public final class AuthenticationActivity extends AppCompatActivity {

    private static final String TAG             = "AuthActivity";
    private static final int    PERM_REQUEST    = 1002;
    private static final long   AUTH_RUN_MAX_MS = 60_000L;  // 인식 대기시간 1분
    private static int          nextAuthRunId   = 0;

    private FaceDetector   faceDetector;
    private FaceEmbedder   faceEmbedder;
    private QualityGate    qualityGate;
    private StorageManager storageManager;
    private FaceAuthConfig config;

    private PreviewView      previewView;
    private FaceGuideOverlay guideOverlay;
    private LinearLayout     panelResult;
    private TextView        tvAuthResult;
    private Button          btnBackToList;
    private View            scrollAuthDebug;
    private TextView        tvAuthDebug;
    private TextView        tvAuthBanner;

    private List<ProfileRecord> candidates;
    /** 현재 런 ID. 매 run 시작 시 갱신, 로그에 사용. */
    private int     currentAuthRunId;
    /** 인증 런 시작 시각 (1분 재시도 창 계산용). */
    private long    runStartTimeMs;
    private boolean authDone;
    private int     stableFrames;
    private Integer lastTrackingId;

    /** 게이트: 이 run에서만 유효. 결과 산출 전 모두 true 여야 함. */
    private volatile boolean gateDbLoadedThisRun;
    private volatile boolean gateEmbeddingComputedThisRun;

    /** Guide geometry cache (main thread에서 갱신, analyzer에서 읽기) */
    private volatile int   lastGuideViewW;
    private volatile int   lastGuideViewH;
    private volatile float lastGuideCx;
    private volatile float lastGuideCy;
    private volatile float lastGuideR;

    /** [DEBUG] 증거 패널용 (analyzer에서 설정, main에서 표시) */
    private volatile int     lastFacesCount;
    private volatile int     lastRoiCandidateCount;
    private volatile float  lastBboxRatio;
    private volatile String lastTrackingIdStr;
    private volatile int    lastStableFrames;
    private volatile boolean lastGateFaceReady;
    private volatile boolean lastGateStableReady;
    private volatile boolean lastGateEmbeddingComputed;
    private volatile boolean lastGateDbLoaded;
    private volatile boolean lastGateComparedAll;
    private volatile int    lastEnrolledCount;
    private volatile int    lastComparedCount;
    private volatile String lastIdsFirst3;
    private volatile String lastBestId;
    private volatile float  lastBestScore;
    private volatile String lastDecisionStr;
    private volatile String lastQueryHash;
    private volatile double lastQueryNorm;
    private volatile String lastQueryFirst5;

    private ExecutorService cameraExecutor;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authentication);

        previewView   = findViewById(R.id.preview_view);
        guideOverlay  = findViewById(R.id.face_guide_overlay);
        panelResult   = findViewById(R.id.panel_result);
        tvAuthResult  = findViewById(R.id.tv_auth_result);
        btnBackToList  = findViewById(R.id.btn_back_to_list);
        scrollAuthDebug = findViewById(R.id.scroll_auth_debug);
        tvAuthDebug    = findViewById(R.id.tv_auth_debug);
        tvAuthBanner   = findViewById(R.id.tv_auth_banner);
        guideOverlay.setConfig(FaceAuthSdk.getConfig());

        boolean debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debugBuild && scrollAuthDebug != null) {
            scrollAuthDebug.setVisibility(View.VISIBLE);
        }

        config         = FaceAuthSdk.getConfig();
        storageManager = FaceAuthSdk.getStorageManager();
        qualityGate    = new QualityGate(config);

        // SSoT: 인증에서는 눈깜빡임(blink) 미사용. 라이브니스는 별도 LivenessActivity에서만.
        SafeLogger.i(TAG, "{\"event\":\"auth_ssot_violation_prevented\",\"feature\":\"BLINK_IN_AUTH\",\"action\":\"FORCED_OFF\"}");

        btnBackToList.setOnClickListener(v -> onBackToListClicked());

        resetAuthRunState();
        currentAuthRunId = ++nextAuthRunId;
        runStartTimeMs = System.currentTimeMillis();
        SafeLogger.i(TAG, String.format("{\"event\":\"auth_run_start\",\"authRunId\":%d,\"timestamp\":%d,\"maxWaitMs\":%d}", currentAuthRunId, runStartTimeMs, AUTH_RUN_MAX_MS));
        // #region agent log
        try {
            JSONObject o = new JSONObject();
            o.put("hypothesisId", "H1");
            o.put("location", "AuthenticationActivity.onCreate");
            o.put("message", "auth_run_start");
            o.put("timestamp", System.currentTimeMillis());
            o.put("data", new JSONObject().put("authRunId", currentAuthRunId).put("runStartTimeMs", runStartTimeMs).put("maxWaitMs", AUTH_RUN_MAX_MS));
            appendDebugLog(o.toString());
        } catch (Exception e) { /* ignore */ }
        // #endregion

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
            gateDbLoadedThisRun = true;
            int count = candidates.size();
            StringBuilder ids = new StringBuilder("[");
            for (int i = 0; i < Math.min(3, count); i++) {
                if (i > 0) ids.append(",");
                ids.append("\"").append(candidates.get(i).userId).append("\"");
            }
            ids.append("]");
            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_db_loaded\",\"authRunId\":%d,\"enrolledCount\":%d,\"idsFirst3\":%s,\"timestamp\":%d}",
                    currentAuthRunId, count, ids.toString(), System.currentTimeMillis()));
            logEnrolledEmbeddingSample(count);
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
                cameraProvider = provider;
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
            android.media.Image mediaImage = proxy.getImage();
            if (mediaImage == null) return;
            int rotation = proxy.getImageInfo().getRotationDegrees();
            InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);
            List<Face> faces = faceDetector.detectSync(inputImage);
            if (faces.isEmpty()) {
                showGuide(FaceGuideOverlay.GuideState.IDLE, "얼굴이 감지되지 않습니다.");
                return;
            }

            int imageW = proxy.getWidth();
            int imageH = proxy.getHeight();
            int viewW = lastGuideViewW;
            int viewH = lastGuideViewH;
            float viewCx = lastGuideCx;
            float viewCy = lastGuideCy;
            float viewR = lastGuideR;
            EnrollmentFrameSnapshot snapshot = EnrollmentFrameSnapshot.fromFaces(
                    faces, imageW, imageH,
                    viewW, viewH, viewCx, viewCy, viewR,
                    config.guideInnerMarginRatio,
                    config.guideCircleRadiusRatio);

            float usedYaw = snapshot.usedYaw;
            boolean gateFaceReady = snapshot.roiCandidateCount >= 1 && snapshot.isInsideGuide
                    && snapshot.bboxAreaRatio >= config.authMinFaceAreaRatio;
            boolean gateYawOk = Math.abs(usedYaw) <= config.authYawMaxDeg;
            boolean gateStableReady = stableFrames >= config.authStableFramesRequired;
            boolean gateDbLoaded = gateDbLoadedThisRun && candidates != null && !candidates.isEmpty();
            Integer tid = snapshot.selectedTrackingId;
            if (tid != null && tid.equals(lastTrackingId)) {
                stableFrames++;
            } else {
                stableFrames = (snapshot.selectedFace != null ? 1 : 0);
                lastTrackingId = tid;
            }
            gateStableReady = stableFrames >= config.authStableFramesRequired;

            lastFacesCount = faces.size();
            lastRoiCandidateCount = snapshot.roiCandidateCount;
            lastBboxRatio = snapshot.bboxAreaRatio;
            lastTrackingIdStr = tid != null ? String.valueOf(tid) : "null";
            lastStableFrames = stableFrames;
            lastGateFaceReady = gateFaceReady;
            lastGateStableReady = gateStableReady;
            lastGateEmbeddingComputed = gateEmbeddingComputedThisRun;
            lastGateDbLoaded = gateDbLoaded;

            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_gate_status\",\"authRunId\":%d,\"gate_face_ready\":%s,\"gate_stable_ready\":%s,\"gate_embedding_computed\":%s,\"gate_db_loaded\":%s,\"gate_compared_all\":false,\"stableFrames\":%d,\"requiredFrames\":%d,\"tid\":%s,\"roiCandidateCount\":%d,\"bboxRatio\":%.4f}",
                    currentAuthRunId, gateFaceReady, gateStableReady, gateEmbeddingComputedThisRun, gateDbLoaded,
                    stableFrames, config.authStableFramesRequired, tid != null ? tid : "null", snapshot.roiCandidateCount, snapshot.bboxAreaRatio));

            if (snapshot.roiCandidateCount == 0) {
                showGuide(FaceGuideOverlay.GuideState.IDLE, "얼굴이 감지되지 않습니다.");
                return;
            }
            if (!gateFaceReady) {
                showGuide(FaceGuideOverlay.GuideState.FAIL, "얼굴을 원 안에 맞춰주세요.");
                return;
            }
            if (!gateYawOk) {
                showGuide(FaceGuideOverlay.GuideState.FAIL, "정면으로");
                return;
            }
            if (!gateStableReady) {
                showGuide(FaceGuideOverlay.GuideState.QUALITY_OK, "얼굴을 인식 중...");
                return;
            }

            Bitmap frame = ImageUtils.toBitmapFromYuvNoJpeg(proxy);
            if (frame == null) return;
            Face face = snapshot.selectedFace;
            QualityGate.Result qr = qualityGate.check(frame, face, frame.getWidth(), frame.getHeight());
            if (!qr.passed) {
                showGuide(FaceGuideOverlay.GuideState.FAIL, qr.guideMessage);
                frame.recycle();
                return;
            }

            showGuide(FaceGuideOverlay.GuideState.QUALITY_OK, "얼굴을 인식 중...");

            int enrolledCount = candidates.size();
            float threshold = config.matchThreshold;
            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_match_attempt\",\"authRunId\":%d,\"yaw\":%.2f,\"ratio\":%.4f,\"tid\":%s,\"enrolledCount\":%d,\"threshold\":%.4f}",
                    currentAuthRunId, usedYaw, snapshot.bboxAreaRatio, tid != null ? tid : "null", enrolledCount, threshold));

            Bitmap aligned  = FaceAligner.align(frame, face);
            float[] liveEmb = faceEmbedder.embed(aligned);
            aligned.recycle();
            frame.recycle();

            double norm = 0;
            for (float v : liveEmb) norm += (double) v * v;
            norm = Math.sqrt(norm);
            double hashSum = 0;
            for (int i = 0; i < Math.min(16, liveEmb.length); i++) hashSum += liveEmb[i];
            gateEmbeddingComputedThisRun = true;
            lastGateEmbeddingComputed = true;
            lastQueryHash = String.format("%.4f", hashSum);
            lastQueryNorm = norm;
            lastQueryFirst5 = EmbeddingDebugUtils.first5(liveEmb, 3);
            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_embedding_computed\",\"authRunId\":%d,\"dim\":%d,\"embeddingHash\":%.4f,\"norm\":%.4f}",
                    currentAuthRunId, liveEmb.length, hashSum, norm));

            EmbeddingMatcher.MatchResult match =
                    EmbeddingMatcher.findTopMatchWithLogging(liveEmb, candidates, config.matchThreshold, TAG);

            enrolledCount = candidates.size();
            String bestId = match.bestProfile != null ? match.bestProfile.userId : null;
            float bestScore = match.matchScore;
            threshold = config.matchThreshold;
            boolean decisionMatch = match.matchScore >= threshold && match.bestProfile != null;
            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_match_result\",\"authRunId\":%d,\"bestId\":\"%s\",\"bestScore\":%.4f,\"threshold\":%.4f,\"decision\":\"%s\"}",
                    currentAuthRunId, bestId != null ? bestId : "", bestScore, threshold, decisionMatch ? "MATCH" : "NO_MATCH"));
            lastGateComparedAll = true;
            lastEnrolledCount = enrolledCount;
            lastComparedCount = enrolledCount;
            StringBuilder ids3 = new StringBuilder("[");
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                if (i > 0) ids3.append(",");
                ids3.append(candidates.get(i).userId);
            }
            ids3.append("]");
            lastIdsFirst3 = ids3.toString();
            lastBestId = bestId;
            lastBestScore = bestScore;
            lastDecisionStr = decisionMatch ? "MATCH" : "NO_MATCH";

            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_compare_done\",\"authRunId\":%d,\"enrolledCount\":%d,\"comparedCount\":%d,\"bestId\":\"%s\",\"bestScore\":%.4f,\"threshold\":%.4f,\"decision\":\"%s\"}",
                    currentAuthRunId, enrolledCount, enrolledCount, bestId != null ? bestId : "", bestScore, threshold, decisionMatch ? "MATCH" : "NO_MATCH"));

            String auditResult = decisionMatch ? "SUCCESS" : "FAIL_MATCH";
            storageManager.saveAudit(
                    auditResult, bestId, match.matchScore,
                    config.pocMode ? "{score:" + match.matchScore + "}" : null);

            if (decisionMatch) {
                authDone = true;
                deliverResult(AuthResult.success(match.bestProfile.userId, match.matchScore));
            } else {
                long elapsedMs = System.currentTimeMillis() - runStartTimeMs;
                if (elapsedMs < AUTH_RUN_MAX_MS) {
                    // 1분 미만: 재시도 (안정 프레임 리셋 후 다시 시도)
                    stableFrames = 0;
                    lastTrackingId = null;
                    gateEmbeddingComputedThisRun = false;
                    SafeLogger.i(TAG, String.format("{\"event\":\"auth_no_match_retry\",\"authRunId\":%d,\"elapsedMs\":%d,\"maxMs\":%d,\"bestScore\":%.4f,\"threshold\":%.4f}",
                            currentAuthRunId, elapsedMs, AUTH_RUN_MAX_MS, bestScore, threshold));
                    // #region agent log
                    try {
                        JSONObject o = new JSONObject();
                        o.put("hypothesisId", "H2");
                        o.put("location", "AuthenticationActivity.analyzeFrame NO_MATCH");
                        o.put("message", "auth_no_match_retry");
                        o.put("timestamp", System.currentTimeMillis());
                        o.put("data", new JSONObject().put("authRunId", currentAuthRunId).put("elapsedMs", elapsedMs).put("maxMs", AUTH_RUN_MAX_MS).put("bestScore", bestScore).put("threshold", threshold).put("willRetry", true));
                        appendDebugLog(o.toString());
                    } catch (Exception e) { /* ignore */ }
                    // #endregion
                    return;
                }
                authDone = true;
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_MATCH, match.matchScore,
                        "얼굴 인식에 실패했습니다. 다른 방법으로 로그인해 주세요."));
            }

        } catch (Exception e) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("screen", "authentication");
            AuthErrorLogger.log(e, "AuthenticationActivity.analyzeFrame", ctx);
            SafeLogger.e(TAG, "인증 프레임 처리 오류", e);
            SafeLogger.i(TAG, String.format(
                    "{\"event\":\"auth_fail_internal\",\"authRunId\":%d,\"where\":\"analyzeFrame\",\"exceptionMessage\":\"%s\"}",
                    currentAuthRunId, e.getMessage() != null ? e.getMessage().replace("\"", "'") : ""));
            if (!authDone) {
                authDone = true;
                deliverResult(AuthResult.failure(
                        FailureReason.FAIL_INTERNAL, 0f, "인증 실패"));
            }
        } finally {
            proxy.close();
        }
    }

    private void showGuide(FaceGuideOverlay.GuideState state, String msg) {
        mainHandler.post(() -> {
            guideOverlay.update(state, msg);
            int w = guideOverlay.getWidth();
            int h = guideOverlay.getHeight();
            if (w > 0 && h > 0) {
                lastGuideViewW = w;
                lastGuideViewH = h;
                lastGuideCx = guideOverlay.getCircleCenterX();
                lastGuideCy = guideOverlay.getCircleCenterY();
                lastGuideR = guideOverlay.getCircleRadiusPx();
            }
            updateAuthDebugPanel();
        });
    }

    private static void appendDebugLog(String ndjsonLine) {
        String path = "c:\\_project\\face\\faceauth-sdk-fixed\\.cursor\\debug.log";
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write((ndjsonLine + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            SafeLogger.d(TAG, "debugLog write skip: " + t.getMessage());
        }
    }

    private void resetAuthRunState() {
        authDone = false;
        stableFrames = 0;
        lastTrackingId = null;
        gateDbLoadedThisRun = false;
        gateEmbeddingComputedThisRun = false;
    }

    private void logEnrolledEmbeddingSample(int count) {
        if (candidates == null || count < 1) return;
        int limit = Math.min(2, count);
        for (int i = 0; i < limit; i++) {
            ProfileRecord p = candidates.get(i);
            if (p == null || p.embedding == null) continue;
            float[] emb = p.embedding;
            int dim = emb.length;
            double norm = 0;
            for (float v : emb) norm += (double) v * v;
            norm = Math.sqrt(norm);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"event\":\"auth_enrolled_embedding_sample\",\"authRunId\":").append(currentAuthRunId)
                    .append(",\"id\":\"").append(p.userId).append("\",\"embeddingDim\":").append(dim)
                    .append(",\"first5\":[");
            for (int j = 0; j < Math.min(5, dim); j++) {
                if (j > 0) sb.append(",");
                sb.append(String.format("%.4f", emb[j]));
            }
            sb.append("],\"norm\":").append(String.format("%.4f", norm)).append("}");
            SafeLogger.i(TAG, sb.toString());
        }
    }

    private void deliverResult(AuthResult result) {
        mainHandler.post(() -> {
            lastDeliveredResult = result;
            boolean matched = result.status == AuthResult.Status.SUCCESS && result.matchedUserId != null;
            guideOverlay.update(matched ? FaceGuideOverlay.GuideState.QUALITY_OK : FaceGuideOverlay.GuideState.FAIL,
                    matched ? "인식 완료" : "인식 실패");
            if (matched) {
                tvAuthResult.setText("인식된 ID: " + result.matchedUserId
                        + "\n점수: " + String.format("%.3f", result.matchScore)
                        + "\n기준(threshold): " + String.format("%.2f", config.matchThreshold));
            } else {
                tvAuthResult.setText("인식 실패\n" + (result.message != null ? result.message : "")
                        + "\n기준(threshold): " + String.format("%.2f", config.matchThreshold));
                android.widget.Toast.makeText(AuthenticationActivity.this,
                        "오류가 발생했습니다.", android.widget.Toast.LENGTH_LONG).show();
            }
            panelResult.setVisibility(View.VISIBLE);
            btnBackToList.setVisibility(View.VISIBLE);
            updateAuthDebugPanel();
            updateAuthBanners();
        });
    }

    private void updateAuthDebugPanel() {
        if (tvAuthDebug == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("authRunId=").append(currentAuthRunId);
        sb.append("\nroiCandidateCount=").append(lastRoiCandidateCount);
        sb.append(" facesCount=").append(lastFacesCount);
        sb.append("\nbboxRatio=").append(String.format("%.4f", lastBboxRatio));
        sb.append(" tid=").append(lastTrackingIdStr);
        sb.append("\nstableFrames=").append(lastStableFrames).append("/").append(config.authStableFramesRequired);
        sb.append("\ngate_face_ready=").append(lastGateFaceReady);
        sb.append(" gate_stable_ready=").append(lastGateStableReady);
        sb.append("\ngate_embedding_computed=").append(lastGateEmbeddingComputed);
        sb.append(" gate_db_loaded=").append(lastGateDbLoaded);
        sb.append(" gate_compared_all=").append(lastGateComparedAll);
        sb.append("\nenrolledCount=").append(lastEnrolledCount);
        sb.append(" comparedCount=").append(lastComparedCount);
        sb.append("\nidsFirst3=").append(lastIdsFirst3 != null ? lastIdsFirst3 : "-");
        sb.append("\nbestId=").append(lastBestId != null ? lastBestId : "-");
        sb.append(" bestScore=").append(String.format("%.4f", lastBestScore));
        sb.append("\ndecision=").append(lastDecisionStr != null ? lastDecisionStr : "-");
        sb.append("\nqueryHash=").append(lastQueryHash != null ? lastQueryHash : "-");
        sb.append(" queryNorm=").append(String.format("%.4f", lastQueryNorm));
        sb.append("\nqueryFirst5=").append(lastQueryFirst5 != null ? lastQueryFirst5 : "-");
        tvAuthDebug.setText(sb.toString());
    }

    private void updateAuthBanners() {
        if (tvAuthBanner == null) return;
        StringBuilder banners = new StringBuilder();
        if (lastEnrolledCount < 2) {
            banners.append("DB LOADED <2 USERS\n");
        }
        if (candidates != null && candidates.size() >= 2) {
            java.util.Set<String> hashes = new java.util.HashSet<>();
            for (ProfileRecord p : candidates) {
                if (p.embedding != null) {
                    String h = EmbeddingDebugUtils.embeddingHash(p.embedding);
                    if (hashes.contains(h)) {
                        banners.append("EMBEDDINGS IDENTICAL\n");
                        break;
                    }
                    hashes.add(h);
                }
            }
        }
        if (lastComparedCount != lastEnrolledCount && lastEnrolledCount > 0) {
            banners.append("COMPARE LOOP INCOMPLETE\n");
        }
        if (lastBestScore == 1.0f && lastBestId != null) {
            banners.append("SCORE CONSTANT\n");
        }
        if (banners.length() > 0) {
            tvAuthBanner.setVisibility(View.VISIBLE);
            tvAuthBanner.setText(banners.toString().trim());
        } else {
            tvAuthBanner.setVisibility(View.GONE);
        }
    }

    private ProcessCameraProvider cameraProvider;

    private void onBackToListClicked() {
        SafeLogger.i(TAG, String.format("{\"event\":\"ui_nav_back_clicked\",\"screen\":\"AUTH\",\"authRunId\":%d}", currentAuthRunId));
        SafeLogger.i(TAG, String.format("{\"event\":\"auth_run_stop\",\"authRunId\":%d,\"reason\":\"STOP_BUTTON\"}", currentAuthRunId));
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                SafeLogger.d(TAG, "unbindAll: " + e.getMessage());
            }
            cameraProvider = null;
        }
        if (FaceAuthSdk.pendingAuthCallback != null) {
            AuthResult result = lastDeliveredResult;
            if (result != null) {
                FaceAuthSdk.pendingAuthCallback.onResult(result);
            }
            FaceAuthSdk.pendingAuthCallback = null;
        }
        SafeLogger.i(TAG, "{\"event\":\"auth_ui_back_to_list_done\",\"destination\":\"MAIN\",\"timestamp\":" + System.currentTimeMillis() + "}");
        finish();
    }

    private AuthResult lastDeliveredResult;

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
    protected void onPause() {
        super.onPause();
        SafeLogger.i(TAG, String.format("{\"event\":\"auth_run_stop\",\"authRunId\":%d,\"reason\":\"ON_PAUSE\"}", currentAuthRunId));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception e) { SafeLogger.d(TAG, "unbindAll: " + e.getMessage()); }
            cameraProvider = null;
        }
        cameraExecutor.shutdown();
        try {
            if (!cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)) cameraExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cameraExecutor.shutdownNow();
        }
        if (faceDetector != null) faceDetector.close();
        if (faceEmbedder != null) faceEmbedder.close();
    }
}
