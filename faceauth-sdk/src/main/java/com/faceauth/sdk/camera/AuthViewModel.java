package com.faceauth.sdk.camera;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.faceauth.sdk.api.VerificationState;

/**
 * 인증 화면 전용 ViewModel.
 * 상태 메시지 영역(tvStatusMessage) 갱신은 이 ViewModel을 통해서만 수행.
 * distinctUntilChanged 적용으로 상태가 바뀔 때만 UI에 반영.
 */
public final class AuthViewModel extends ViewModel {

    private final MutableLiveData<String> statusMessageLiveData = new MutableLiveData<>("");
    private VerificationState lastState = VerificationState.IDLE;

    /**
     * 검증 상태를 한글 메시지로 매핑해 LiveData에 반영.
     * 호출 스레드: 메인 스레드 (Activity의 mainHandler.post 내부에서 호출).
     * distinctUntilChanged: 동일 상태 연속 호출 시 setValue 하지 않음.
     */
    public void updateStatusMessage(VerificationState state) {
        if (state == null) return;
        if (state.equals(lastState)) return;
        lastState = state;
        statusMessageLiveData.setValue(state.getDisplayMessage());
    }

    /** 상태 메시지 영역에 표시할 문자열. Activity에서 observe. */
    public LiveData<String> getStatusMessageLiveData() {
        return statusMessageLiveData;
    }
}
