package com.faceauth.sdk.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import com.faceauth.sdk.logging.SafeLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * CameraX ImageProxy → Bitmap 변환 유틸리티.
 * SSoT: 분석기 per-frame 경로에서는 fromMediaImage 사용; Bitmap은 COMMITTING/품질·정렬 전용.
 */
final class ImageUtils {

    private static final String TAG = "ImageUtils";

    private ImageUtils() {}

    /**
     * YUV_420_888 → ARGB Bitmap. JPEG 없이 직접 변환. 분석기 검출 경로에서 호출 금지.
     * COMMITTING 또는 검출 후 품질/정렬용. 호출자가 .recycle() 책임.
     */
    static Bitmap toBitmapFromYuvNoJpeg(ImageProxy proxy) {
        if (proxy.getFormat() != ImageFormat.YUV_420_888) {
            SafeLogger.w(TAG, "지원하지 않는 이미지 포맷: " + proxy.getFormat());
            return null;
        }
        int w = proxy.getWidth();
        int h = proxy.getHeight();
        ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int[] argb = new int[w * h];
        int yIdx = 0;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int y = yBuf.get(yIdx) & 0xff;
                int uIdx = (row >> 1) * uRowStride + (col >> 1) * uPixelStride;
                int vIdx = (row >> 1) * vRowStride + (col >> 1) * vPixelStride;
                int u = uBuf.get(uIdx) & 0xff;
                int v = vBuf.get(vIdx) & 0xff;
                int r = clamp((int) (y + 1.370705 * (v - 128)));
                int g = clamp((int) (y - 0.337633 * (u - 128) - 0.698001 * (v - 128)));
                int b = clamp((int) (y + 1.732446 * (u - 128)));
                argb[row * w + col] = 0xff000000 | (r << 16) | (g << 8) | b;
                yIdx += yPixelStride;
            }
            yIdx += yRowStride - w * yPixelStride;
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
        int rotation = proxy.getImageInfo().getRotationDegrees();
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            m.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            bitmap.recycle();
            return rotated;
        }
        return bitmap;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /**
     * ImageProxy(YUV_420_888) → ARGB via JPEG. 분석기 경로에서 사용 금지. toBitmapFromYuvNoJpeg 권장.
     */
    static Bitmap toBitmap(ImageProxy proxy) {
        if (proxy.getFormat() != ImageFormat.YUV_420_888) {
            SafeLogger.w(TAG, "지원하지 않는 이미지 포맷: " + proxy.getFormat());
            return null;
        }

        // YUV → JPEG → Bitmap (간소화 경로)
        // 성능이 중요한 경우 RenderScript/GPU 경로로 교체 가능
        ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
        ByteBuffer yBuf  = planes[0].getBuffer();
        ByteBuffer uBuf  = planes[1].getBuffer();
        ByteBuffer vBuf  = planes[2].getBuffer();

        int ySize = yBuf.remaining();
        int uSize = uBuf.remaining();
        int vSize = vBuf.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuf.get(nv21, 0, ySize);
        vBuf.get(nv21, ySize, vSize);
        uBuf.get(nv21, ySize + vSize, uSize);

        int w = proxy.getWidth();
        int h = proxy.getHeight();
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, w, h), 90, out);

        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        // 회전 보정 (전면 카메라는 통상 270도 또는 90도 회전)
        int rotation = proxy.getImageInfo().getRotationDegrees();
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            // 전면 카메라 좌우 반전 (미러)
            m.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), m, true);
            bitmap.recycle();
            return rotated;
        }
        return bitmap;
    }
}
