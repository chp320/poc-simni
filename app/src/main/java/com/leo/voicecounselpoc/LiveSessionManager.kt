package com.leo.voicecounselpoc

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveAudioConversationConfig
import com.google.firebase.ai.type.liveGenerationConfig

/**
 * Gemini Live API 세션의 연결·해제와 음성 대화를 담당한다 (Phase 0 3~5단계).
 *
 * 오디오 송수신과 스피커 재생은 [LiveSession.startAudioConversation]이 내부에서 전부 처리한다.
 * 우리가 만든 [AudioRecorder]는 이 경로에서 쓰이지 않지만, 커스텀 스트리밍이 필요해질 때의
 * 출발점으로 남겨둔다.
 *
 * [LiveSession] 타입은 이 클래스 밖으로 내보내지 않는다 — 내보내면 `@PublicPreviewAPI`
 * opt-in이 호출부까지 전염된다.
 */
@OptIn(PublicPreviewAPI::class)
class LiveSessionManager {

    private var session: LiveSession? = null

    /** 세션이 열려 있는지. */
    val isConnected: Boolean
        get() = session != null

    /** 음성 대화가 진행 중인지. */
    val isConversationActive: Boolean
        get() = session?.isAudioConversationActive() == true

    /**
     * Live 세션을 연다. 이미 열려 있으면 먼저 닫고 다시 연결한다.
     * 실패 시 예외를 그대로 던진다 — 호출부에서 모델명 전환을 판단해야 하기 때문이다.
     */
    suspend fun connect(modelName: String) {
        disconnect()

        Log.i(TAG, "Live 세션 연결 시도 — 모델=$modelName")
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelName,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                // 양쪽 전사를 켜야 transcriptHandler 로 텍스트가 들어온다.
                // Phase 0의 "한국어 인식/발화 품질 기록"에 필요하다.
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            },
            systemInstruction = content {
                text(
                    "당신은 한국어로 대화하는 상담 도우미입니다. " +
                        "항상 한국어로, 한두 문장으로 짧고 자연스럽게 응답하세요."
                )
            }
        )

        session = model.connect()
        Log.i(TAG, "Live 세션 연결 성공 — 모델=$modelName")
    }

    /**
     * 음성 대화를 시작한다. 마이크 캡처 → 전송 → 응답 수신 → 스피커 재생을 SDK가 처리한다.
     *
     * 주의: 이 함수는 대화를 "시작"만 하고 즉시 반환한다 (실측 74ms). 대화는 SDK 내부 스코프에서
     * 계속 돌아가며, [stopConversation] 을 호출해야 끝난다. 반환을 대화 종료로 해석하면 안 된다.
     *
     * 호출 전에 `RECORD_AUDIO` 권한과 [connect]가 선행되어야 한다.
     *
     * @param onTranscript (내가 말한 내용, 모델이 말한 내용) — 둘 중 하나만 올 수 있다.
     */
    suspend fun startConversation(onTranscript: (input: String?, output: String?) -> Unit) {
        val open = checkNotNull(session) { "세션이 연결되지 않았습니다. connect() 를 먼저 호출하세요." }

        Log.i(TAG, "음성 대화 시작 (끼어들기 활성화)")
        open.startAudioConversation(
            liveAudioConversationConfig {
                // Phase 0 완료 기준에 끼어들기 동작 확인이 포함되어 있다.
                enableInterruptions = true
                transcriptHandler = { input, output ->
                    val inputText = input?.text?.takeIf { it.isNotBlank() }
                    val outputText = output?.text?.takeIf { it.isNotBlank() }
                    if (inputText != null) Log.i(TAG, "[내 말] $inputText")
                    if (outputText != null) Log.i(TAG, "[모델] $outputText")
                    onTranscript(inputText, outputText)
                }
            }
        )
        Log.i(TAG, "음성 대화 시작됨 — active=${open.isAudioConversationActive()}")
    }

    /** 음성 대화만 중지한다. 세션은 유지된다. */
    fun stopConversation() {
        session?.stopAudioConversation()
        Log.i(TAG, "음성 대화 중지 요청")
    }

    /** 세션을 닫는다. 열려 있지 않으면 아무것도 하지 않는다. */
    suspend fun disconnect() {
        val open = session ?: return
        session = null
        runCatching { open.stopAudioConversation() }
        runCatching { open.close() }
            .onSuccess { Log.i(TAG, "Live 세션 종료") }
            .onFailure { Log.w(TAG, "Live 세션 종료 중 오류", it) }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
    }
}
