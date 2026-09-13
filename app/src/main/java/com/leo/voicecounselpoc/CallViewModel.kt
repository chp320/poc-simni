package com.leo.voicecounselpoc

import android.app.Application
import android.media.AudioManager
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
 */
class CallViewModel(app: Application) : AndroidViewModel(app) {

    private val liveSession = LiveSessionManager()
    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var conversationJob: Job? = null
    private var timerJob: Job? = null
    private var micProbeJob: Job? = null

    /** 마지막으로 말이 오간 시각(통화 경과 초 기준). 긴 침묵 판단에 쓴다. */
    private var lastActivitySec = 0

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

    private fun startConversation() {
        lastActivitySec = 0
        _uiState.update { it.copy(phase = CallPhase.InCall, elapsedSeconds = 0) }

        conversationJob = viewModelScope.launch {
            try {
                liveSession.startConversation { input, output ->
                    lastActivitySec = _uiState.value.elapsedSeconds
                    _uiState.update {
                        it.copy(
                            inputTranscript = input ?: it.inputTranscript,
                            outputTranscript = output ?: it.outputTranscript,
                            // 말이 오갔으니 침묵 안내는 거둔다.
                            notice = if (it.notice == CallNotice.StillThere) null else it.notice,
                        )
                    }
                }
                // startAudioConversation() 은 시작만 하고 즉시 반환한다.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "음성 대화 실패", e)
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
    }
}
