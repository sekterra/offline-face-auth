package com.faceauth.sdk.camera;

import android.graphics.RectF;

import com.faceauth.sdk.overlay.GuideGeometry;
import com.google.mlkit.vision.face.Face;

import java.util.List;

/**
 * Single-frame snapshot for enrollment state machine (FACEAUTH_REGISTRATION_STATE_MACHINE §3.3).
 * SSoT: only faces inside guide; if multiple, select the one with largest bbox area.
 * usedYaw from ML Kit face.getHeadEulerAngleY() only.
 */
public final class EnrollmentFrameSnapshot {

    public final int     facesCount;
    public final int     roiCandidateCount;
    public final Integer selectedTrackingId;
    public final float   bboxAreaRatio;
    public final float   usedYaw;
    public final boolean isInsideGuide;

    /** Selected face for embedding (null if none). */
    public final Face selectedFace;

    private EnrollmentFrameSnapshot(int facesCount, int roiCandidateCount,
                                    Integer selectedTrackingId, float bboxAreaRatio,
                                    float usedYaw, boolean isInsideGuide, Face selectedFace) {
        this.facesCount           = facesCount;
        this.roiCandidateCount    = roiCandidateCount;
        this.selectedTrackingId   = selectedTrackingId;
        this.bboxAreaRatio        = bboxAreaRatio;
        this.usedYaw              = usedYaw;
        this.isInsideGuide        = isInsideGuide;
        this.selectedFace         = selectedFace;
    }

    /**
     * Build snapshot from detected faces. Only considers faces inside guide;
     * selects the one with largest bbox area.
     * When viewW/viewH > 0 uses view-space circle (cx, cy, r) with innerMargin; else legacy normalized ratio.
     */
    public static EnrollmentFrameSnapshot fromFaces(List<Face> faces, int imageWidth, int imageHeight,
                                                     int viewWidth, int viewHeight,
                                                     float viewCx, float viewCy, float viewR,
                                                     float innerMarginRatio,
                                                     float legacyGuideRatio) {
        if (faces == null || faces.isEmpty()) {
            return new EnrollmentFrameSnapshot(0, 0, null, 0f, 0f, false, null);
        }

        float frameArea = (float) imageWidth * imageHeight;
        Face best = null;
        float bestArea = 0f;
        int insideCount = 0;
        boolean useViewSpace = viewWidth > 0 && viewHeight > 0 && viewR > 0;

        for (Face face : faces) {
            RectF bbox = new RectF(face.getBoundingBox());
            boolean inside = useViewSpace
                    ? GuideGeometry.isInsideGuide(bbox, imageWidth, imageHeight,
                    viewWidth, viewHeight, viewCx, viewCy, viewR, innerMarginRatio)
                    : GuideGeometry.isInsideGuideLegacy(bbox, imageWidth, imageHeight, legacyGuideRatio);
            if (!inside) continue;
            insideCount++;
            float area = bbox.width() * bbox.height();
            if (area > bestArea) {
                bestArea = area;
                best = face;
            }
        }

        if (best == null) {
            return new EnrollmentFrameSnapshot(
                    faces.size(), 0, null, 0f, 0f, false, null);
        }

        float ratio = frameArea > 0 ? (bestArea / frameArea) : 0f;
        float yaw = best.getHeadEulerAngleY(); // usedYaw SSoT
        Integer trackingId = best.getTrackingId() != null ? best.getTrackingId() : null;

        return new EnrollmentFrameSnapshot(
                faces.size(),
                insideCount,
                trackingId,
                ratio,
                yaw,
                true,
                best);
    }

    /** Legacy: single radius ratio (normalized). */
    public static EnrollmentFrameSnapshot fromFaces(List<Face> faces, int imageWidth, int imageHeight,
                                                     float guideRadiusRatio) {
        return fromFaces(faces, imageWidth, imageHeight, 0, 0, 0f, 0f, 0f, 0.92f, guideRadiusRatio);
    }
}
