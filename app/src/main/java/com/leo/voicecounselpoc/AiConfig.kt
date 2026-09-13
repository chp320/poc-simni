package com.leo.voicecounselpoc

/**
 * Phase 0 스파이크용 Gemini Live API 설정.
 *
 * [LIVE_MODEL_NAME]은 preview 모델이다. 세션 연결 자체가 실패하거나 한국어 음성 품질에
 * 심각한 문제가 있으면 이 값 하나만 [FALLBACK_LIVE_MODEL_NAME]으로 바꿔서 재시도한다.
 *
 * 주의: 2.5 native-audio 계열은 2026년 10월 종료 예정이므로 fallback은 임시 확인용으로만 쓴다.
 */
object AiConfig {

    /** 기본 모델 — Gemini 3.1 Live (native audio) */
    const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"

    /** 문제 발생 시 즉시 전환할 대체 모델 — 2026년 10월 종료 예정 */
    const val FALLBACK_LIVE_MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Live API 오디오 포맷: 16-bit PCM, 24kHz, mono */
    const val AUDIO_SAMPLE_RATE_HZ = 24_000
}
