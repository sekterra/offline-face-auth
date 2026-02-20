package com.faceauth.sdk.detection;

/** 얼굴 검출/정렬 중 발생하는 예외 */
public class DetectionException extends Exception {
    public DetectionException(String msg, Throwable cause) { super(msg, cause); }
    public DetectionException(String msg) { super(msg); }
}
