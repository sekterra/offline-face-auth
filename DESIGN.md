# Offline FaceAuth SDK — 설계 문서 v1.0

> **SSoT 기반 / Offline / Android 15 / Galaxy Tab 6~10 / Java 21 / AAR**

---

## 1. 모듈 경계 (Module Boundaries)

```
root/
├── faceauth-sdk/          ← Android Library → 배포 AAR
│   └── src/main/java/com/faceauth/sdk/
│       ├── api/           ← Public Facade + Data Models (외부 노출 유일 진입점)
│       ├── camera/        ← CameraX 파이프라인, 프레임 캡처
│       ├── overlay/       ← 실루엣 가이드 UI (View / Fragment)
│       ├── detection/     ← 얼굴 검출 + 랜드마크 (MLKit Face Detection)
│       ├── embedding/     ← TFLite Embedder, 벡터 추출
│       ├── matcher/       ← Cosine Similarity, Top-1 식별
│       ├── liveness/      ← EAR 기반 눈깜빡임 챌린지
│       ├── storage/       ← SQLite + Android Keystore AES-GCM 암호화
│       ├── quality/       ← Quality Gate 검증 로직
│       └── logging/       ← PII-Safe 내부 로거
│
└── sample-app/            ← 통합 데모 앱 (독립 모듈)
    └── src/main/java/com/faceauth/sample/
        ├── EnrollActivity.kt
        └── AuthActivity.kt
```

### 모듈 간 의존 규칙

```
api → camera, detection, embedding, matcher, liveness, storage, quality, overlay
각 내부 모듈은 api를 역참조(역방향 의존) 금지
sample-app → api (공개 API만 사용, 내부 패키지 직접 참조 금지)
```

---

## 2. 데이터 흐름

### 2.1 등록 (Enroll) 흐름

```
[사용자] 등록 시작
    │
    ▼
FaceAuthSdk.startEnrollment(activity, userId, options, callback)
    │
    ▼
EnrollmentActivity (내장 Activity, SDK가 관리)
    │
    ├─ CameraX Preview 시작
    ├─ FaceGuideOverlay 표시 ("정면으로 맞춰주세요")
    │
    ▼ [프레임 루프 — 캡처 대상: NORMAL×3, HELMET×3]
    │
ImageAnalysis.Analyzer (CameraX)
    │
    ├─ [1] FaceDetector.detect(frame)
    │       └─ face_detected? → NO → 품질 실패, 재시도 안내
    │
    ├─ [2] QualityGate.check(frame, landmarks)
    │       ├─ bbox_area_ratio >= 0.08?
    │       ├─ yaw_abs <= 15°, pitch_abs <= 15°?
    │       ├─ blur_score >= threshold?
    │       └─ brightness in [min, max]?
    │           FAIL → UI 안내 메시지 표시, 프레임 버림
    │
    ├─ [3] FaceEmbedder.embed(alignedCrop)
    │       └─ float[] embedding (192-d or 512-d)
    │
    ├─ [4] StorageManager.saveProfile(userId, profileType, embedding, qualityScore)
    │       └─ AES-GCM 암호화 → SQLite face_profile 삽입
    │
    └─ [5] 6장 완료 → EnrollmentResult(NORMAL=3, HELMET=3, SUCCESS) callback
```

### 2.2 인증 (Auth) 흐름

```
[사용자] 앱 로그인 시도
    │
    ▼
FaceAuthSdk.startAuthentication(activity, options, callback)
    │
    ▼
AuthActivity (내장 Activity, SDK가 관리)
    │
    ├─ CameraX Preview 시작
    ├─ FaceGuideOverlay + 챌린지 UI ("눈을 깜빡여 주세요")
    │
    ▼ [실시간 프레임 분석]
    │
    ├─ [1] FaceDetector.detect(frame)
    ├─ [2] QualityGate.check() → FAIL_QUALITY
    │
    ├─ [3] LivenessChecker (병렬/순차)
    │       ├─ EAR(Eye Aspect Ratio) 계산
    │       ├─ 3초 내 blink 2회 카운트
    │       └─ FAIL → 재시도(최대 2회) → FAIL_LIVENESS
    │
    ├─ [4] FaceEmbedder.embed(alignedCrop) → live_embedding
    │
    ├─ [5] Matcher.findTopMatch(live_embedding, allProfiles)
    │       ├─ StorageManager.loadAllActiveProfiles() → List<ProfileRecord>
    │       ├─ cosine_sim = cos(live, saved) → [(cosine_sim+1)/2] = match_score
    │       └─ top-1 선택
    │
    └─ [6] match_score >= 0.80?
            YES → AuthResult(SUCCESS, matchedUserId, matchScore)
            NO  → AuthResult(FAIL_MATCH, null, matchScore)
```

---

## 3. 스레딩 전략

| 계층 | 스레드 |
|------|--------|
| CameraX Analyzer | `Executors.newSingleThreadExecutor()` (CameraX 전용) |
| 검출/임베딩/매칭 | `Executors.newFixedThreadPool(2)` (FaceAuthSdk 내부 풀) |
| SQLite R/W | `Executors.newSingleThreadExecutor()` (직렬화, WAL 모드) |
| Callback 전달 | `MainThread Handler` (결과는 항상 UI 스레드로) |

> **규칙**: UI 스레드에서 SQLite, TFLite 추론 절대 금지.  
> 모든 내부 작업은 `CompletableFuture` or `Handler` 체인으로 처리.

---

## 4. 에러 처리

```
FailureReason (enum)
├── FAIL_QUALITY      → 품질 게이트 미통과 (안내 메시지 포함)
├── FAIL_LIVENESS     → 눈깜빡임 챌린지 실패 (재시도 2회 초과)
├── FAIL_MATCH        → match_score < 0.80
├── FAIL_CAMERA       → 카메라 권한 없음 / 초기화 실패
└── FAIL_INTERNAL     → TFLite 로드 실패, DB 오류 등
```

- 모든 예외는 `FaceAuthException(FailureReason, String message)`으로 래핑
- Callback에는 `status` + `failureReason` + `message`(사용자 안내용) 포함
- 재시도 로직은 SDK 내부에서 처리 (호출 앱은 단순 결과만 수신)

---

## 5. 보안 설계

### 5.1 Android Keystore + AES-GCM

```
KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
    "FaceAuthMasterKey",
    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .setUserAuthenticationRequired(false)  // 자동 로그인용
    .build();
```

- embedding BLOB: AES-GCM(IV 12bytes + ciphertext + tag 16bytes)으로 저장
- 복호화는 인증 시 메모리 내에서만 수행, 이후 즉시 GC 대상
- IV는 BLOB 앞 12바이트에 prefix로 저장

### 5.2 이미지 미저장 원칙

- `ImageProxy` → 분석 완료 즉시 `close()`
- 정렬된 Bitmap은 임베딩 생성 후 `bitmap.recycle()` 강제 호출
- `FileProvider`, `MediaStore` 접근 완전 금지
- 디버그/POC 모드에서도 Bitmap 로그 출력 금지

### 5.3 PII 보호

- 로그에 `user_id`, `embedding`, `match_score` 원값 출력 금지
- 로그는 `SafeLogger`를 통해서만 출력 (내부 필터링 레이어)
- `auth_audit` 테이블의 `debug_json`은 POC 모드에서만 기록

---

## 6. SQLite 스키마

```sql
-- 얼굴 프로파일
CREATE TABLE face_profile (
    profile_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          TEXT    NOT NULL,
    profile_type     TEXT    NOT NULL CHECK(profile_type IN ('NORMAL','HELMET')),
    embedding        BLOB    NOT NULL,   -- AES-GCM 암호화
    embedding_dim    INTEGER NOT NULL,
    quality_score    REAL    NOT NULL,
    created_at       INTEGER NOT NULL,   -- epoch millis
    model_version    TEXT    NOT NULL,
    device_fingerprint TEXT  NULL,
    is_active        INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_fp_user ON face_profile(user_id, is_active);

-- 인증 감사 로그 (옵션, POC 모드)
CREATE TABLE auth_audit (
    audit_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts               INTEGER NOT NULL,
    result           TEXT    NOT NULL,   -- SUCCESS|FAIL_MATCH|FAIL_LIVENESS|FAIL_QUALITY
    matched_user_id  TEXT    NULL,       -- 성공 시만 저장
    match_score      REAL    NULL,
    debug_json       TEXT    NULL        -- POC 모드에서만
);
```

---

## 7. 품질 게이트 기준값 (초기값, POC에서 조정 가능)

| 항목 | 기준 | 설정 키 |
|------|------|---------|
| 얼굴 검출 | 필수 | — |
| bbox 면적 비율 | >= 0.08 | `qualityBboxRatioMin` |
| Yaw | abs <= 15° | `qualityYawMaxDeg` |
| Pitch | abs <= 15° | `qualityPitchMaxDeg` |
| Blur (Laplacian) | >= 80.0 | `qualityBlurMin` |
| 밝기 | 40 ~ 220 | `qualityBrightnessMin/Max` |

---

## 8. Liveness 정책

- **챌린지**: 3초 내 눈깜빡임 2회
- **EAR 공식**: `EAR = (‖p2-p6‖ + ‖p3-p5‖) / (2 × ‖p1-p4‖)`
- **임계값**: `EAR < 0.21` → closed, `EAR > 0.27` → open
- **카운트**: closed→open 전이 = blink 1회
- **실패 시**: 최대 2회 재시도 후 `FAIL_LIVENESS`

---

## 9. 성능 목표

| 항목 | 목표 |
|------|------|
| 인증 1회 처리 (단일 프레임) | <= 800ms |
| 프레임 누적/Liveness 포함 전체 | <= 2s |
| 정상 사용자 인식률 | >= 80% |
| False Accept Rate | POC에서 측정 후 합의 |
| 지원 기기 | Galaxy Tab 6~10 (Android 15) |
