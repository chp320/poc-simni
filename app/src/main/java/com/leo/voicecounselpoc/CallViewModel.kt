package com.leo.voicecounselpoc

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 통화 상태와 로직의 단일 소유자.
 *
 * `MainActivity` 가 아니라 여기에 상태를 두는 이유는 **화면 회전에도 통화가 유지**되어야 하기
 * 때문이다. Activity 가 재생성되어도 ViewModel 은 살아남으므로 Live 세션이 끊기지 않는다.
 *
 * [AndroidViewModel] 인 이유는 마이크 뮤트 때문이다 — SDK 가 마이크를 내부에서 잡고 있어
 * `AudioManager.setMicrophoneMute()` 로 처리해야 하고, 여기에 Context 가 필요하다.
 *
 * 권한 확인·요청은 여전히 Activity 에 남긴다.
 *
 * 화면 잠김·다른 앱 전환 중에도 통화를 유지하는 [CallService] 는 유지 역할만 한다 (B1, 이슈 #13).
 * 세션은 여전히 여기서 소유하므로, 시스템이 Activity 를 파괴해 이 ViewModel 이 정리되면 통화도 끝난다.
 */
class CallViewModel(app: Application) : AndroidViewModel(app) {

    private val liveSession = LiveSessionManager()
    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    /**
     * 면책 고지 전체판을 확인했는지 (이슈 #18). 최초 실행 때 한 번만 받는다.
     *
     * [CallUiState] 에 넣지 않은 이유: 통화를 시작·종료할 때마다 [CallUiState] 를 새로 만드는데,
     * 그때마다 이 값을 챙겨 옮기다 빠뜨리면 통화 뒤에 고지 화면이 다시 뜬다. 수명이 다른 값이라 따로 둔다.
     *
     * 저장하는 것은 이 true/false 하나다. 백업은 이미 전부 막혀 있다(allowBackup=false).
     */
    private val prefs = app.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val _disclaimerAccepted = MutableStateFlow(prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false))
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
        _disclaimerAccepted.value = true
        Log.i(TAG, "면책 고지 확인됨")
    }

    private var conversationJob: Job? = null
    private var timerJob: Job? = null
    private var micProbeJob: Job? = null

    /** 마지막으로 말이 오간 시각(통화 경과 초 기준). 긴 침묵 판단에 쓴다. */
    private var lastActivitySec = 0

    /**
     * 내가 마지막으로 말한 시각과 모델이 마지막으로 답한 시각 (통화 경과 초 기준).
     *
     * 이슈 #24: 세션은 살아 있는데(`isAudioConversationActive() == true`) 모델만 응답을
     * 멈추는 경우가 있다. 2.5 모델에서 문장 중간("혼자 고민하지 마시고,")에 끊긴 채
     * 세션 객체는 멀쩡했다. 생존 확인만으로는 이 상태를 잡을 수 없어서
     * "내가 말했는데 답이 없다"를 따로 감시한다.
     *
     * -1 은 아직 한 번도 없었다는 뜻이다.
     */
    private var lastUserSpeechSec = -1
    private var lastModelReplySec = -1

    /** App Check 토큰을 한 번 요청해 콘솔 등록 여부를 확인한다. 화면 진입 시 1회. */
    fun verifyAppCheck() {
        if (_uiState.value.appCheck != AppCheckStatus.Checking) return

        Firebase.appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                Log.i(TAG, "App Check 토큰 획득 성공 — 디버그 토큰이 콘솔에 등록되어 있다.")
                _uiState.update { it.copy(appCheck = AppCheckStatus.Ok) }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "App Check 토큰 획득 실패 — 콘솔에 디버그 토큰을 등록해야 한다.", e)
                _uiState.update {
                    it.copy(appCheck = AppCheckStatus.Failed(e.message ?: "알 수 없는 오류"))
                }
            }
    }

    // ---- 통화 시작 / 종료 --------------------------------------------------

    /**
     * 통화를 시작한다. 세션 연결과 음성 대화 시작이 하나로 이어진다 (요구사항 2.2).
     *
     * 호출 전에 `RECORD_AUDIO` 권한이 허용되어 있어야 한다.
     */
    fun startCall() {
        val state = _uiState.value
        if (state.isTransitioning || state.isSessionOpen) return

        _uiState.update {
            CallUiState(
                appCheck = it.appCheck,
                phase = CallPhase.Connecting,
                showTranscript = it.showTranscript,
            )
        }

        viewModelScope.launch {
            try {
                liveSession.connect(AiConfig.LIVE_MODEL_NAME)
                startConversation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live 세션 연결 실패", e)
                _uiState.update {
                    it.copy(phase = CallPhase.Failed(e.message ?: "연결에 실패했습니다"))
                }
            }
        }
    }

    // RECORD_AUDIO 권한은 MainActivity.onStartCall 에서 확인한 뒤에만 startCall 이 불린다.
    @SuppressLint("MissingPermission")
    private fun startConversation() {
        lastActivitySec = 0
        lastUserSpeechSec = -1
        lastModelReplySec = -1
        _uiState.update { it.copy(phase = CallPhase.InCall, elapsedSeconds = 0) }

        // SDK 가 AudioRecord/AudioTrack 을 만들기 전에 통화 모드로 들어가야 한다.
        enterCommunicationAudio()
        // 화면이 꺼지거나 다른 앱으로 가도 마이크를 유지한다 (이슈 #13).
        // 사용자가 버튼을 누른 직후라 앱이 화면에 보이는 상태에서 시작된다.
        CallService.start(getApplication())

        conversationJob = viewModelScope.launch {
            try {
                liveSession.startConversation(
                    onTranscript = { input, output ->
                        val now = _uiState.value.elapsedSeconds
                        lastActivitySec = now
                        if (input != null) lastUserSpeechSec = now
                        if (output != null) lastModelReplySec = now
                        _uiState.update {
                            it.copy(
                                inputTranscript = input ?: it.inputTranscript,
                                outputTranscript = output ?: it.outputTranscript,
                                // 말이 오갔으니 침묵 안내는 거둔다.
                                notice = if (it.notice == CallNotice.StillThere) null else it.notice,
                            )
                        }
                    },
                    // 직접 오디오 경로는 going away 를 받으면 스스로 재연결하고(이슈 #34),
                    // 이어 붙일 수 없을 때만 여기로 온다. SDK 경로는 늘 여기로 온다.
                    onGoAway = { timeLeft ->
                        Log.w(TAG, "서버 종료 통지 수신 — 경과 ${_uiState.value.elapsedSeconds}초, timeLeft=$timeLeft")
                        endCall("서버에서 연결을 종료했어요.")
                    },
                )
                // startAudioConversation() 은 시작만 하고 즉시 반환한다.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "음성 대화 실패", e)
                exitCommunicationAudio()
                CallService.stop(getApplication())
                _uiState.update {
                    it.copy(phase = CallPhase.Failed(e.message ?: "대화를 시작하지 못했습니다"))
                }
            }
        }

        startTimer()
    }

    /** 사용자가 끊었을 때. */
    fun endCall() = endCall(reason = null)

    private fun endCall(reason: String?) {
        timerJob?.cancel(); timerJob = null
        conversationJob?.cancel(); conversationJob = null
        setMuted(false)
        exitCommunicationAudio()
        CallService.stop(getApplication())

        viewModelScope.launch {
            liveSession.stopConversation()
            liveSession.disconnect()
        }

        _uiState.update {
            CallUiState(
                appCheck = it.appCheck,
                phase = CallPhase.Idle,
                endedReason = reason,
                showTranscript = it.showTranscript,
            )
        }
        Log.i(TAG, "통화 종료 — ${reason ?: "사용자 종료"}")
    }

    /** 연결 실패 화면에서 대기 화면으로 돌아간다. */
    fun dismissError() {
        _uiState.update { CallUiState(appCheck = it.appCheck, showTranscript = it.showTranscript) }
    }

    // ---- 시간 관리 ---------------------------------------------------------

    /**
     * 1초마다 경과 시간을 올리며 상한과 침묵을 감시한다.
     *
     * 상한에 닿아도 갑자기 끊지 않는다 — 5분 전과 1분 전에 화면으로 미리 알린다.
     * 침묵은 상담에서 의미 있는 시간이라 [AiConfig.Session.SILENCE_NOTICE_SEC] 까지는
     * 아무것도 하지 않고, 그 뒤에도 확인만 한다.
     */
    private fun startTimer() {
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = _uiState.value
                if (current.phase !is CallPhase.InCall) break

                val elapsed = current.elapsedSeconds + 1
                val silentFor = elapsed - lastActivitySec

                // 이슈 #24: 세션이 아무 예외 없이 죽는 경우가 있다.
                // 화면만 "통화 중"인 채로 사용자가 계속 말하는 상황을 막으려면 직접 확인해야 한다.
                if (liveSession.isConversationAlive() == false) {
                    Log.w(
                        TAG,
                        "대화가 예고 없이 종료됨 — 경과 ${elapsed}초, " +
                            "마지막 발화 이후 ${silentFor}초, 최근 전사=\"${current.outputTranscript.take(40)}\""
                    )
                    endCall("연결이 끊겼어요. 다시 통화해 주세요.")
                    return@launch
                }

                // 세션은 살아 있는데 모델만 응답을 멈춘 경우 (이슈 #24).
                // 생존 확인으로는 잡히지 않으므로 "말했는데 답이 없다"를 직접 본다.
                val awaitingReply = lastUserSpeechSec >= 0 && lastModelReplySec < lastUserSpeechSec
                if (awaitingReply && elapsed - lastUserSpeechSec >= AiConfig.Session.NO_REPLY_TIMEOUT_SEC) {
                    Log.w(
                        TAG,
                        "모델 무응답 — 경과 ${elapsed}초, 마지막 발화 ${lastUserSpeechSec}초, " +
                            "마지막 응답 ${lastModelReplySec}초, 세션 생존=${liveSession.isConversationAlive()}"
                    )
                    endCall("응답이 오지 않아 통화를 마쳤어요. 다시 통화해 주세요.")
                    return@launch
                }

                when {
                    elapsed >= AiConfig.Session.MAX_DURATION_SEC -> {
                        endCall("30분이 지나 통화를 마쳤어요.")
                        return@launch
                    }

                    silentFor >= AiConfig.Session.SILENCE_END_SEC -> {
                        endCall("한동안 말씀이 없어 통화를 마쳤어요.")
                        return@launch
                    }
                }

                val notice = when {
                    silentFor >= AiConfig.Session.SILENCE_NOTICE_SEC -> CallNotice.StillThere
                    elapsed >= AiConfig.Session.FINAL_WARN_AT_SEC -> CallNotice.EndingSoon
                    elapsed >= AiConfig.Session.WARN_AT_SEC -> CallNotice.FiveMinutesLeft
                    else -> null
                }

                _uiState.update { it.copy(elapsedSeconds = elapsed, notice = notice) }
            }
        }
    }

    // ---- 마이크 뮤트 -------------------------------------------------------

    fun toggleMute() = setMuted(!_uiState.value.isMuted)

    private fun setMuted(muted: Boolean) {
        runCatching { audioManager.isMicrophoneMute = muted }
            .onFailure { Log.w(TAG, "마이크 뮤트 전환 실패", it) }
        _uiState.update { it.copy(isMuted = muted) }
    }

    // ---- 오디오 경로 (이슈 #12) --------------------------------------------

    /** 통화 모드로 들어가기 전의 오디오 모드. null 이면 통화 모드가 아니다. */
    private var previousAudioMode: Int? = null

    /**
     * 통화 중에만 `MODE_IN_COMMUNICATION` 으로 전환하고 출력 장치를 고른다.
     *
     * 통화 모드에서 폰은 기본적으로 수화부(귀에 대는 곳)로 소리를 낸다. 화면을 보며 쓰는 앱이라
     * 이어폰이 없으면 스피커로 보낸다. 이어폰이 있으면 이어폰으로 보낸다 — 블루투스는 통화
     * 프로필(SCO)로 바뀌어 음질은 떨어지지만 이어폰 마이크를 쓰게 된다 (이슈 #27).
     */
    private fun enterCommunicationAudio() {
        if (!AiConfig.Audio.USE_COMMUNICATION_ROUTE || previousAudioMode != null) return

        runCatching {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val candidates = audioManager.availableCommunicationDevices
                val target = HEADSET_TYPES.firstNotNullOfOrNull { type -> candidates.firstOrNull { it.type == type } }
                    ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val applied = target != null && audioManager.setCommunicationDevice(target)
                Log.i(
                    TAG,
                    "오디오 경로 — mode=IN_COMMUNICATION, 선택=${target?.let(::deviceName)}, 적용=$applied, " +
                        "후보=${candidates.joinToString { deviceName(it) }}"
                )
            } else {
                // API 30 이하는 검증 기기가 없어 실측하지 못했다. 블루투스 SCO 는 startBluetoothSco() 가
                // 따로 필요해 여기서는 다루지 않는다.
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasHeadset = outputs.any { it.type in HEADSET_TYPES }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = !hasHeadset
                Log.i(TAG, "오디오 경로 — mode=IN_COMMUNICATION, 스피커=${!hasHeadset} (API ${Build.VERSION.SDK_INT})")
            }
        }.onFailure { Log.w(TAG, "오디오 경로 전환 실패", it) }
    }

    /** 통화 모드를 풀고 원래 모드로 돌린다. 통화 모드가 아니면 아무것도 하지 않는다. */
    private fun exitCommunicationAudio() {
        val previous = previousAudioMode ?: return
        previousAudioMode = null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previous
            Log.i(TAG, "오디오 경로 복구 — mode=$previous")
        }.onFailure { Log.w(TAG, "오디오 경로 복구 실패", it) }
    }

    private fun deviceName(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "스피커"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화부"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선헤드셋"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "유선이어폰"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB헤드셋"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스SCO(${device.productName})"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE헤드셋(${device.productName})"
        else -> "type${device.type}"
    }

    // ---- 마이크 단독 검증 (개발용) ----------------------------------------

    fun startMicProbe() {
        if (_uiState.value.micProbe.isRecording) return
        _uiState.update {
            it.copy(micProbe = MicProbeState(isRecording = true, message = "🎙 녹음 중"))
        }

        var chunkCount = 0
        micProbeJob = viewModelScope.launch {
            try {
                AudioRecorder().start().collect { chunk ->
                    _uiState.update { it.copy(micProbe = it.micProbe.copy(amplitude = chunk.peakAmplitude)) }
                    if (chunkCount++ % 5 == 0) {
                        Log.d(TAG, "진폭=${chunk.peakAmplitude} (청크 ${chunk.pcm.size}바이트)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "오디오 캡처 실패", e)
                _uiState.update { it.copy(micProbe = MicProbeState(message = "캡처 실패: ${e.message}")) }
            }
        }
    }

    fun stopMicProbe() {
        micProbeJob?.cancel()
        micProbeJob = null
        _uiState.update { it.copy(micProbe = MicProbeState()) }
    }

    fun onMicPermissionDenied() {
        _uiState.update {
            it.copy(phase = CallPhase.Failed("마이크 권한이 필요해요. 설정에서 허용한 뒤 다시 시도해주세요."))
        }
        Log.w(TAG, "RECORD_AUDIO 권한 거부됨")
    }

    // ---- 정리 -------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        runCatching { audioManager.isMicrophoneMute = false }
        exitCommunicationAudio()
        CallService.stop(getApplication())
        // viewModelScope 는 이 시점에 이미 취소되므로 별도 스코프에서 정리한다.
        if (liveSession.isConnected) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                liveSession.stopConversation()
                liveSession.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
        private const val PREFS_NAME = "app"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

        /** 통화 출력으로 스피커보다 먼저 고르는 장치. 앞에 있을수록 우선한다. */
        private val HEADSET_TYPES = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
