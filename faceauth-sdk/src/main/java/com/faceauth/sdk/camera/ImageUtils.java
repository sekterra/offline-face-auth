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
 * 이미지는 메모리 내에서만 처리되며 파일로 저장하지 않음.
 */
final class ImageUtils {

    private static final String TAG = "ImageUtils";

    private ImageUtils() {}

    /**
     * ImageProxy(YUV_420_888) → ARGB_8888 Bitmap + 회전 보정.
     * 반환된 Bitmap은 사용 후 호출자가 .recycle()해야 함.
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
