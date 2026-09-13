package com.leo.voicecounselpoc

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 마이크 입력을 Gemini Live API 포맷(PCM 16-bit / 24kHz / mono)으로 캡처한다.
 *
 * Phase 0 2단계에서는 캡처만 검증한다 — 여기서 나온 청크를 아직 어디로도 보내지 않는다.
 * 3~4단계에서 이 Flow를 그대로 Live 세션에 연결할 예정이라 포맷을 미리 맞춰둔다.
 */
class AudioRecorder {

    /** 한 번에 읽어낸 오디오 조각. [peakAmplitude]는 이 조각 안의 최대 진폭(0~32767). */
    class Chunk(val pcm: ByteArray, val peakAmplitude: Int)

    /**
     * 캡처를 시작하고 약 [CHUNK_MILLIS]ms 단위로 청크를 방출한다.
     * Flow 수집이 취소되면 [AudioRecord]를 정지·해제한다.
     *
     * 호출 전에 `RECORD_AUDIO` 런타임 권한이 허용되어 있어야 한다.
     */
    @SuppressLint("MissingPermission")
    fun start(): Flow<Chunk> = flow {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        check(minBufferSize > 0) {
            "이 기기가 ${SAMPLE_RATE}Hz / 16-bit / mono 캡처를 지원하지 않습니다 " +
                "(getMinBufferSize=$minBufferSize)"
        }

        // 청크 하나 = CHUNK_MILLIS 분량. 단, 최소 버퍼보다는 커야 한다.
        val chunkBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MILLIS / 1000
        val bufferSize = maxOf(minBufferSize * 2, chunkBytes)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            bufferSize
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord 초기화 실패 — 마이크 권한 또는 오디오 장치를 확인하세요."
        }

        try {
            record.startRecording()
            val buffer = ByteArray(chunkBytes)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                emit(Chunk(buffer.copyOf(read), peakAmplitudeOf(buffer, read)))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    /** 리틀엔디언 16-bit PCM에서 최대 절댓값을 구한다. */
    private fun peakAmplitudeOf(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    companion object {
        const val SAMPLE_RATE = AiConfig.AUDIO_SAMPLE_RATE_HZ
        const val CHUNK_MILLIS = 100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** 16-bit PCM 최대 진폭. 화면에 비율로 표시할 때 쓴다. */
        const val MAX_AMPLITUDE = 32767
    }
}
