# Cursor AI Prompt (EN) — FaceAuth POC SSoT & Principles Lock (Read First, Do Not Deviate)

You are working on an Android FaceAuth POC. Before making any code changes, you MUST internalize and follow the fixed principles below. Treat this document as the Single Source of Truth (SSoT). Do NOT introduce new rules, do NOT change requirements, and do NOT implement extra features beyond what is explicitly stated.

## 0) Project Goal
This POC is not a throwaway demo. It must be implementable as a reusable Android Library for a real production project later.

## 1) Fixed Features (Functional Requirements)

### 1.1 Enrollment (Registration)
- User inputs an ID.
- Using the FRONT camera, the user aligns their face inside a face guide.
- The system detects the face and stores face recognition data into SQLite.
- The core recognition data to store is Face Embedding (feature vector). Do NOT store only landmarks as the primary recognition data.
- Also store minimal quality metadata (bbox, bbox area ratio, pitch/yaw/roll, timestamp, device info).
- UI requirement: show recognition metrics and numeric values at bottom-left of the screen.
- Storage must be designed to support 1 sample now, and 3–5 samples per ID later.

### 1.2 Yaw Verification (Debug Mode)
- Keep the current yaw verification function as-is.
- It is used to validate that face.getHeadEulerAngleY() works reliably for CENTER/LEFT/RIGHT.

### 1.3 Face Login (Recognition)
- Perform face recognition and identify an ID.
- If an ID is identified, show the ID on screen.
- Liveness is not required to be complex now; it will be added/used later using the same yaw-based mechanism.

## 2) Fixed Liveness Policy (POC Decision)
- Liveness uses yaw (face.getHeadEulerAngleY()).
- For each login attempt, randomly choose exactly ONE direction: LEFT or RIGHT.
- The user must perform the chosen direction.
- Passing condition: the chosen direction is detected at least ONCE (no streak required).
- Do NOT require CENTER as a mandatory step.
- Default thresholds:
  - LEFT: yaw <= -13
  - RIGHT: yaw >= +13

## 3) Global Principles (SSoT)

### 3.1 Target Face Selection SSoT (Multi-person environment)
- Only consider faces that are INSIDE the face guide region.
- If multiple faces are inside the guide, select the ONE face with the largest bounding box area.
- Ignore all other faces.

### 3.2 Face Guide Geometry SSoT
- All face guide lines must have the SAME position and SAME size across all screens:
  - Enrollment
  - Yaw Verification
  - Face Login
- The face guide shape is a CIRCLE.
- The circle must scale proportionally across device sizes using screen ratio (not fixed pixels).
- Default orientation baseline is portrait, but landscape must be supported later via configuration.

### 3.3 Yaw Signal SSoT
- Use ONE yaw value for decisions across the app:
  - `usedYaw` (rawYaw may exist for debug, but decisions use usedYaw consistently).
- Use ML Kit face.getHeadEulerAngleY() as the yaw source.

### 3.4 Camera & Performance Principles
- Use CameraX ImageAnalysis + STRATEGY_KEEP_ONLY_LATEST.
- Use InputImage.fromMediaImage(MediaImage, rotation).
- Do NOT reintroduce Bitmap/JPEG conversions in the analyzer path.
- proxy.close() must be guaranteed in finally.
- Use isProcessing gate and a single-thread executor to prevent overlap.
- Maintain structured logs (1-second metrics + event logs). Add new logs only if needed for diagnosis.

### 3.5 Zoom Policy
- If reducing zoom helps yaw/liveness stability, it is allowed.
- Zoom behavior must be configurable and must not break other functions.

## 4) Library-Ready Requirement
All work should move toward a reusable library:
- Keep logic separated from Activities when possible.
- Keep thresholds and guide geometry in a centralized config.
- DB storage should be abstracted behind an interface (injectable repository).
- Avoid hard-coded UI assumptions in core logic.

## 5) Delivery Rules
- When asked to change something, implement ONLY what is requested.
- Do not add unrelated improvements.
- Do not change the fixed principles above.
- If something conflicts with these principles, STOP and explain the conflict instead of improvising.

Now proceed with the requested code changes strictly under these rules.

---

## References

- [FACEAUTH_REGISTRATION_STATE_MACHINE.md](FACEAUTH_REGISTRATION_STATE_MACHINE.md)
- [FACEAUTH_ENROLLMENT_DATA_STANDARD.md](FACEAUTH_ENROLLMENT_DATA_STANDARD.md)
- [FACEAUTH_COMMON_DECISION_RULES.md](FACEAUTH_COMMON_DECISION_RULES.md)
