package com.leo.voicecounselpoc

import android.Manifest
import android.media.AudioAttributes
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.activityDetectionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveAudioConversationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.realtimeInputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live API 세션의 연결·해제와 음성 대화를 담당한다.
 *
 * 대화 경로가 둘이다 ([AiConfig.Audio.USE_DIRECT_AUDIO]):
 * - **직접 경로** (기본, 이슈 #34): 오디오는 [DirectAudioEngine], 서버 메시지는 여기서 `receive()` 로
 *   직접 받는다. 재개 핸들을 저장해 두었다가 going away 가 오면 `resumeSession` 으로 이어 붙여
 *   약 10분 연결 수명을 넘긴다.
 * - **SDK 경로** (되돌림용): [LiveSession.startAudioConversation] 이 전부 처리한다. 재개 핸들을
 *   SDK 가 버려서 약 10분에 통화가 끝난다.
 *
 * [LiveSession] 타입은 이 클래스 밖으로 내보내지 않는다 — 내보내면 `@PublicPreviewAPI`
 * opt-in이 호출부까지 전염된다.
 */
@OptIn(PublicPreviewAPI::class)
class LiveSessionManager {

    private var session: LiveSession? = null

    // ---- 직접 경로 상태 ----------------------------------------------------

    private var engine: DirectAudioEngine? = null
    private var receiveScope: CoroutineScope? = null
    private var receiveJob: Job? = null

    /**
     * 서버가 가장 최근에 알려준 재개 핸들.
     *
     * 서버는 대화 중 수시로 `LiveSessionResumptionUpdate` 를 보낸다. 핸들은 "여기까지의 대화 상태"를
     * 가리키는 표식이고, 이것을 들고 재연결하면 서버가 맥락을 복원한다. 연결 종료 후 약 2시간 유효.
     * 모델이 말하는 도중처럼 재개할 수 없는 시점에는 `resumable=false` 로 오므로 그때는 갱신하지 않는다.
     */
    @Volatile private var resumptionHandle: String? = null

    /**
     * 지금 재연결 중인지. going away 가 **두 번** 오므로(실측) 연결당 한 번만 재연결하도록 막는다.
     */
    private val resuming = AtomicBoolean(false)

    /** 이번 통화에서 재연결한 횟수. 로그용. */
    private var resumeCount = 0

    /** 세션이 열려 있는지. */
    val isConnected: Boolean
        get() = session != null

    /**
     * 음성 대화가 실제로 살아 있는지.
     *
     * 이슈 #24: 세션이 아무 예외 없이 죽는 경우가 있어 주기적으로 확인해야 한다.
     * 세션을 연 적이 없으면 false 가 아니라 null 을 돌려준다 — "죽었다"와
     * "아직 시작 안 했다"를 호출부가 구분할 수 있어야 하기 때문이다.
     *
     * 직접 경로에서는 수신 루프와 오디오 엔진이 모두 살아 있어야 한다. 재연결 중에는 소켓이 잠깐
     * 바뀌지만 수신 루프는 계속 돌기 때문에 끊긴 것으로 보지 않는다.
     */
    fun isConversationAlive(): Boolean? {
        val open = session ?: return null
        if (!AiConfig.Audio.USE_DIRECT_AUDIO) return open.isAudioConversationActive()
        if (receiveJob == null) return null
        return receiveJob?.isActive == true && engine?.isRunning == true
    }

    /**
     * Live 세션을 연다. 이미 열려 있으면 먼저 닫고 다시 연결한다.
     * 실패 시 예외를 그대로 던진다 — 호출부에서 모델명 전환을 판단해야 하기 때문이다.
     */
    suspend fun connect(modelName: String) {
        disconnect()
        val direct = AiConfig.Audio.USE_DIRECT_AUDIO

        Log.i(TAG, "Live 세션 연결 시도 — 모델=$modelName, 직접 오디오=$direct")
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelName,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO

                // 양쪽 전사를 켜야 transcriptHandler 로 텍스트가 들어온다.
                // 한국어 인식/발화 품질 기록에 필요하다.
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()

                // 턴 종료 판단 (이슈 #22).
                // 말 시작은 민감하게(HIGH) 잡아 끼어들기를 유지하고,
                // 말 끝은 둔감하게(LOW) 잡아 생각하는 시간을 끊지 않는다.
                realtimeInputConfig = realtimeInputConfig {
                    automaticActivityDetection = activityDetectionConfig {
                        startSensitivity = ActivityDetectionConfig.Sensitivity.HIGH
                        endSensitivity = ActivityDetectionConfig.Sensitivity.LOW
                        silenceDurationMs = AiConfig.TurnDetection.SILENCE_DURATION_MS
                        prefixPaddingMs = AiConfig.TurnDetection.PREFIX_PADDING_MS
                    }
                    // NO_INTERRUPT 로 두면 끼어들기가 죽는다. Phase 0에서 확인된
                    // "즉시 중단"을 유지해야 하므로 INTERRUPT 를 명시한다.
                    activityHandling = RealtimeInputConfig.ActivityHandling.INTERRUPT
                }

                // 이슈 #34: 오디오 세션 15분·컨텍스트 128k 제한을 넘기려면 서버가 오래된 맥락을 줄여야 한다.
                if (direct) {
                    contextWindowCompression = ContextWindowCompressionConfig(
                        triggerTokens = AiConfig.LongCall.COMPRESSION_TRIGGER_TOKENS,
                        slidingWindow = SlidingWindow(targetTokens = AiConfig.LongCall.COMPRESSION_TARGET_TOKENS),
                    )
                }
            },
            tools = if (direct && AiConfig.Tools.ENABLED) listOf(Tool.functionDeclarations(TOOL_DECLARATIONS)) else null,
            systemInstruction = content { text(SystemInstruction.COUNSELING) }
        )

        // 빈 SessionResumptionConfig 는 "재개 기능을 켠다"는 뜻이다. 이걸 넣어야 서버가 핸들을 보내준다.
        session = if (direct) model.connect(SessionResumptionConfig()) else model.connect()
        resumptionHandle = null
        resuming.set(false)
        resumeCount = 0
        Log.i(
            TAG,
            "Live 세션 연결 성공 — 모델=$modelName, " +
                "silenceDurationMs=${AiConfig.TurnDetection.SILENCE_DURATION_MS}, " +
                "endSensitivity=LOW, 직접 오디오=$direct"
        )
    }

    /**
     * 음성 대화를 시작한다. 경로는 [AiConfig.Audio.USE_DIRECT_AUDIO] 로 고른다.
     *
     * 주의: 이 함수는 대화를 "시작"만 하고 즉시 반환한다. 대화는 백그라운드에서 계속 돌아가며,
     * [stopConversation] 을 호출해야 끝난다. 반환을 대화 종료로 해석하면 안 된다.
     *
     * 호출 전에 `RECORD_AUDIO` 권한과 [connect]가 선행되어야 한다.
     *
     * @param onTranscript (내가 말한 내용, 모델이 말한 내용) — 둘 중 하나만 올 수 있다.
     * @param onGoAway 서버가 연결을 끝내겠다고 알렸는데 **이어 붙일 수 없을 때** 불린다.
     *   직접 경로에서는 재연결에 성공하면 불리지 않는다.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startConversation(
        onTranscript: (input: String?, output: String?) -> Unit,
        onGoAway: (String) -> Unit = {},
        onModelConcern: (CrisisLevel) -> Unit = {},
    ) {
        if (AiConfig.Audio.USE_DIRECT_AUDIO) {
            startDirectConversation(onTranscript, onGoAway, onModelConcern)
        } else {
            startSdkConversation(onTranscript, onGoAway)
        }
    }

    // ---- 직접 경로 ---------------------------------------------------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startDirectConversation(
        onTranscript: (input: String?, output: String?) -> Unit,
        onGoAway: (String) -> Unit,
        onModelConcern: (CrisisLevel) -> Unit,
    ) {
        val open = checkNotNull(session) { "세션이 연결되지 않았습니다. connect() 를 먼저 호출하세요." }
        Log.i(TAG, "음성 대화 시작 (직접 오디오, 끼어들기 활성화, 통화 경로=${AiConfig.Audio.USE_COMMUNICATION_ROUTE})")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiveScope = scope

        // 1) 서버 메시지 수신 루프를 먼저 띄운다 — 오디오를 보내기 시작하면 곧바로 응답이 올 수 있다.
        receiveJob = scope.launch {
            try {
                open.receive().collect { message ->
                    when (message) {
                        is LiveServerContent -> handleContent(message, onTranscript)
                        is LiveSessionResumptionUpdate -> handleResumptionUpdate(message)
                        is LiveServerGoAway -> handleGoAway(message, open, scope, onGoAway)
                        is LiveServerToolCall -> handleToolCall(message, open, onModelConcern)
                        else -> Unit
                    }
                }
                Log.w(TAG, "서버 수신 종료 — 연결이 닫혔다 (재연결 ${resumeCount}회)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "서버 수신 오류 — 대화 종료", e)
            }
        }

        // 2) 마이크를 열고 읽는 족족 서버로 보낸다.
        //    resumeSession 으로 소켓이 바뀌어도 LiveSession 이 새 소켓으로 보내준다.
        val newEngine = DirectAudioEngine()
        engine = newEngine
        try {
            newEngine.start { pcm ->
                if (!open.isClosed()) open.sendAudioRealtime(InlineData(pcm, "audio/pcm"))
            }
        } catch (e: Exception) {
            // 마이크·스피커를 못 열었으면 띄워둔 수신 루프도 정리하고 호출부에 알린다.
            stopConversation()
            throw e
        }
    }

    /** 서버의 대화 내용 메시지: 전사, 모델 목소리, 끼어들기 신호. */
    private fun handleContent(
        message: LiveServerContent,
        onTranscript: (input: String?, output: String?) -> Unit,
    ) {
        val inputText = message.inputTranscription?.text?.takeIf { it.isNotBlank() }
        val outputText = message.outputTranscription?.text?.takeIf { it.isNotBlank() }
        if (inputText != null) Log.i(TAG, "[내 말] $inputText")
        if (outputText != null) Log.i(TAG, "[모델] $outputText")
        if (inputText != null || outputText != null) onTranscript(inputText, outputText)

        if (message.interrupted) {
            // 사용자가 끼어들었다 — 모델이 하던 말을 즉시 끊는다 (이슈 #26).
            engine?.clearPlayback()
            return
        }
        message.content?.parts
            ?.filterIsInstance<InlineDataPart>()
            ?.forEach { engine?.enqueuePlayback(it.inlineData) }
    }

    /**
     * 모델이 도구(함수)를 호출했다 (이슈 #17 S5-2). 결과를 돌려줘야 모델이 이어서 말한다.
     *
     * 호출 인자는 로그에 이름과 레벨만 남긴다 — 이유 설명 같은 자유 텍스트는 대화 내용이라 남기지 않는다 (#35).
     */
    private suspend fun handleToolCall(
        message: LiveServerToolCall,
        open: LiveSession,
        onModelConcern: (CrisisLevel) -> Unit,
    ) {
        val responses = message.functionCalls.map { call ->
            when (call.name) {
                TOOL_LOCAL_TIME -> {
                    val now = ZonedDateTime.now()
                    Log.i(TAG, "도구 호출: $TOOL_LOCAL_TIME")
                    FunctionResponsePart(call.name, buildJsonObject {
                        put("local_time", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE a h시 m분", java.util.Locale.KOREAN)))
                        put("timezone", now.zone.id)
                    }, call.id)
                }
                TOOL_REPORT_CONCERN -> {
                    val level = parseConcernLevel(call)
                    Log.w(TAG, "도구 호출: $TOOL_REPORT_CONCERN — 모델 판단 레벨=$level")
                    onModelConcern(level)
                    FunctionResponsePart(call.name, buildJsonObject { put("acknowledged", true) }, call.id)
                }
                else -> {
                    Log.w(TAG, "알 수 없는 도구 호출: ${call.name}")
                    FunctionResponsePart(call.name, buildJsonObject { put("error", "unknown function") }, call.id)
                }
            }
        }
        runCatching { open.sendFunctionResponse(responses) }
            .onFailure { Log.w(TAG, "도구 응답 전송 실패", it) }
    }

    private fun parseConcernLevel(call: FunctionCallPart): CrisisLevel {
        val raw = runCatching { call.args["level"]?.jsonPrimitive?.content }.getOrNull()
        return when (raw) {
            "concern" -> CrisisLevel.CONCERN
            "explicit" -> CrisisLevel.EXPLICIT
            "imminent" -> CrisisLevel.IMMINENT
            // 알 수 없는 값이 오면 놓치지 않는 쪽으로 — 우려 신호로 본다.
            else -> CrisisLevel.CONCERN
        }
    }

    private fun handleResumptionUpdate(message: LiveSessionResumptionUpdate) {
        val handle = message.newHandle
        if (message.resumable == true && !handle.isNullOrEmpty()) {
            val first = resumptionHandle == null
            resumptionHandle = handle
            // 핸들은 대화 상태를 되살리는 열쇠라 값 자체는 로그에 남기지 않는다.
            if (first) Log.i(TAG, "재개 핸들 수신 — 이제 연결이 끝나도 이어 붙일 수 있다")
        }
    }

    /**
     * 서버가 곧 연결을 끊겠다고 알렸다 (실측: 종료 약 50초 전, 같은 알림이 2번).
     * 첫 알림에서 한 번만 재개 핸들로 이어 붙인다.
     */
    private fun handleGoAway(
        message: LiveServerGoAway,
        open: LiveSession,
        scope: CoroutineScope,
        onGoAway: (String) -> Unit,
    ) {
        val timeLeft = message.timeLeft.toString()
        if (!resuming.compareAndSet(false, true)) {
            Log.i(TAG, "서버 종료 통지 중복 — 이미 재연결 중이라 무시 (timeLeft=$timeLeft)")
            return
        }

        val handle = resumptionHandle
        if (handle == null) {
            Log.w(TAG, "서버 종료 통지 — 재개 핸들이 없어 이어 붙일 수 없다 (timeLeft=$timeLeft)")
            onGoAway(timeLeft)
            return
        }

        Log.i(TAG, "서버 종료 통지 — 재연결 시작 (timeLeft=$timeLeft)")
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                open.resumeSession(SessionResumptionConfig(handle))
                resumeCount++
                Log.i(TAG, "재연결 성공 — ${System.currentTimeMillis() - startedAt}ms, 이번 통화 ${resumeCount}회째")
                // 새 연결의 going away 를 다시 받을 수 있게 푼다.
                resuming.set(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "재연결 실패 — 통화를 이어갈 수 없다", e)
                onGoAway(timeLeft)
            }
        }
    }

    // ---- SDK 경로 (되돌림용) ----------------------------------------------

    private suspend fun startSdkConversation(
        onTranscript: (input: String?, output: String?) -> Unit,
        onGoAway: (String) -> Unit,
    ) {
        val open = checkNotNull(session) { "세션이 연결되지 않았습니다. connect() 를 먼저 호출하세요." }

        Log.i(TAG, "음성 대화 시작 (SDK 오디오, 끼어들기 활성화, 통화 경로=${AiConfig.Audio.USE_COMMUNICATION_ROUTE})")
        open.startAudioConversation(
            liveAudioConversationConfig {
                // Phase 0 완료 기준에 끼어들기 동작 확인이 포함되어 있다.
                enableInterruptions = true
                // 이슈 #12: SDK 는 출력을 USAGE_MEDIA 로 만든다. build() 직전에 불리므로
                // 여기서 덮어쓰면 출력이 통화 경로로 가고 에코 캔슬러가 스피커 소리를 참조할 수 있다.
                // 오디오 모드 전환(MODE_IN_COMMUNICATION)은 CallViewModel 이 맡는다.
                if (AiConfig.Audio.USE_COMMUNICATION_ROUTE) {
                    initializationHandler = { _, trackBuilder ->
                        trackBuilder.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                }
                transcriptHandler = { input, output ->
                    val inputText = input?.text?.takeIf { it.isNotBlank() }
                    val outputText = output?.text?.takeIf { it.isNotBlank() }
                    if (inputText != null) Log.i(TAG, "[내 말] $inputText")
                    if (outputText != null) Log.i(TAG, "[모델] $outputText")
                    onTranscript(inputText, outputText)
                }
                // 서버가 세션을 닫겠다고 알려오는 경로. 이걸 설정하지 않아서
                // 이슈 #24 (세션이 조용히 죽음)를 감지하지 못했다.
                goAwayHandler = { goAway ->
                    val detail = goAway.timeLeft.toString()
                    Log.w(TAG, "서버가 세션 종료를 통지 — timeLeft=$detail")
                    onGoAway(detail)
                }
            }
        )
        Log.i(TAG, "음성 대화 시작됨 — active=${open.isAudioConversationActive()}")
    }

    // ---- 종료 --------------------------------------------------------------

    /** 음성 대화만 중지한다. 세션은 유지된다. */
    fun stopConversation() {
        engine?.stop(); engine = null
        receiveJob?.cancel(); receiveJob = null
        receiveScope?.cancel(); receiveScope = null
        session?.let { open ->
            if (AiConfig.Audio.USE_DIRECT_AUDIO) runCatching { open.stopReceiving() }
            else open.stopAudioConversation()
        }
        Log.i(TAG, "음성 대화 중지 요청")
    }

    /** 세션을 닫는다. 열려 있지 않으면 아무것도 하지 않는다. */
    suspend fun disconnect() {
        val open = session ?: return
        stopConversation()
        session = null
        resumptionHandle = null
        runCatching { open.close() }
            .onSuccess { Log.i(TAG, "Live 세션 종료") }
            .onFailure { Log.w(TAG, "Live 세션 종료 중 오류", it) }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"

        private const val TOOL_LOCAL_TIME = "get_local_time"
        private const val TOOL_REPORT_CONCERN = "report_concern"

        /**
         * 모델에게 주는 도구 목록.
         *
         * - `get_local_time`: 모델은 기기 시각을 모른다(UTC 로 답하던 문제, #33). 위기와 무관해 도구 호출이
         *   동작하는지 먼저 확인하는 용도도 겸한다.
         * - `report_concern`: 2계층 위기 감지 (#17). 모델이 대화에서 위기 신호를 느끼면 조용히 호출한다.
         *
         * 설명문(description)은 영어로 쓴다 — 모델에게만 보이는 기계용 설명이고, 한국어 지시문에 섞이면
         * 발화로 새어 나올 위험(#16 원칙)을 줄인다.
         */
        private val TOOL_DECLARATIONS = listOf(
            FunctionDeclaration(
                name = TOOL_LOCAL_TIME,
                description = "Returns the current local date and time on the user's device, including time zone. " +
                    "Call this whenever the current time or date matters. Never guess the time.",
                parameters = emptyMap(),
            ),
            FunctionDeclaration(
                name = TOOL_REPORT_CONCERN,
                description = "Silently report a safety concern to the app so it can show crisis helpline information. " +
                    "Call it as soon as you notice any sign, and call again if it becomes more serious. " +
                    "Never mention this function or that you are reporting. Keep talking warmly as before. " +
                    "Levels: concern = hopelessness, helplessness, persistent isolation; " +
                    "explicit = any mention of self-harm or suicide or wanting to die or disappear; " +
                    "imminent = mentions a concrete method, plan, time, or preparation.",
                parameters = mapOf(
                    "level" to Schema.enumeration(listOf("concern", "explicit", "imminent"), "Severity of the concern"),
                ),
            ),
        )
    }
}
