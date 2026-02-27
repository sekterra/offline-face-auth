# /docs/FACEAUTH_REGISTRATION_STATE_MACHINE.md
# FaceAuth Registration State Machine (SSoT)

## 1. Purpose
Registration(Enrollment) 흐름이 반복/중복/경합으로 깨지지 않도록
단방향 상태 머신으로 고정한다.

이 문서의 상태 정의/전이 규칙은 고정 SSoT이며,
코드 구현은 반드시 본 규칙을 따른다.

---

## 2. States

### 2.1 IDLE
- 기본 대기 상태
- 등록 버튼을 누르기 전
- 안정 프레임 카운트는 0

### 2.2 ARMED
- 사용자가 등록 시작을 명시적으로 요청한 상태
- "아직 캡처 조건이 충족되지 않음"
- 이 상태에서 타겟(face) 선정과 품질 조건을 만족할 때 CAPTURING로 전이

### 2.3 CAPTURING
- 안정 프레임 윈도우를 카운트하는 상태
- 같은 selectedTrackingId가 유지되고,
  bboxAreaRatio 및 기타 품질 조건이 N프레임 연속 충족되면 COMMITTING으로 전이

### 2.4 COMMITTING
- 임베딩 추출 및 DB 저장을 수행하는 상태
- 중복 저장을 막기 위해 단일 진입/단일 종료(guard) 보장
- 성공 시 SUCCESS, 실패 시 FAILED로 전이

### 2.5 SUCCESS
- 등록 성공 상태
- UI에 "성공"을 표시하고, reset 전까지 유지

### 2.6 FAILED
- 등록 실패 상태
- UI에 "실패(사유)"를 표시하고, reset 전까지 유지

---

## 3. Events

### 3.1 USER_START_ENROLL(id)
- ID 입력 후 "등록 시작" 버튼 클릭

### 3.2 USER_RESET
- 등록 상태 초기화 버튼 클릭

### 3.3 FRAME_UPDATE(faceSnapshot)
- 분석 프레임 1건의 결과 스냅샷
- faceSnapshot은 아래 정보를 포함(최소):
  - facesCount
  - roiCandidateCount
  - selectedTrackingId
  - bboxAreaRatio
  - usedYaw (SSoT)
  - isInsideGuide (SSoT)

### 3.4 COMMIT_SUCCESS(storageKey)
### 3.5 COMMIT_FAIL(reason)

---

## 4. Transition Rules (SSoT)

### 4.1 IDLE → ARMED
- 조건: USER_START_ENROLL(id)
- 액션:
  - currentEnrollId = id
  - stableFrames = 0
  - lastTrackingId = null
  - min/max metrics reset
  - state = ARMED

### 4.2 ARMED → CAPTURING
- 조건: FRAME_UPDATE에서 "등록 가능" 조건 만족
- 등록 가능 조건(최소):
  - roiCandidateCount >= 1
  - isInsideGuide == true
  - selectedTrackingId != null
  - bboxAreaRatio >= MIN_BBOX_AREA_RATIO
- 액션:
  - stableFrames = 1
  - lastTrackingId = selectedTrackingId
  - state = CAPTURING

### 4.3 ARMED 유지
- 조건: 등록 가능 조건 불만족
- 액션:
  - state 유지
  - 사유는 이벤트 로그(auth_register_blocked)로 남김

### 4.4 CAPTURING → CAPTURING (카운트 증가)
- 조건:
  - selectedTrackingId == lastTrackingId
  - bboxAreaRatio >= MIN_BBOX_AREA_RATIO
  - isInsideGuide == true
  - (옵션) abs(usedYaw) <= ENROLL_MAX_ABS_YAW
- 액션:
  - stableFrames += 1

### 4.5 CAPTURING → ARMED (리셋)
- 조건: 위 CAPTURING 유지 조건이 깨짐
- 액션:
  - stableFrames = 0
  - lastTrackingId = null
  - state = ARMED
  - 사유 로그 남김(TARGET_UNSTABLE / FACE_TOO_SMALL / OUTSIDE_GUIDE / YAW_TOO_LARGE 등)

### 4.6 CAPTURING → COMMITTING
- 조건: stableFrames >= REQUIRED_STABLE_FRAMES
- 액션:
  - state = COMMITTING
  - commit 시작(임베딩 추출 + DB 저장)
  - 중복 commit 방지 guard 적용

### 4.7 COMMITTING → SUCCESS / FAILED
- SUCCESS 조건: COMMIT_SUCCESS
- FAILED 조건: COMMIT_FAIL
- 액션:
  - state 변경
  - UI 표시 업데이트

### 4.8 ANY → IDLE
- 조건: USER_RESET
- 액션:
  - 모든 상태/카운터/ID/메트릭 초기화

---

## 5. Default Params (Config SSoT)
- REQUIRED_STABLE_FRAMES: 7 (권장 범위 5~10)
- MIN_BBOX_AREA_RATIO: 0.05
- ENROLL_MAX_ABS_YAW: 10 (옵션, 적용 시 등록 품질 안정화)

모든 파라미터는 중앙 config에서만 관리한다.

---

## 6. Required Logs (SSoT)
- auth_register_start
- auth_register_blocked
- auth_register_stable_progress
- auth_register_captured
- auth_register_persisted
- auth_register_failed

1초 로그에는 state/카운터/usedYaw/bboxAreaRatio/selectedTrackingId를 포함한다.
