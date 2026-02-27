# SSOT v1 — 2차 검증(그레이존) 검증 가이드

## 1. 그레이존 트리거 재현 방법

- **조건**: `T_LOW <= top1Score < T_HIGH` 또는 `margin < M_HIGH` 인 경우 2차 검증이 수행됩니다.
- **기본값**: `T_LOW=0.70`, `T_HIGH=0.88`, `M_HIGH=0.06`, `T2=0.82`, `M2=0.04`, `M_AMBIGUOUS=0.03`.

**재현 절차 (POC)**  
1. 동일 사용자로 **여러 번 등록**하여 같은 `user_id`에 템플릿 2개 이상 확보.  
2. 인증 시 **조명/각도**를 살짝 바꿔 top1Score가 0.75~0.87 구간이 되도록 시도.  
3. 또는 **비슷한 두 사용자**를 등록한 뒤, 한 명으로 인증할 때 margin이 작게 나오도록 시도.

**설정으로 그레이존 유도**  
- `FaceAuthConfig.Builder`에서 `grayTHigh(0.95f)` 등으로 1차 통과를 어렵게 하면, 더 많은 시도가 그레이존으로 들어갑니다.  
- `pocMode(true)` 로 초기화해야 `auth_audit.debug_json` 이 저장됩니다.

---

## 2. auth_audit.debug_json 에서 확인할 항목

POC 모드에서 `auth_audit` 의 `debug_json` 컬럼에는 아래 필드가 들어갑니다.

| 필드 | 설명 |
|------|------|
| `profileType` | 프로파일 유형 (예: "NORMAL") |
| `top1UserId` | 1위 사용자 ID |
| `top1Score` | 1위 점수 (match_score) |
| `top2UserId` | 2위 사용자 ID (없으면 null) |
| `top2Score` | 2위 점수 |
| `margin` | top1Score - top2Score |
| `triggerSecondary` | 2차 검증 수행 여부 |
| `centroidScore` | 2차 검증 시 쿼리–센트로이드 코사인 점수 (2차 수행 시만 존재) |
| `decision` | ACCEPT / REJECT / UNCERTAIN |
| `reason` | primary / secondary / below_t_low / low_margin / secondary_fail |
| `quality` | `bboxAreaRatio`, `yawAbs`, `stableFrames`, `qualityScore` 스냅샷 |

**확인 포인트**  
- 그레이존 진입 시: `triggerSecondary == true`, `centroidScore` 존재.  
- 1차 통과: `decision == "ACCEPT"`, `reason == "primary"`.  
- 2차 통과: `decision == "ACCEPT"`, `reason == "secondary"`, `centroidScore >= T2`, `margin >= M2`.  
- “다시 시도” 케이스: `decision == "UNCERTAIN"`, `reason == "low_margin"`, `audit` 의 `result == "LOW_MARGIN"`.

---

## 3. 단위 테스트

- **EmbeddingMatcherTest**  
  - `findTopTwoUsersWithMargin_twoUsers_returnsTop1Top2Margin`: 두 사용자·멀티 템플릿 시 top1/top2/margin 정확성.  
  - `findTopTwoUsersWithMargin_empty_returnsNullsAndZero`: 후보 없을 때 null/0 반환.  
  - 기존 `cosineSimilarityNorm` / `findTopMatch` 테스트로 코사인·top1 일치 유지.

- **SecondaryVerifierTest**  
  - `centroid_singleTemplate_acceptWhenScoreAboveT2`: 단일 템플릿 센트로이드로 2차 ACCEPT.  
  - `centroid_twoTemplates_meanUsed`: 동일 사용자 2템플릿 평균(센트로이드) 사용.  
  - `verify_lowMargin_returnsUncertain`: margin < M_AMBIGUOUS 시 UNCERTAIN.  
  - `verify_unknownUser_returnsSecondaryFail`: 미등록 사용자 시 SECONDARY_FAIL.

로컬에서 실행:

```bash
./gradlew :faceauth-sdk:testDebugUnitTest --tests "com.faceauth.sdk.matcher.*"
```

---

## 4. 상태 메시지 영역 (실시간 검증 상태 UI)

- **명칭**: 한국어 **상태 메시지 영역**, 코드 **tvStatusMessage** (TextView).
- **위치**: 인증 화면 하단 중앙. 레이아웃 ID: `tv_status_message`, `contentDescription`: "상태 메시지 영역".
- **갱신 경로**: Analyzer → `postVerificationState(state)` (mainHandler.post) → **AuthViewModel.updateStatusMessage(state)** → LiveData → Activity observe → `tvStatusMessage.setText()`. Analyzer는 TextView를 직접 건드리지 않음.
- **상태 흐름**: IDLE → FIRST_VERIFY_RUNNING → (그레이존 시) SECONDARY_VERIFY_RUNNING → SECONDARY_VERIFY_DONE → ACCEPT / REJECT / UNCERTAIN.
- **표시 문구**: "1차 검증 수행 중" → (그레이존 시) "2차 검증 수행" → "2차 검증 수행 완료" → "인식 완료" / "인식 실패" / "다시 시도".
- **distinctUntilChanged**: ViewModel 내부에서 동일 상태 연속 호출 시 `setValue` 생략.

### 테스트 체크리스트

| 시나리오 | 기대 동작 |
|----------|------------|
| **정상 (고 margin)** | "1차 검증 수행 중" → 곧바로 "인식 완료" 또는 "인식 실패". 2차 관련 문구 없음. |
| **그레이존 (저 margin)** | "1차 검증 수행 중" → "2차 검증 수행" → "2차 검증 수행 완료" → "인식 완료" / "인식 실패" / "다시 시도" 중 하나. |
| **재시도** | NO_MATCH 후 1분 미만 재시도 시 상태가 IDLE로 리셋되고, 다음 유효 프레임에서 다시 "1차 검증 수행 중" 표시. |
| **제약** | 버튼 추가 없음, 카메라/프리뷰 정상 동작, 프레임마다 깜빡임 없음 (상태 변경 시에만 텍스트 변경). |

---

## 5. 다중 템플릿 등록

- 등록 시 `StorageManager.saveProfile()` 은 `(user_id, profile_type)` 제한 없이 삽입합니다.  
- 품질 게이트를 통과하면 같은 사용자로 여러 행이 쌓이며, 인증 시 해당 사용자의 **모든 활성 템플릿**에 대해 1차 점수를 계산하고, 사용자 단위 top1/top2·margin을 구한 뒤 그레이존 시 2차(센트로이드) 검증을 사용합니다.

“실시간 검증 상태가 표시되는 UI 영역"을 상태메시지 영역 명칭을 정함