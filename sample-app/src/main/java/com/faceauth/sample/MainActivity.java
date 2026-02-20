package com.faceauth.sample;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.faceauth.sdk.api.AuthOptions;
import com.faceauth.sdk.api.EnrollmentOptions;
import com.faceauth.sdk.api.FaceAuthSdk;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * 샘플 앱 메인 화면.
 * SDK 공개 API만 사용하여 등록/인증/사용자 목록/삭제 기능 시연.
 */
public class MainActivity extends AppCompatActivity {

    private EditText   etUserId;
    private TextView   tvResult;
    private ListView   lvUsers;
    private ArrayAdapter<String> usersAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUserId     = findViewById(R.id.et_user_id);
        tvResult     = findViewById(R.id.tv_result);
        lvUsers      = findViewById(R.id.lv_users);
        usersAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lvUsers.setAdapter(usersAdapter);

        // ── 등록 버튼 ──────────────────────────────────────────────────
        MaterialButton btnEnroll = findViewById(R.id.btn_enroll);
        btnEnroll.setOnClickListener(v -> {
            String userId = etUserId.getText().toString().trim();
            if (userId.isEmpty()) {
                Toast.makeText(this, "사용자 ID를 입력하세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            tvResult.setText("등록 진행 중...");

            FaceAuthSdk.startEnrollment(
                    this,
                    userId,
                    EnrollmentOptions.defaults(),
                    result -> {
                        // 항상 Main Thread
                        tvResult.setText(
                                "등록 결과: " + result.status
                                        + "\n일반: " + result.normalCount + "/3"
                                        + "\n안전모: " + result.helmetCount + "/3"
                                        + "\n메시지: " + result.message);
                        refreshUserList();
                    }
            );
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

        // ── 사용자 목록 새로고침 버튼 ──────────────────────────────────
        MaterialButton btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(v -> refreshUserList());

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

        // 초기 사용자 목록 로딩
        refreshUserList();
    }

    private void refreshUserList() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<String> users = FaceAuthSdk.listEnrolledUsers();
            runOnUiThread(() -> {
                usersAdapter.clear();
                usersAdapter.addAll(users);
                if (users.isEmpty()) {
                    tvResult.setText("등록된 사용자가 없습니다.");
                }
            });
        });
    }
}
