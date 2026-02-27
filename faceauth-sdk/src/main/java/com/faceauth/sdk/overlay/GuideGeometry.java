package com.faceauth.sdk.overlay;

import android.graphics.RectF;

/**
 * Face guide geometry SSoT (FACEAUTH_COMMON_DECISION_RULES §3).
 * Circle in PreviewView (view coordinates). Dynamic preset by device/orientation.
 * Inside-check: transform face bbox center image→view, then dist2 <= effectiveR^2.
 */
public final class GuideGeometry {

    private GuideGeometry() {}

    /**
     * Compute circle in view coordinates.
     * diameter = min(viewW, viewH) * guideRatio, r = diameter/2,
     * cx = viewW/2, cy = viewH/2 + min(viewW, viewH) * centerYOffsetRatio.
     */
    public static void computeCircleInView(int viewWidth, int viewHeight,
                                            float guideRatio, float centerYOffsetRatio,
                                            float[] outCx, float[] outCy, float[] outR) {
        float min = Math.min(viewWidth, viewHeight);
        float diameter = min * guideRatio;
        float r = diameter * 0.5f;
        float cx = viewWidth * 0.5f;
        float cy = viewHeight * 0.5f + min * centerYOffsetRatio;
        if (outCx != null && outCx.length > 0) outCx[0] = cx;
        if (outCy != null && outCy.length > 0) outCy[0] = cy;
        if (outR != null && outR.length > 0) outR[0] = r;
    }

    /**
     * View dimensions + guide circle (cx, cy, r) in view space.
     * Transforms face center from image to view using FIT_CENTER scale.
     */
    public static boolean isInsideGuide(RectF faceBbox, int imageWidth, int imageHeight,
                                        int viewWidth, int viewHeight,
                                        float viewCx, float viewCy, float viewR,
                                        float innerMarginRatio) {
        if (faceBbox == null || imageWidth <= 0 || imageHeight <= 0 || viewR <= 0) {
            return false;
        }
        float fx = (faceBbox.left + faceBbox.right) * 0.5f;
        float fy = (faceBbox.top + faceBbox.bottom) * 0.5f;
        // FIT_CENTER: scale = min(viewW/imageW, viewH/imageH), offset so image centered in view
        float scale = Math.min((float) viewWidth / imageWidth, (float) viewHeight / imageHeight);
        float drawW = imageWidth * scale;
        float drawH = imageHeight * scale;
        float offsetX = (viewWidth - drawW) * 0.5f;
        float offsetY = (viewHeight - drawH) * 0.5f;
        float vx = offsetX + fx * scale;
        float vy = offsetY + fy * scale;
        float dx = vx - viewCx;
        float dy = vy - viewCy;
        float dist2 = dx * dx + dy * dy;
        float effectiveR = viewR * innerMarginRatio;
        return dist2 <= effectiveR * effectiveR;
    }

    /**
     * Legacy: normalized image space (center 0.5, 0.5), radius = radiusRatio.
     * Used when view dimensions not yet available.
     */
    public static boolean isInsideGuideLegacy(RectF faceBbox, int imageWidth, int imageHeight, float radiusRatio) {
        if (faceBbox == null || imageWidth <= 0 || imageHeight <= 0 || radiusRatio <= 0) {
            return false;
        }
        float cx = (faceBbox.left + faceBbox.right) * 0.5f;
        float cy = (faceBbox.top + faceBbox.bottom) * 0.5f;
        float nx = cx / imageWidth;
        float ny = cy / imageHeight;
        float dx = nx - 0.5f;
        float dy = ny - 0.5f;
        return (dx * dx + dy * dy) <= (radiusRatio * radiusRatio);
    }

    /**
     * Circle radius in view pixels (legacy single-ratio).
     */
    public static float getCircleRadiusPx(int viewWidth, int viewHeight, float radiusRatio) {
        float shortSide = Math.min(viewWidth, viewHeight);
        return shortSide * radiusRatio * 0.5f;
    }
}
