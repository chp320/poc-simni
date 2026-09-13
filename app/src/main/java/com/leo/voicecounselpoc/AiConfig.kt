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

    /**
     * 턴 종료 판단 설정 (이슈 #22).
     *
     * Phase 0에서 "말하다 생각하려고 잠깐 멈추면 모델이 바로 응답을 시작한다"는 문제가 있었다.
     * 상담에서는 감정을 정리할 시간이 필요한데 끊기면 하려던 말을 놓친다.
     *
     * 핵심은 **끼어들기를 죽이지 않으면서 턴 종료만 늦추는 것**이다.
     * `startSensitivity = HIGH` (말 시작은 민감하게 → 끼어들기 유지)
     * `endSensitivity = LOW`    (말 끝은 둔감하게 → 성급한 턴 종료 방지)
     */
    object TurnDetection {

        /**
         * 이만큼 침묵이 이어지면 사용자의 말이 끝났다고 본다.
         *
         * 트레이드오프가 있어 무작정 늘리면 안 된다:
         * - 짧으면 → 생각하는 중에 끊긴다 (이슈 #22의 원래 증상)
         * - 길면  → 말을 끝냈는데 반응이 없어 어색하다
         *
         * 상담 맥락에서는 긴 쪽으로 치우치는 편이 낫다.
         * 기다려주는 어색함보다 끊기는 불쾌함이 크다.
         *
         * 실기기 튜닝 이력 (Alldocube / Android 14):
         * - 1500ms → 끊김. "음.. 지금 시각은 4시 32분..(3초) 입니다" 에서 '입니다' 전에 응답 시작
         * - 2000ms → 여전히 부족
         * - 3000ms → 현재값
         */
        const val SILENCE_DURATION_MS = 3000

        /** 발화 시작 지점 앞쪽 오디오를 얼마나 포함할지. 첫 음절이 잘리는 것을 막는다. */
        const val PREFIX_PADDING_MS = 300
    }

    /**
     * 통화 시간 정책 (이슈 #20).
     *
     * 상한에 닿았다고 갑자기 끊지 않는다. 말하는 도중 끊기는 것은 나쁜 경험이고
     * 상담 맥락에서는 더 그렇다. 실제 상담에서도 "시간이 다 되어갑니다"로 마무리를 준비시킨다.
     *
     * 테스트할 때는 이 값들을 짧게 바꿔서 확인한다 — 30분을 기다릴 수 없다.
     */
    object Session {

        /** 세션 상한. 요구사항 3장에서 확정. */
        const val MAX_DURATION_SEC = 30 * 60

        /** "5분 남았어요" 표시 시점. */
        const val WARN_AT_SEC = 25 * 60

        /** "곧 종료됩니다" 표시 시점. */
        const val FINAL_WARN_AT_SEC = 29 * 60

        /**
         * 이만큼 아무 말도 오가지 않으면 "아직 계신가요?"를 띄운다.
         *
         * 상담에서 침묵은 생각을 정리하는 의미 있는 시간이라 [MAX_DURATION_SEC] 처럼
         * 단호하게 끊지 않는다. 확인만 한다.
         *
         * ⚠️ 이 수치는 근거 없는 추정이다. 실사용 후 조정해야 한다.
         */
        const val SILENCE_NOTICE_SEC = 3 * 60

        /** 확인에도 응답이 없으면 종료한다. 앱을 켜둔 채 잠든 경우 대비. */
        const val SILENCE_END_SEC = 5 * 60
    }
}
