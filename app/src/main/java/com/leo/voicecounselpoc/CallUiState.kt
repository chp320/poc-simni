package com.leo.voicecounselpoc

/**
 * 통화 세션의 단계.
 *
 * [Connecting] → [Connected] → [InCall] 은 현재 UI에서 버튼 두 번으로 나뉘어 있다.
 * S2(통화 UX)에서 버튼 하나로 합쳐지면 이 전이가 자동으로 이어질 뿐,
 * 단계 자체는 그대로 쓴다 — 연결 중 화면이 [Connecting] 에 대응한다.
 */
sealed interface CallPhase {

    /** 통화 전. */
    data object Idle : CallPhase

    /** Live 세션 연결 중. Phase 0 실측 약 1.8초. */
    data object Connecting : CallPhase

    /** 세션은 열렸으나 아직 음성 대화를 시작하지 않은 상태. */
    data object Connected : CallPhase

    /** 음성 대화 진행 중. */
    data object InCall : CallPhase

    /** 연결 또는 대화 중 오류. */
    data class Failed(val message: String) : CallPhase
}

/** App Check 토큰 상태. 토큰이 없으면 Live API 호출이 거부된다. */
sealed interface AppCheckStatus {
    data object Checking : AppCheckStatus
    data object Ok : AppCheckStatus
    data class Failed(val message: String) : AppCheckStatus
}

/**
 * 마이크 캡처를 단독으로 검증하기 위한 상태.
 *
 * 통화 경로(`startAudioConversation`)는 SDK 자체 `AudioRecord` 를 쓰므로 이 진폭과 무관하다.
 * S2에서 통화 화면이 들어오면 제거한다.
 */
data class MicProbeState(
    val isRecording: Boolean = false,
    val amplitude: Int = 0,
    val message: String = "마이크 대기 중",
)

/** 통화 중 사용자에게 알릴 내용. 음성으로 개입하지 않고 화면에만 표시한다. */
sealed interface CallNotice {

    /** 상한 5분 전. */
    data object FiveMinutesLeft : CallNotice

    /** 곧 종료. */
    data object EndingSoon : CallNotice

    /** 오래 아무 말도 오가지 않음. 끊지 않고 확인만 한다. */
    data object StillThere : CallNotice
}

/** 화면이 그리는 데 필요한 전부. */
data class CallUiState(
    val appCheck: AppCheckStatus = AppCheckStatus.Checking,
    val phase: CallPhase = CallPhase.Idle,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    /** 통화 경과 시간(초). 상한 표시와 자동 종료 판단에 쓴다. */
    val elapsedSeconds: Int = 0,
    val isMuted: Boolean = false,
    val notice: CallNotice? = null,
    /** 통화가 자동으로 끝난 이유. 대기 화면에 한 번 보여주고 다음 통화 시작 시 지운다. */
    val endedReason: String? = null,
    /**
     * 전사를 화면에 보여줄지. 기본은 숨김이다.
     *
     * 전사는 별도 ASR 경로라 부정확한데("오늘은 일요일이고" → "응. 응. 이들이고"),
     * 화면에 보이면 모델이 못 알아들었다는 오해만 만든다. 설정 토글은 S4에서 붙인다.
     */
    val showTranscript: Boolean = false,
    val micProbe: MicProbeState = MicProbeState(),
) {
    /** 세션이 열려 있는가 (대화 중 포함). */
    val isSessionOpen: Boolean
        get() = phase is CallPhase.Connected || phase is CallPhase.InCall

    /** 음성 대화가 진행 중인가. */
    val isInCall: Boolean
        get() = phase is CallPhase.InCall

    /** 전이 중이라 버튼을 눌러서는 안 되는 상태인가. */
    val isTransitioning: Boolean
        get() = phase is CallPhase.Connecting

    /** `12:34` 형식의 경과 시간. */
    val elapsedLabel: String
        get() = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
}
