# /docs/FACEAUTH_ENROLLMENT_DATA_STANDARD.md
# FaceAuth Enrollment Data Standard (SSoT)

## 1. Purpose
Enrollment(등록) 단계에서 SQLite에 저장할 데이터 구조를 표준으로 고정한다.
이 표준은 향후 "안면인식 로그인(식별)"의 정확도와 재현성의 기준이 된다.

---

## 2. Identity SSoT
식별의 단일 기준(SSoT)은 Face Embedding(특징 벡터)이다.

- 랜드마크(눈/코/입 좌표)는 식별 SSoT가 아니다.
- 랜드마크는 디버그/품질/보조 메타데이터로만 저장 가능하다(옵션).

---

## 3. Storage Model (SQLite)

### 3.1 Entities
- person: 사용자(등록 ID)
- face_sample: 1회 등록 샘플(향후 3~5개 확장)

### 3.2 Table: person
- person_id (TEXT, PK) : 사용자가 입력한 ID

### 3.3 Table: face_sample
- sample_id (INTEGER, PK AUTOINCREMENT)
- person_id (TEXT, FK -> person.person_id)
- created_at (INTEGER) : epoch millis
- embedding_version (TEXT) : 예 "v1"
- embedding_dim (INTEGER) : 예 128/192/256/512
- embedding_blob (BLOB) : float array bytes (Little-endian) 또는 JSON TEXT (POC는 BLOB 권장)

Quality Metadata:
- bbox_left (INTEGER)
- bbox_top (INTEGER)
- bbox_right (INTEGER)
- bbox_bottom (INTEGER)
- bbox_area_ratio (REAL)
- used_yaw (REAL)
- used_pitch (REAL)
- used_roll (REAL)
- rotation_degrees (INTEGER)
- device_model (TEXT)

Optional Debug Metadata:
- landmarks_json (TEXT, nullable) : 필요 시만 저장

Indexes:
- index_face_sample_person_id (person_id)

---

## 4. Multiple Samples Policy (SSoT)
- 현재: person_id 당 1 sample만 저장해도 된다.
- 확장: person_id 당 3~5 sample 저장 가능해야 한다.
- 동일 ID 재등록 시 정책(POC 기본):
  - 기본은 "추가 샘플 적재" (maxSamplesPerPerson 초과 시 오래된 샘플 삭제)

---

## 5. Recognition Comparison Policy (SSoT, POC Default)
- 로그인 시 현재 embedding과 DB의 embedding들을 비교하여 가장 가까운 sample을 선택한다.
- 거리 함수는 config로 관리한다:
  - POC 기본: Cosine distance 또는 L2 중 택1 (프로젝트에서 1개로 고정)
- threshold 또한 config로 관리한다:
  - 초기값은 POC 데이터 수집 후 보정

---

## 6. Non-Negotiable Rules
- embedding 저장 없이 "좌표만 저장"으로 대체 금지
- embedding_dim / version을 저장하지 않는 방식 금지
- person_id 당 복수 샘플 확장 불가 구조 금지
- 임계값/거리함수는 코드 곳곳에 매직 넘버로 분산 금지
