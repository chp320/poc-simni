package com.leo.voicecounselpoc

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.liveGenerationConfig

/**
 * Gemini Live API 세션의 연결·해제만 담당한다 (Phase 0 3단계).
 *
 * 오디오 송수신은 여기서 하지 않는다 — 4~5단계 범위다.
 *
 * 백엔드는 Gemini Developer API(`GenerativeBackend.googleAI()`)를 쓴다.
 * 모델명은 [AiConfig.LIVE_MODEL_NAME]에서 오며, preview 모델이 연결되지 않으면
 * [AiConfig.FALLBACK_LIVE_MODEL_NAME]으로 바꿔 재시도한다.
 */
@OptIn(PublicPreviewAPI::class)
class LiveSessionManager {

    private var session: LiveSession? = null

    /** 세션이 열려 있는지. [LiveSession] 타입을 밖으로 내보내지 않기 위한 창구다. */
    val isConnected: Boolean
        get() = session != null

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
            }
        )

        session = model.connect()
        Log.i(TAG, "Live 세션 연결 성공 — 모델=$modelName")
    }

    /** 세션을 닫는다. 열려 있지 않으면 아무것도 하지 않는다. */
    suspend fun disconnect() {
        val open = session ?: return
        session = null
        runCatching { open.close() }
            .onSuccess { Log.i(TAG, "Live 세션 종료") }
            .onFailure { Log.w(TAG, "Live 세션 종료 중 오류", it) }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
    }
}
