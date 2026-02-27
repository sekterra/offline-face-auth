# FaceAuth SQLite 테이블 정의서

- **데이터베이스명**: `faceauth.db`
- **DB 버전**: 1
- **WAL 모드**: 사용
- **외래키**: 활성화 (`setForeignKeyConstraintsEnabled(true)`)

---

## 1. 얼굴 프로파일 (face_profile)

등록된 사용자별 얼굴 임베딩·메타데이터 저장. 임베딩은 Keystore AES-GCM 암호화 후 BLOB으로 저장된다.

| 컬럼명(영문) | 한글 설명 | 타입 | 제약 | 비고 |
|-------------|----------|------|------|------|
| `profile_id` | 프로파일 ID | INTEGER | PRIMARY KEY, AUTOINCREMENT | 내부 PK |
| `user_id` | 사용자 ID | TEXT | NOT NULL | 로그인/인식용 ID |
| `profile_type` | 프로파일 유형 | TEXT | NOT NULL, CHECK IN ('NORMAL','HELMET') | 일반/헬멧 등 |
| `embedding` | 임베딩 벡터 | BLOB | NOT NULL | 암호화된 float[] |
| `embedding_dim` | 임베딩 차원 | INTEGER | NOT NULL | 예: 192 |
| `quality_score` | 품질 점수 | REAL | NOT NULL | 등록 시 품질 게이트 점수 |
| `created_at` | 생성 시각 | INTEGER | NOT NULL | Unix timestamp (ms) |
| `model_version` | 모델 버전 | TEXT | NOT NULL | TFLite 모델 버전 식별 |
| `device_fingerprint` | 기기 지문 | TEXT | NULL | 선택 |
| `is_active` | 활성 여부 | INTEGER | NOT NULL, DEFAULT 1 | 1=활성, 0=논리 삭제 |

### 인덱스

| 인덱스명 | 대상 컬럼 | 용도 |
|----------|----------|------|
| `idx_fp_user` | `user_id`, `is_active` | 활성 프로파일 조회·사용자별 조회 |

### DDL (참고)

```sql
CREATE TABLE face_profile (
  profile_id    INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       TEXT NOT NULL,
  profile_type  TEXT NOT NULL CHECK(profile_type IN ('NORMAL','HELMET')),
  embedding     BLOB NOT NULL,
  embedding_dim INTEGER NOT NULL,
  quality_score REAL NOT NULL,
  created_at    INTEGER NOT NULL,
  model_version TEXT NOT NULL,
  device_fingerprint TEXT NULL,
  is_active     INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_fp_user ON face_profile(user_id, is_active);
```

---

## 2. 인증 감사 로그 (auth_audit)

인증 시도 결과·매칭 정보 기록. POC 모드에서만 `debug_json` 저장.

| 컬럼명(영문) | 한글 설명 | 타입 | 제약 | 비고 |
|-------------|----------|------|------|------|
| `audit_id` | 감사 로그 ID | INTEGER | PRIMARY KEY, AUTOINCREMENT | 내부 PK |
| `ts` | 시각 | INTEGER | NOT NULL | Unix timestamp (ms) |
| `result` | 인증 결과 | TEXT | NOT NULL | 예: SUCCESS, NO_MATCH 등 |
| `matched_user_id` | 매칭된 사용자 ID | TEXT | NULL | 매칭 시 user_id |
| `match_score` | 매칭 점수 | REAL | NULL | 코사인 유사도 등 |
| `debug_json` | 디버그 JSON | TEXT | NULL | POC 모드에서만 저장 |

### DDL (참고)

```sql
CREATE TABLE auth_audit (
  audit_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  ts             INTEGER NOT NULL,
  result         TEXT NOT NULL,
  matched_user_id TEXT NULL,
  match_score    REAL NULL,
  debug_json     TEXT NULL
);
```

---

## 3. 참고 사항

- **스키마 소스**: `FaceAuthDatabase.java` (SSoT §6.1 기준)
- **쓰기**: `StorageManager`를 통해서만 수행 (암호화·직렬화 보장)
- **업그레이드**: 현재 `onUpgrade`는 테이블 DROP 후 재생성 (마이그레이션 전략 TODO)
