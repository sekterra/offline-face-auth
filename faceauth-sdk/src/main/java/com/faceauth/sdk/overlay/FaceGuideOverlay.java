package com.faceauth.sdk.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 얼굴 가이드 실루엣 오버레이.
 *
 * - 반투명 어두운 배경에 타원형 투명 구멍(face frame)
 * - 가이드 메시지 표시 (한국어)
 * - 상태에 따라 테두리 색상 변경:
 *     IDLE    → 흰색
 *     QUALITY_OK → 초록색
 *     FAIL    → 빨간색
 */
public final class FaceGuideOverlay extends View {

    public enum GuideState { IDLE, QUALITY_OK, LIVENESS, FAIL }

    private final Paint overlayPaint;
    private final Paint ovalPaint;
    private final Paint textPaint;
    private final Paint clearPaint;

    private GuideState state   = GuideState.IDLE;
    private String     message = "얼굴을 원 안에 맞춰주세요";

    // 타원 영역 (레이아웃 시 계산)
    private final RectF ovalRect = new RectF();

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

        ovalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ovalPaint.setStyle(Paint.Style.STROKE);
        ovalPaint.setStrokeWidth(6f);
        ovalPaint.setColor(Color.WHITE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx    = w / 2f;
        float cy    = h / 2f - h * 0.05f;  // 약간 위로
        float rx    = w * 0.38f;
        float ry    = h * 0.28f;
        ovalRect.set(cx - rx, cy - ry, cx + rx, cy + ry);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();

        // 1. 어두운 배경
        canvas.drawRect(0, 0, w, h, overlayPaint);

        // 2. 타원 구멍 (투명)
        canvas.drawOval(ovalRect, clearPaint);

        // 3. 타원 테두리 (상태 색상)
        ovalPaint.setColor(stateColor());
        canvas.drawOval(ovalRect, ovalPaint);

        // 4. 안내 메시지
        textPaint.setColor(Color.WHITE);
        float textY = ovalRect.bottom + 80f;
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

    /** 상태 + 메시지 동시 업데이트 (Main Thread에서 호출) */
    public void update(GuideState newState, String newMessage) {
        this.state   = newState;
        this.message = newMessage != null ? newMessage : "";
        invalidate();
    }

    /** 메시지만 업데이트 */
    public void setMessage(String msg) {
        this.message = msg != null ? msg : "";
        invalidate();
    }

    public RectF getOvalRect() { return new RectF(ovalRect); }
}
