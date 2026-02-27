# /docs/FACEAUTH_COMMON_DECISION_RULES.md
# FaceAuth Common Decision Rules (SSoT)
# Enrollment / Yaw Verification / Login / Liveness

## 1. Purpose
모듈별로 판정 기준이 갈라져 반복 오류가 생기는 것을 방지한다.
모든 판정의 공통 규칙을 본 문서로 고정한다.

---

## 2. Target Face Selection SSoT (Multi-person)
- 후보는 "Guide 내부" 얼굴만.
- Guide 내부 후보가 여러 개면 bbox 면적 최대 1개만 선택.
- 나머지 얼굴은 무시.

SSoT outputs:
- roiCandidateCount
- selectedTrackingId
- selectedBboxAreaRatio
- isInsideGuide

---

## 3. Guide Geometry SSoT
- Guide shape: Circle
- 모든 화면에서 동일 위치/동일 크기
- 화면 비율 기반 scaling (픽셀 고정 금지)
- Portrait 기본, Landscape는 설정으로 확장 가능

Guide function SSoT:
- isInsideGuide(faceBoundingBox): Boolean

---

## 4. Yaw Signal SSoT
- 의사결정에 사용하는 yaw 값은 usedYaw 단일 값이다.
- rawYaw는 디버그 전용이며, 판정 로직에 섞지 않는다.

Yaw source:
- ML Kit face.getHeadEulerAngleY()

---

## 5. Thresholds (Config SSoT)

### 5.1 Yaw Verification (Debug)
- CENTER: abs(usedYaw) <= 10
- LEFT: usedYaw <= -13
- RIGHT: usedYaw >= +13
- 방식: 60초 내 1회라도 만족하면 성공

### 5.2 Liveness (POC Fixed)
- 로그인 시도마다 LEFT 또는 RIGHT 중 하나를 랜덤으로 선택
- 선택된 방향이 1회라도 만족하면 통과
- CENTER는 강제하지 않음
- LEFT: usedYaw <= -13
- RIGHT: usedYaw >= +13

### 5.3 Enrollment (Quality Gate)
- MIN_BBOX_AREA_RATIO: 0.05
- (옵션) ENROLL_MAX_ABS_YAW: 10
- REQUIRED_STABLE_FRAMES: 7

---

## 6. Block Reasons (SSoT)
모든 차단/실패는 reason으로 로그에 남긴다.

Common reasons:
- NO_ROI_CANDIDATE
- OUTSIDE_GUIDE
- FACE_TOO_SMALL
- TARGET_UNSTABLE
- YAW_UNAVAILABLE
- MULTI_FACE_UNSTABLE
- TIMEOUT
- EXTRACT_FAIL
- PERSIST_FAIL

---

## 7. Logging SSoT
- 1초 로그: FPS, avg/max processMs, facesCount, roiCandidateCount, selectedTrackingId,
  selectedBboxAreaRatio, usedYaw, state(등록/라이브니스), streak/counter 등
- 이벤트 로그:
  - auth_yaw_verify_start/success/timeout/reset
  - auth_register_start/blocked/stable_progress/captured/persisted/failed
  - auth_login_start/matched/failed
  - auth_liveness_start/chosen_dir/pass/fail

---

## 8. Non-Negotiable
- usedYaw 혼용 금지(rawYaw와 섞지 말 것)
- Guide 화면마다 다른 크기/위치 금지
- Multi-face에서 2명 이상 동시 처리 금지(항상 1명만)
- Threshold/params 매직넘버 분산 금지(central config only)
