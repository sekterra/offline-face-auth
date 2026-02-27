package com.faceauth.sdk.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.faceauth.sdk.api.FaceAuthConfig;
import com.faceauth.sdk.api.GuidePreset;

/**
 * 얼굴 가이드 실루엣 오버레이 (SSoT: Guide shape = Circle).
 * 동적 기하: Phone/Tablet × Portrait/Landscape preset (config).
 * 상태에 따라 테두리: IDLE 흰색, QUALITY_OK 초록, LIVENESS 노랑, FAIL 빨강.
 */
public final class FaceGuideOverlay extends View {

    public enum GuideState { IDLE, QUALITY_OK, LIVENESS, FAIL }

    private final Paint overlayPaint;
    private final Paint circleStrokePaint;
    private final Paint clearPaint;
    private final Paint textPaint;
    /** White dashed crosshair inside circle; stroke 2dp, dash 10px/10px (density-adjusted). */
    private final Paint crosshairPaint;

    private GuideState state   = GuideState.IDLE;
    private String     message = "얼굴을 원 안에 맞춰주세요";

    /** Config for dynamic preset; null = use legacy guideRadiusRatio. */
    @Nullable private FaceAuthConfig config;
    /** Legacy single ratio when config not set. */
    private float guideRadiusRatio = 0.35f;

    private float centerX;
    private float centerY;
    private float circleRadiusPx;
    private float guideRatio;
    private boolean isLandscape;
    private boolean isTablet;

    public FaceGuideOverlay(Context context) {
        this(context, null);
    }

    public FaceGuideOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        overlayPaint.setColor(Color.argb(160, 0, 0, 0));
        overlayPaint.setStyle(Paint.Style.FILL);

        clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setStyle(Paint.Style.FILL);

        circleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circleStrokePaint.setStyle(Paint.Style.STROKE);
        circleStrokePaint.setStrokeWidth(6f);
        circleStrokePaint.setColor(Color.WHITE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        float density = getResources().getDisplayMetrics().density;
        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(Color.WHITE);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(2f * density);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 10f * density}, 0f));
    }

    /** SSoT: 동적 preset 사용 시 config 설정. 모든 화면에서 동일. */
    public void setConfig(@Nullable FaceAuthConfig config) {
        this.config = config;
        updateCircle();
    }

    /** 레거시: 단일 비율 (config 미설정 시). */
    public void setGuideRadiusRatio(float ratio) {
        if (ratio > 0f && ratio != guideRadiusRatio) {
            guideRadiusRatio = ratio;
            updateCircle();
        }
    }

    private void updateCircle() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (config != null) {
            isLandscape = FaceAuthConfig.isLandscape(w, h);
            int smallestWidthDp = getContext().getResources().getConfiguration().smallestScreenWidthDp;
            isTablet = FaceAuthConfig.isTablet(smallestWidthDp);
            GuidePreset preset = config.getGuidePreset(isTablet, isLandscape);
            guideRatio = preset.guideRatio;
            float[] cx = new float[1], cy = new float[1], r = new float[1];
            GuideGeometry.computeCircleInView(w, h, preset.guideRatio, preset.centerYOffsetRatio, cx, cy, r);
            centerX = cx[0];
            centerY = cy[0];
            circleRadiusPx = r[0];
        } else {
            centerX = w / 2f;
            centerY = h / 2f;
            circleRadiusPx = GuideGeometry.getCircleRadiusPx(w, h, guideRadiusRatio);
            guideRatio = guideRadiusRatio;
            isLandscape = w > h;
            isTablet = FaceAuthConfig.isTablet(getContext().getResources().getConfiguration().smallestScreenWidthDp);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCircle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRect(0, 0, w, h, overlayPaint);
        canvas.drawCircle(centerX, centerY, circleRadiusPx, clearPaint);
        circleStrokePaint.setColor(stateColor());
        canvas.drawCircle(centerX, centerY, circleRadiusPx, circleStrokePaint);

        // Crosshair: white dashed vertical/horizontal lines inside circle only
        canvas.save();
        Path circlePath = new Path();
        circlePath.addCircle(centerX, centerY, circleRadiusPx, Path.Direction.CW);
        canvas.clipPath(circlePath);
        canvas.drawLine(centerX, centerY - circleRadiusPx, centerX, centerY + circleRadiusPx, crosshairPaint);
        canvas.drawLine(centerX - circleRadiusPx, centerY, centerX + circleRadiusPx, centerY, crosshairPaint);
        canvas.restore();

        textPaint.setColor(Color.WHITE);
        float textY = centerY + circleRadiusPx + 80f;
        canvas.drawText(message, w / 2f, textY, textPaint);
    }

    private int stateColor() {
        switch (state) {
            case QUALITY_OK: return Color.GREEN;
            case LIVENESS:   return Color.YELLOW;
            case FAIL:       return Color.RED;
            default:         return Color.WHITE;
        }
    }

    public void update(GuideState newState, String newMessage) {
        this.state   = newState;
        this.message = newMessage != null ? newMessage : "";
        invalidate();
    }

    public void setMessage(String msg) {
        this.message = msg != null ? msg : "";
        invalidate();
    }

    public float getCircleCenterX() { return centerX; }
    public float getCircleCenterY() { return centerY; }
    public float getCircleRadiusPx() { return circleRadiusPx; }
    public float getGuideRatio() { return guideRatio; }
    public boolean getIsLandscape() { return isLandscape; }
    public boolean getIsTablet() { return isTablet; }
}
