package com.leo.voicecounselpoc

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 마이크와 스피커를 앱이 직접 다루는 오디오 엔진 (이슈 #34 A안).
 *
 * ## 왜 필요한가
 * 원래는 SDK 의 `LiveSession.startAudioConversation()` 한 줄이 마이크 녹음 → 서버 전송 →
 * 응답 수신 → 스피커 재생을 전부 해줬다. 편하지만 **서버 메시지를 SDK 가 혼자 받아서**,
 * 통화를 10분 넘게 이어가는 데 필요한 "재개 핸들"을 앱이 받을 수 없었다.
 * 그래서 오디오는 이 엔진이, 서버 메시지는 [LiveSessionManager] 가 직접 처리한다.
 *
 * ## 이 엔진이 하는 일 (서버와는 전혀 모른다)
 * ```
 *  [마이크] ──AudioRecord──▶ 녹음 루프 ──PCM 조각──▶ onMicChunk 콜백 (→ 서버로 전송은 호출자가)
 *
 *  enqueuePlayback(PCM) ──▶ 재생 큐(Channel) ──▶ 재생 루프 ──AudioTrack──▶ [스피커]
 *                                  ▲
 *  clearPlayback() ── 끼어들기 시 큐와 스피커 버퍼를 즉시 비운다
 * ```
 * SDK 타입(`LiveSession` 등)을 하나도 쓰지 않는다. 그래서 `@PublicPreviewAPI` 가 이 파일로
 * 번지지 않고, SDK 가 바뀌어도 이 파일은 영향을 받지 않는다.
 *
 * ## 오디오 기초 용어
 * - **PCM**: 소리를 압축하지 않고 "순간순간의 음압 값"을 숫자로 나열한 것. WAV 파일의 알맹이다.
 * - **샘플레이트 (Hz)**: 1초에 소리 값을 몇 번 기록하는지. 16000Hz = 1초에 16,000개.
 * - **16-bit**: 값 하나를 2바이트(-32768 ~ 32767)로 표현. 그래서 16kHz 모노 1초 = 32,000바이트.
 * - **모노 / 스테레오**: 채널 수. 음성 대화에는 모노 1채널이면 충분하다.
 *
 * Gemini Live API 약속: **보내는 소리는 16kHz**, **받는 소리는 24kHz**, 둘 다 16-bit 모노 PCM.
 * 설정은 SDK 내부 `AudioHelper.build()` 를 그대로 따랐다 (소스로 확인).
 */
class DirectAudioEngine {

    // ---- 상태 --------------------------------------------------------------

    /**
     * 이 엔진 전용 코루틴 스코프.
     *
     * 녹음과 재생은 "끝없이 도는 루프"라서 각자 스레드를 하나씩 붙잡는다.
     * `Dispatchers.IO` 는 이렇게 오래 막혀 있는(blocking) 작업을 위한 스레드 풀이다.
     * `SupervisorJob` 이라 녹음 루프가 실패해도 재생 루프까지 같이 죽지는 않는다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordJob: Job? = null
    private var playJob: Job? = null

    /**
     * 재생을 기다리는 PCM 조각들의 대기열.
     *
     * 서버는 말을 실제 재생 속도보다 **빠르게** 보내준다. 받자마자 스피커에 밀어 넣으면 소리가
     * 겹치므로, 큐에 쌓아두고 재생 루프가 하나씩 꺼내 순서대로 재생한다.
     * `UNLIMITED` 는 큐 크기 제한이 없다는 뜻 — 한 턴 분량은 수 초라 메모리 부담이 작다.
     *
     * 각 조각에 [generation] 번호를 붙여 넣는다. 이유는 [clearPlayback] 참고.
     */
    private val playbackQueue = Channel<Chunk>(Channel.UNLIMITED)

    /**
     * "몇 번째 발화 묶음인가" 번호. 끼어들기가 일어날 때마다 1 씩 올린다.
     *
     * 끼어들기 순간 이미 큐에서 꺼내져 재생 루프 손에 들린 조각이 있을 수 있다.
     * 번호가 현재 값과 다르면 "끼어들기 이전의 낡은 말"로 보고 버린다.
     * 여러 스레드가 동시에 읽고 쓰므로 `AtomicInteger` 를 쓴다.
     */
    private val generation = AtomicInteger(0)

    private class Chunk(val generation: Int, val pcm: ByteArray)

    /**
     * 마이크 입력의 최근 최대 진폭 (0 ~ 32767).
     *
     * SDK 경로에서는 마이크가 SDK 안에 숨어 있어 얻을 수 없던 값이다.
     * 입력이 끊겼는지 감지(#28)하거나 실제 음량 파형(#20)을 그릴 때 쓸 수 있다.
     */
    private val _inputLevel = MutableStateFlow(0)
    val inputLevel: StateFlow<Int> = _inputLevel.asStateFlow()

    /** 엔진이 동작 중인지. 녹음 루프가 살아 있는 동안 true. */
    val isRunning: Boolean
        get() = recordJob?.isActive == true

    // ---- 시작 / 종료 -------------------------------------------------------

    /**
     * 마이크와 스피커를 열고 녹음·재생 루프를 시작한다.
     *
     * 호출 전에 오디오 모드를 통화 모드(`MODE_IN_COMMUNICATION`)로 바꿔 두어야 에코 캔슬이
     * 제대로 붙는다 — 이 일은 [CallViewModel] 이 맡는다 (이슈 #12).
     *
     * @param onMicChunk 마이크에서 PCM 조각을 읽을 때마다 불린다. 녹음 스레드에서 호출되며,
     *   여기서 서버로 보내면 된다. 보내는 동안 다음 녹음이 밀리지 않도록 오래 걸리면 안 된다.
     * @throws IllegalStateException 마이크나 스피커를 열지 못했을 때
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onMicChunk: suspend (ByteArray) -> Unit) {
        check(recordJob == null) { "이미 시작된 엔진이다" }

        val newRecorder = createRecorder()
        val newTrack = createTrack()
        recorder = newRecorder
        track = newTrack

        enableEchoCanceler(newRecorder)

        newRecorder.startRecording()
        newTrack.play()

        recordJob = scope.launch { recordLoop(newRecorder, onMicChunk) }
        playJob = scope.launch { playLoop(newTrack) }
        Log.i(TAG, "직접 오디오 엔진 시작 — 녹음 ${INPUT_SAMPLE_RATE}Hz, 재생 ${OUTPUT_SAMPLE_RATE}Hz")
    }

    /** 루프를 멈추고 마이크·스피커를 반납한다. 여러 번 불러도 안전하다. */
    fun stop() {
        if (recorder == null && track == null) return

        // 1) 루프부터 멈춘다. 루프가 아직 read()/write() 중인 객체를 release 하면 크래시가 난다.
        recordJob?.cancel(); recordJob = null
        playJob?.cancel(); playJob = null

        // 2) 하드웨어 반납. stop() 은 이미 멈춘 상태면 예외를 던지므로 runCatching 으로 감싼다.
        recorder?.let { runCatching { it.stop() }; it.release() }
        track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        recorder = null
        track = null

        // 3) 남은 대기열을 비운다.
        drainQueue()
        scope.cancel()
        _inputLevel.value = 0
        Log.i(TAG, "직접 오디오 엔진 종료")
    }

    // ---- 재생 제어 ---------------------------------------------------------

    /**
     * 서버에서 받은 모델의 목소리(24kHz PCM)를 재생 대기열 맨 뒤에 넣는다.
     * 네트워크 수신 스레드에서 불러도 된다 — 실제 재생은 재생 루프가 한다.
     */
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        playbackQueue.trySend(Chunk(generation.get(), pcm))
    }

    /**
     * 사용자가 끼어들었을 때 모델의 말을 **즉시** 끊는다 (이슈 #26).
     *
     * SDK 경로는 대기열만 비웠다. 그런데 `AudioTrack` 안에는 이미 넘겨준 소리가 버퍼로 남아 있어
     * 그만큼 계속 재생됐고, 이것이 "끼어들어도 1~2초 뒤에 멈춘다"의 원인 중 하나였다.
     * 여기서는 세 겹으로 끊는다:
     *  1. 번호를 올려, 재생 루프가 들고 있던 낡은 조각을 버리게 한다
     *  2. 대기열에 남은 조각을 모두 버린다
     *  3. `pause()` → `flush()` 로 스피커 버퍼에 들어간 소리까지 지운 뒤 다시 `play()`
     */
    fun clearPlayback() {
        generation.incrementAndGet()
        drainQueue()
        track?.let { t ->
            runCatching {
                t.pause()   // 재생을 멈춰야 flush 가 동작한다
                t.flush()   // 아직 재생되지 않은 버퍼 내용을 버린다
                t.play()    // 다음 말을 바로 받을 수 있게 재생 상태로 되돌린다
            }.onFailure { Log.w(TAG, "재생 버퍼 비우기 실패", it) }
        }
    }

    // ---- 내부: 루프 --------------------------------------------------------

    /**
     * 녹음 루프 — 마이크에서 PCM 을 계속 읽어 콜백으로 넘긴다.
     *
     * `AudioRecord.read()` 는 요청한 만큼 소리가 모일 때까지 **스레드를 멈추고 기다린다**(blocking).
     * 그래서 이 루프는 IO 스레드 하나를 계속 붙잡고 있다. 코루틴 취소(`isActive == false`)로 빠져나온다.
     */
    private suspend fun CoroutineScope.recordLoop(recorder: AudioRecord, onMicChunk: suspend (ByteArray) -> Unit) {
        val buffer = ByteArray(INPUT_CHUNK_BYTES)
        while (isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read < 0) {
                // 음수는 오류 코드다 (예: ERROR_DEAD_OBJECT — 오디오 서버가 재시작됨).
                Log.w(TAG, "마이크 읽기 실패 — 코드 $read, 녹음 루프 종료")
                break
            }
            if (read == 0) continue

            val chunk = buffer.copyOf(read)   // 버퍼는 재사용하므로 넘길 때는 복사본을 만든다
            _inputLevel.value = peakAmplitude(chunk)
            runCatching { onMicChunk(chunk) }
                .onFailure { Log.w(TAG, "마이크 조각 전달 실패", it) }
        }
    }

    /**
     * 재생 루프 — 대기열에서 조각을 꺼내 스피커에 쓴다.
     *
     * `receive()` 는 대기열이 비어 있으면 조각이 올 때까지 코루틴을 **일시 정지**한다
     * (스레드를 막지 않는다). `AudioTrack.write()` 는 스피커가 받아줄 때까지 스레드를 막는다.
     */
    private suspend fun playLoop(track: AudioTrack) {
        for (chunk in playbackQueue) {
            // 끼어들기 이전에 들어온 조각이면 버린다 (clearPlayback 참고).
            if (chunk.generation != generation.get()) continue

            val written = track.write(chunk.pcm, 0, chunk.pcm.size)
            if (written < 0) {
                Log.w(TAG, "스피커 쓰기 실패 — 코드 $written")
            }
        }
    }

    // ---- 내부: 하드웨어 생성 ----------------------------------------------

    /**
     * 마이크 객체를 만든다.
     *
     * `VOICE_COMMUNICATION` 소스는 "통화용 마이크"다. 일반 `MIC` 와 달리 기기가 에코 캔슬·
     * 소음 억제 같은 통화용 전처리를 붙여준다. 스피커 소리가 마이크로 되돌아가 모델이
     * 자기 말에 끼어드는 문제(이슈 #12)를 막는 첫 단계다.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecorder(): AudioRecord {
        // 기기가 요구하는 최소 버퍼 크기. 이보다 작게 잡으면 생성이 실패한다.
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "마이크 버퍼 크기를 얻지 못했다 ($minBuffer)" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(INPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            // 내부 버퍼는 넉넉히(최소의 2배) — 전송이 잠깐 늦어도 소리를 흘리지 않도록.
            .setBufferSizeInBytes(maxOf(minBuffer * 2, INPUT_CHUNK_BYTES * 2))
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "마이크를 열지 못했다 — 다른 앱이 마이크를 쓰고 있을 수 있다"
        }
        return recorder
    }

    /**
     * 스피커 객체를 만든다.
     *
     * `USAGE_VOICE_COMMUNICATION` 은 "통화 음성"이라는 뜻이다. SDK 는 `USAGE_MEDIA`(음악·영상)로
     * 재생했는데, 그러면 소리가 미디어 경로로 나가서 에코 캔슬러가 이 소리를 "지워야 할 소리"로
     * 참조하지 못했다. 입력과 출력을 모두 통화 경로에 올려야 에코 캔슬이 동작한다 (이슈 #12 1단계).
     * 볼륨도 미디어 볼륨이 아니라 통화 볼륨을 따른다.
     */
    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // 버퍼를 최소로 잡는다. 크면 끼어들기 때 버릴 소리가 많아지고 지연도 커진다.
            .setBufferSizeInBytes(minBuffer)
            // STREAM 모드: 소리를 조각조각 계속 흘려 넣는 방식 (파일 한 번에 재생하는 STATIC 과 반대).
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "스피커를 열지 못했다"
        }
        return track
    }

    /**
     * 기기의 하드웨어 에코 캔슬러를 마이크에 붙인다.
     *
     * 에코 캔슬러는 "스피커로 내보낸 소리"를 기억했다가 마이크 입력에서 그 소리를 빼준다.
     * `VOICE_COMMUNICATION` 소스가 이미 붙여주는 기기도 많지만, SDK 와 동일하게 명시적으로 켠다.
     * 지원하지 않는 기기에서는 조용히 건너뛴다.
     */
    private fun enableEchoCanceler(recorder: AudioRecord) {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "이 기기는 AcousticEchoCanceler 를 지원하지 않는다 — 통화 경로 전처리에만 의존")
            return
        }
        val aec = AcousticEchoCanceler.create(recorder.audioSessionId)
        aec?.enabled = true
        Log.i(TAG, "에코 캔슬러 ${if (aec?.enabled == true) "켜짐" else "켜기 실패"}")
    }

    // ---- 내부: 유틸 --------------------------------------------------------

    private fun drainQueue() {
        while (playbackQueue.tryReceive().isSuccess) Unit
    }

    /**
     * PCM 조각에서 가장 큰 소리의 크기를 구한다.
     *
     * 16-bit PCM 은 2바이트가 값 하나다. 안드로이드는 **리틀 엔디언**이라 앞 바이트가 낮은 자리,
     * 뒤 바이트가 높은 자리다: 값 = (뒤 바이트 << 8) | 앞 바이트.
     */
    private fun peakAmplitude(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"

        /** 서버로 보내는 소리의 샘플레이트. Live API 입력 규격. */
        const val INPUT_SAMPLE_RATE = 16_000

        /** 서버에서 받는 모델 목소리의 샘플레이트. Live API 출력 규격. */
        const val OUTPUT_SAMPLE_RATE = 24_000

        /**
         * 한 번에 읽어서 보내는 마이크 조각 크기 — 40ms 분량.
         *
         * 16000Hz × 2바이트 × 0.04초 = 1280바이트.
         * 작을수록 지연은 줄지만 전송 횟수가 늘어난다. 음성 대화에서 흔히 쓰는 20~100ms 사이에서
         * 끼어들기 반응을 우선해 짧은 쪽을 골랐다. (SDK 는 약 1000바이트 이상 모아서 보냈다.)
         */
        private const val INPUT_CHUNK_BYTES = INPUT_SAMPLE_RATE * 2 * 40 / 1000
    }
}
