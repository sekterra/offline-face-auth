package com.faceauth.sample;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import androidx.core.content.FileProvider;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.faceauth.sdk.api.AuthOptions;
import com.faceauth.sdk.api.EnrollmentOptions;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.faceauth.sdk.camera.EnrollmentActivity;
import com.faceauth.sdk.logging.SafeLogger;
import com.faceauth.sdk.storage.EnrolledUserDebugRow;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 샘플 앱 메인 화면.
 * SDK 공개 API만 사용하여 등록/인증/사용자 목록/삭제 기능 시연.
 */
public class MainActivity extends AppCompatActivity {

    private EditText   etUserId;
    private TextView   tvResult;
    private TextView   tvDebugEnrolled;
    private ListView   lvUsers;
    private ArrayAdapter<String> usersAdapter;

    private final ActivityResultLauncher<Intent> enrollmentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                if (data.getBooleanExtra(EnrollmentActivity.EXTRA_ENROLL_SUCCESS, false)) {
                    refreshUserList("RESULT");
                }
                if (FaceAuthSdk.pendingEnrollCallback != null) {
                    // 콜백은 EnrollmentActivity에서 이미 호출됨; 여기서는 목록만 갱신
                    FaceAuthSdk.pendingEnrollCallback = null;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUserId       = findViewById(R.id.et_user_id);
        tvResult       = findViewById(R.id.tv_result);
        tvDebugEnrolled = findViewById(R.id.tv_debug_enrolled);
        lvUsers        = findViewById(R.id.lv_users);
        usersAdapter   = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lvUsers.setAdapter(usersAdapter);

        if (BuildConfig.DEBUG && tvDebugEnrolled != null) {
            tvDebugEnrolled.setVisibility(View.VISIBLE);
        }

        // ── 등록 버튼 ──────────────────────────────────────────────────
        MaterialButton btnEnroll = findViewById(R.id.btn_enroll);
        btnEnroll.setOnClickListener(v -> {
            String userId = etUserId.getText().toString().trim();
            boolean idProvided = !userId.isEmpty();
            SafeLogger.i("FaceAuth", String.format(
                    "{\"event\":\"auth_register_ui_start\",\"idProvided\":%s,\"idLength\":%d,\"timestamp\":%d}",
                    idProvided, idProvided ? userId.length() : 0, System.currentTimeMillis()));
            if (!idProvided) {
                Toast.makeText(this, "사용자 ID를 입력하세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            tvResult.setText("등록 진행 중...");

            FaceAuthSdk.pendingEnrollCallback = result -> {
                tvResult.setText(
                        "등록 결과: " + result.status
                                + "\n일반: " + result.normalCount + "/3"
                                + "\n안전모: " + result.helmetCount + "/3"
                                + "\n메시지: " + result.message);
            };
            Intent intent = FaceAuthSdk.getEnrollmentIntent(this, userId, EnrollmentOptions.defaults());
            enrollmentLauncher.launch(intent);
        });

        // ── 인증 버튼 ──────────────────────────────────────────────────
        MaterialButton btnAuth = findViewById(R.id.btn_auth);
        btnAuth.setOnClickListener(v -> {
            tvResult.setText("인증 진행 중...");

            FaceAuthSdk.startAuthentication(
                    this,
                    new AuthOptions(),
                    result -> {
                        String text;
                        if (result.status == com.faceauth.sdk.api.AuthResult.Status.SUCCESS) {
                            text = "✅ 인증 성공!\n사용자: " + result.matchedUserId
                                    + "\n점수: " + String.format("%.3f", result.matchScore);
                        } else {
                            text = "❌ 인증 실패\n원인: " + result.failureReason
                                    + "\n메시지: " + result.message;
                        }
                        tvResult.setText(text);
                    }
            );
        });

        // ── 라이브니스 검증 버튼 (머리 돌리기 전용) ─────────────────────
        MaterialButton btnLiveness = findViewById(R.id.btn_liveness);
        btnLiveness.setOnClickListener(v -> FaceAuthSdk.startLiveness(this));

        // ── 사용자 목록 새로고침 버튼 ──────────────────────────────────
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(v -> refreshUserList(null));

        // ── DB 데이터 다운로드 버튼 ─────────────────────────────────────
        MaterialButton btnExportDb = findViewById(R.id.btn_export_db);
        btnExportDb.setOnClickListener(v -> exportDbToFile());

        // ── 전체 초기화 버튼 ───────────────────────────────────────────
        MaterialButton btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                FaceAuthSdk.resetAll();
                runOnUiThread(() -> {
                    usersAdapter.clear();
                    tvResult.setText("전체 데이터가 초기화되었습니다.");
                });
            });
        });

        // 초기 및 재개 시 목록은 onResume에서 로딩
    }

    private void exportDbToFile() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String text = FaceAuthSdk.getEnrolledDataExportText();
                File dir = new File(getFilesDir(), "export");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "faceauth_export.txt");
                try (FileOutputStream os = new FileOutputStream(file)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "DB 데이터 저장/열기"));
                    Toast.makeText(this, "파일 생성: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUserList("ON_RESUME");
    }

    /**
     * @param logSource null = 로그 없음, "RESULT" = 등록 결과 수신 후, "ON_RESUME" = 화면 재개 시
     */
    private void refreshUserList(String logSource) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<String> users = FaceAuthSdk.listEnrolledUsers();
            runOnUiThread(() -> {
                usersAdapter.clear();
                usersAdapter.addAll(users);
                if (users.isEmpty()) {
                    tvResult.setText("등록된 사용자가 없습니다.");
                }
                if (logSource != null) {
                    SafeLogger.i("FaceAuth",
                            "{\"event\":\"auth_ui_main_list_reload\",\"source\":\"" + logSource + "\",\"count\":" + usersAdapter.getCount() + "}");
                }
            });
        });
        if (BuildConfig.DEBUG && tvDebugEnrolled != null) {
            FaceAuthSdk.getEnrolledUserDebugRows(5, this::updateEnrolledDebugPanel);
        }
    }

    private void updateEnrolledDebugPanel(List<EnrolledUserDebugRow> rows) {
        if (tvDebugEnrolled == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[DB 증거] enrolledUserCount=").append(rows.size());
        sb.append("\nenrolledIds=");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(rows.get(i).id);
        }
        for (int i = 0; i < rows.size(); i++) {
            EnrolledUserDebugRow r = rows.get(i);
            sb.append("\n--- ").append(r.id).append(" ---");
            sb.append("\n  embeddingCount=").append(r.embeddingCount);
            sb.append(" dim=").append(r.embeddingDim);
            sb.append(" norm=").append(String.format("%.4f", r.norm));
            sb.append("\n  hash=").append(r.embeddingHash);
            sb.append(" first5=").append(r.first5);
        }
        tvDebugEnrolled.setText(sb.toString());
    }
}
