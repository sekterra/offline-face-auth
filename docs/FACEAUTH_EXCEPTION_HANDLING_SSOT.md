# FaceAuth Exception Handling SSoT

## 1. Purpose
- 모든 예외를 일관된 형식으로 로깅하고, ErrorCode로 매핑한다.
- 예외를 삼키지 않는다 (do not swallow); 로그 후 재전파 또는 명시적 실패 처리.
- 분석기(analyzeFrame)에서는 반드시 `proxy.close()`를 finally에서 보장한다.

## 2. auth_error 이벤트 (표준 JSON)
- 이벤트명: `auth_error`
- 단일 라인 JSON, 한 줄에 한 건.
- 필수 필드:
  - `event`: `"auth_error"`
  - `errorCode`: ErrorCode enum 값 (문자열)
  - `message`: 예외 메시지 (PII 제외, 간단 요약)
- 선택 필드 (컨텍스트):
  - `where`: 발생 위치 식별자 (예: `EnrollmentActivity.analyzeFrame`)
  - `screen`: `enrollment` | `authentication` | `yaw_verify`
  - `state`: 등록/인증 상태 (해당 시)
  - `trackingId`: selectedTrackingId (있을 때)
  - `facesCount`, `roiCandidateCount`, `bboxAreaRatio`, `usedYaw`: 분석기 컨텍스트 (있을 때)

예시:
```json
{"event":"auth_error","errorCode":"DETECTION_FAIL","message":"얼굴 검출 실패","where":"EnrollmentActivity.analyzeFrame","screen":"enrollment","state":"ARMED","facesCount":0}
```

## 3. ErrorCode (SSoT)
| 코드 | 설명 |
|------|------|
| DETECTION_FAIL | ML Kit 얼굴 검출 실패 |
| EMBEDDING_FAIL | 임베딩 추출 실패 (TFLite/정렬) |
| STORAGE_FAIL | DB/저장 실패 |
| CRYPTO_FAIL | 암호화/복호화 실패 |
| LIVENESS_FAIL | 라이브니스 검증 실패 |
| INTERNAL | 기타 내부 오류 |
| UNKNOWN | 매핑 불가 예외 |

## 4. 규칙
- 예외를 catch한 경우: auth_error 로그를 남긴 뒤 재전파(wrap)하거나, 사용자에게 실패 결과를 전달한다. 로그 없이 무시하지 않는다.
- 분석기: catch 블록에서 auth_error 로그 시 위 선택 필드(screen/state/trackingId/facesCount/roiCandidateCount/bboxAreaRatio/usedYaw)를 채울 수 있으면 채운다. 그 후 기존 처리(실패 전달 등)를 수행하고, finally에서 proxy.close()를 호출한다.
- ErrorMapper: Throwable + context(where 등) → ErrorCode. FaceAuthException은 이미 errorCode 보유.

## 5. AOP (선택)
- 가능 시 core/service/repository/usecase 등에서 @Around로 Throwable을 잡아 ErrorCode 매핑 + auth_error 로그 후 재전파할 수 있다.
- AOP가 분석기 메서드를 커버하지 못하는 경우, 분석기 catch 블록에서 동일한 auth_error 형식으로 로그한다.
- **현재 구현**: AspectJ 미도입(빌드 단순화). `AuthErrorLogger.log()`를 각 catch 블록에서 호출하여 동일한 auth_error JSON 출력. 분석기(EnrollmentActivity.analyzeFrame, AuthenticationActivity.analyzeFrame) 및 EnrollmentActivity.startCommit에서 호출.
