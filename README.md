# Offline FaceAuth SDK — 프로젝트 README

> **오프라인 안면인식 자동 로그인 Android Library (AAR)**  
> Android 15 / Galaxy Tab 6~10 / Java 21 / Android Studio

---

## 📁 프로젝트 구조

```
OfflineFaceAuth/
├── faceauth-sdk/                    ← AAR 배포 라이브러리 모듈
│   └── src/main/java/com/faceauth/sdk/
│       ├── api/                     ← Public Facade + 데이터 모델
│       │   ├── FaceAuthSdk.java     ← 진입점 (initialize/enroll/auth)
│       │   ├── FaceAuthConfig.java  ← 설정 Builder
│       │   ├── EnrollmentResult.java
│       │   ├── AuthResult.java
│       │   ├── FailureReason.java
│       │   ├── EnrollmentOptions.java
│       │   ├── AuthOptions.java
│       │   └── Callbacks.java
│       ├── camera/
│       │   ├── EnrollmentActivity.java      ← 등록 화면
│       │   ├── AuthenticationActivity.java  ← 인증 화면
│       │   └── ImageUtils.java
│       ├── overlay/
│       │   └── FaceGuideOverlay.java        ← 실루엣 가이드 UI
│       ├── detection/
│       │   ├── FaceDetector.java            ← ML Kit 얼굴 검출
│       │   ├── FaceAligner.java             ← 정렬/크롭
│       │   └── DetectionException.java
│       ├── embedding/
│       │   ├── FaceEmbedder.java            ← TFLite 임베딩
│       │   └── EmbeddingException.java
│       ├── matcher/
│       │   └── EmbeddingMatcher.java        ← Cosine similarity, Top-1
│       ├── liveness/
│       │   └── LivenessChecker.java         ← EAR 눈깜빡임
│       ├── quality/
│       │   └── QualityGate.java             ← 품질 게이트 검증
│       ├── storage/
│       │   ├── FaceAuthDatabase.java        ← SQLite 스키마
│       │   ├── StorageManager.java          ← R/W 관리자
│       │   ├── EmbeddingCrypto.java         ← AES-GCM + Keystore
│       │   ├── ProfileRecord.java
│       │   └── CryptoException.java
│       └── logging/
│           ├── SafeLogger.java              ← PII-safe 로거 (Logcat + FileLogger 전달)
│           └── FileLogger.java              ← 런타임 파일 로그 (회전·버퍼)
│
└── sample-app/                      ← 통합 데모 앱
    └── src/main/java/com/faceauth/sample/
        ├── SampleApplication.java   ← SDK 초기화
        └── MainActivity.java        ← 등록/인증 데모 화면
```

---

## 🚀 빠른 시작

### 1. SDK 초기화 (Application.onCreate)

```java
// face_embedder 기본값이 builder()에 내장되어 있어 최소 설정으로 동작
FaceAuthConfig config = FaceAuthConfig.builder()
    .matchThreshold(0.80f)              // SSoT 고정값 — 변경 금지
    .tfliteModelAsset("face_embedder.tflite")
    .inputNormalization(127.5f, 128.0f) // (pixel - 127.5) / 128.0
    .embeddingDim(192)
    .modelOutputIsNormalized(true)     // face_embedder는 L2 정규화 출력
    .modelVersion("face_embedder-v1.0")
    .pocMode(BuildConfig.DEBUG)
    .build();

FaceAuthSdk.initialize(this, config);
```

### 2. 얼굴 등록

```java
FaceAuthSdk.startEnrollment(
    activity,
    "user01",
    EnrollmentOptions.defaults(),   // NORMAL×3, HELMET×3
    result -> {
        if (result.status == EnrollmentResult.Status.SUCCESS) {
            // 등록 완료
        }
    }
);
```

### 3. 인증

```java
FaceAuthSdk.startAuthentication(
    activity,
    new AuthOptions(),
    result -> {
        if (result.status == AuthResult.Status.SUCCESS) {
            String userId = result.matchedUserId;
            float  score  = result.matchScore;
            // 자동 로그인 처리
        } else {
            FailureReason reason = result.failureReason;
            // FAIL_QUALITY / FAIL_LIVENESS / FAIL_MATCH / FAIL_CAMERA / FAIL_INTERNAL
        }
    }
);
```

---

## ⚙️ 필수 설정

### TFLite 모델 배치 — face_embedder

```
faceauth-sdk/src/main/assets/face_embedder.tflite
```

#### 모델 파일

`face_embedder.tflite`를 `faceauth-sdk/src/main/assets/`에 배치.

#### face_embedder 스펙 (SDK에 이미 반영됨)

| 항목 | 값 |
|------|----|
| 입력 크기 | 112 × 112 |
| 입력 정규화 | `(pixel - 127.5) / 128.0` → `[-1, 1]` |
| 출력 차원 | 192-d |
| 출력 상태 | **이미 L2 정규화 완료** (추가 정규화 불필요) |
| 모델 크기 | ~5.2 MB |

### ProGuard 설정

```proguard
# faceauth-sdk consumer-rules.pro에 포함됨
-keep class com.faceauth.sdk.api.** { *; }
```

---

## 🔒 보안 원칙 (SSoT §6.2)

| 항목 | 구현 |
|------|------|
| 임베딩 암호화 | Android Keystore + AES-256-GCM |
| 이미지 파일 저장 | 금지 (메모리 내 처리 후 즉시 recycle()) |
| PII 로그 출력 | SafeLogger 필터링으로 차단 |
| 이미지 캐시 | 없음 (ImageProxy.close() 즉시 호출) |

---

## 📊 매칭 점수 정의 (SSoT §3, 고정 계약)

```
cosine_sim  = cos(embedding_live, embedding_saved)  ∈ [-1, 1]
match_score = (cosine_sim + 1) / 2                  ∈ [0,  1]
통과 조건   = match_score >= 0.80
```

---

## 🧪 테스트 실행

```bash
# 단위 테스트
./gradlew :faceauth-sdk:test

# Instrumented 테스트 (실기기 필요)
./gradlew :faceauth-sdk:connectedAndroidTest
```

---

## 📋 POC 체크리스트

- [ ] TFLite 모델 파일 배치 (`face_embedder.tflite`)
- [ ] 정상 사용자 인식률 >= 80% 측정
- [ ] FAR(False Accept Rate) 측정 및 합의
- [ ] 갤럭시탭 6~10 기기별 성능 측정 (목표: <= 800ms)
- [ ] Positive/Negative 분포 리포트 생성
- [ ] 품질 게이트 임계값 튜닝 (`FaceAuthConfig.Builder`)
- [ ] 운영 모드 전환 (pocMode=false)
