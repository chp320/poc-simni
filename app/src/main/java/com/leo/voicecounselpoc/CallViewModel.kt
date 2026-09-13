package com.leo.voicecounselpoc

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 통화 상태와 로직의 단일 소유자.
 *
 * `MainActivity` 가 아니라 여기에 상태를 두는 이유는 **화면 회전에도 통화가 유지**되어야 하기
 * 때문이다. Activity 가 재생성되어도 ViewModel 은 살아남으므로 Live 세션이 끊기지 않는다.
 *
 * Context 가 필요한 일(권한 확인·요청)은 여기서 하지 않고 Activity 에 남긴다.
 */
class CallViewModel : ViewModel() {

    private val liveSession = LiveSessionManager()

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var conversationJob: Job? = null
    private var micProbeJob: Job? = null

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

    // ---- Live 세션 --------------------------------------------------------

    fun toggleSession() {
        val state = _uiState.value
        if (state.isTransitioning) return

        if (state.isSessionOpen) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun connect() {
        _uiState.update { it.copy(phase = CallPhase.Connecting) }
        viewModelScope.launch {
            try {
                liveSession.connect(AiConfig.LIVE_MODEL_NAME)
                _uiState.update { it.copy(phase = CallPhase.Connected) }
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

    private fun disconnect() {
        conversationJob?.cancel()
        conversationJob = null
        viewModelScope.launch {
            liveSession.stopConversation()
            liveSession.disconnect()
            _uiState.update {
                it.copy(phase = CallPhase.Idle, inputTranscript = "", outputTranscript = "")
            }
        }
    }

    // ---- 음성 대화 --------------------------------------------------------

    /** 호출 전에 `RECORD_AUDIO` 권한이 허용되어 있어야 한다. */
    fun startConversation() {
        if (_uiState.value.isInCall) return

        _uiState.update {
            it.copy(phase = CallPhase.InCall, inputTranscript = "", outputTranscript = "")
        }

        conversationJob = viewModelScope.launch {
            try {
                liveSession.startConversation { input, output ->
                    _uiState.update {
                        it.copy(
                            inputTranscript = input ?: it.inputTranscript,
                            outputTranscript = output ?: it.outputTranscript,
                        )
                    }
                }
                // startAudioConversation() 은 시작만 하고 즉시 반환한다.
                // 대화는 stopConversation() 을 부를 때까지 계속된다.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "음성 대화 실패", e)
                _uiState.update {
                    it.copy(phase = CallPhase.Failed(e.message ?: "대화를 시작하지 못했습니다"))
                }
            }
        }
    }

    fun stopConversation() {
        if (!_uiState.value.isInCall) return
        conversationJob?.cancel()
        conversationJob = null
        liveSession.stopConversation()
        _uiState.update { it.copy(phase = CallPhase.Connected) }
    }

    // ---- 마이크 단독 검증 (S2에서 제거 예정) ------------------------------

    fun startMicProbe() {
        if (_uiState.value.micProbe.isRecording) return
        _uiState.update {
            it.copy(micProbe = MicProbeState(isRecording = true, message = "🎙 녹음 중 — 말해보세요"))
        }
        Log.i(TAG, "오디오 캡처 시작 (${AudioRecorder.SAMPLE_RATE}Hz / 16-bit / mono)")

        var chunkCount = 0
        micProbeJob = viewModelScope.launch {
            try {
                AudioRecorder().start().collect { chunk ->
                    _uiState.update { it.copy(micProbe = it.micProbe.copy(amplitude = chunk.peakAmplitude)) }
                    // 청크는 100ms마다 오므로 로그는 약 0.5초에 한 번만 남긴다.
                    if (chunkCount++ % 5 == 0) {
                        Log.d(TAG, "진폭=${chunk.peakAmplitude} (청크 ${chunk.pcm.size}바이트)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "오디오 캡처 실패", e)
                _uiState.update {
                    it.copy(micProbe = MicProbeState(message = "캡처 실패: ${e.message}"))
                }
            }
        }
    }

    fun stopMicProbe() {
        micProbeJob?.cancel()
        micProbeJob = null
        _uiState.update { it.copy(micProbe = MicProbeState(message = "마이크 정지됨")) }
        Log.i(TAG, "오디오 캡처 정지")
    }

    fun onMicPermissionDenied() {
        _uiState.update {
            it.copy(micProbe = it.micProbe.copy(message = "마이크 권한이 거부되었습니다. 설정에서 허용 후 다시 시도하세요."))
        }
        Log.w(TAG, "RECORD_AUDIO 권한 거부됨")
    }

    // ---- 정리 -------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
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
