package com.leo.voicecounselpoc

/**
 * 위기 레벨 (요구사항 5.1 / 이슈 #17).
 *
 * ⚠️ 단계 구분은 임상적 판단이 필요한 영역이다. 정신건강 전문가 검토 전의 초안이다.
 */
enum class CrisisLevel(val rank: Int) {
    /** 신호 없음. */
    NONE(0),

    /** 우려 신호 — 절망감, 무력감, 지속적 고립 표현. 배너만 조용히. */
    CONCERN(1),

    /** 명시적 위기 신호 — 자해·자살 직접 언급. 안내 카드 + 원탭 연결. */
    EXPLICIT(2),

    /** 임박 신호 — 구체적 방법·시점·수단 언급. 전체 화면 안내. */
    IMMINENT(3);

    companion object {
        /** 둘 중 높은 쪽. 레벨은 올라가기만 한다(sticky)는 결정을 한 곳에서 지키기 위해 쓴다. */
        fun max(a: CrisisLevel, b: CrisisLevel): CrisisLevel = if (a.rank >= b.rank) a else b
    }
}

/** 감지 결과. 로그에는 [category] 까지만 남기고 원문 문장은 남기지 않는다 (#35). */
data class CrisisDetection(val level: CrisisLevel, val category: String)

/**
 * 1계층 감지 — 사용자 발화 전사에서 키워드를 찾는다 (이슈 #17).
 *
 * ## 설계
 * - **오탐을 허용하고 미탐을 피한다.** 배너가 한 번 더 뜨는 비용보다 놓치는 비용이 크다.
 *   "죽고 싶지 않아"처럼 부정문도 걸린다 — 의도된 동작이다.
 * - **전사는 부정확하다** ("오늘은 일요일이고" → "응. 응. 이들이고"). 여기서 놓친 것은 2계층(모델 판단)이 잡는다.
 * - 전사는 **조각으로** 도착한다 ("죽고" + " 싶어"). 최근 발화를 이어 붙인 창에서 찾는다.
 * - 띄어쓰기·문장부호를 지우고 비교한다 ("죽고 싶어" = "죽고싶어"). 전사의 띄어쓰기는 들쑥날쑥하다.
 * - Android 의존이 없는 순수 Kotlin 이다. 위기 표현을 소리 내어 말하지 않고 **JVM 단위 테스트**로 검증한다.
 *
 * 한 통화에 하나씩 만들어 쓴다 (창이 통화별로 분리되어야 한다).
 *
 * ⚠️ 키워드 목록은 **전문가 검토 전 초안**이다.
 */
class CrisisDetector(private val windowChars: Int = DEFAULT_WINDOW_CHARS) {

    /** 정규화된 최근 발화. 조각 사이에 걸친 표현을 잡기 위한 창이다. */
    private var window = ""

    /**
     * 사용자 발화 전사 조각을 넣는다. 창 안에서 가장 높은 레벨의 신호를 돌려준다. 없으면 null.
     */
    fun feed(fragment: String): CrisisDetection? {
        val normalized = normalize(fragment)
        if (normalized.isEmpty()) return null
        window = (window + normalized).takeLast(windowChars)
        return detect(window)
    }

    /** 통화가 끝났을 때. */
    fun reset() {
        window = ""
    }

    companion object {
        /** 약 두세 문장 분량. 너무 길면 이미 지나간 말이 계속 다시 걸린다. */
        const val DEFAULT_WINDOW_CHARS = 60

        /**
         * 단일 문장에서 신호를 찾는다. 창 없이 한 번만 볼 때 쓴다.
         * 여러 레벨이 걸리면 가장 높은 레벨을 돌려준다.
         */
        fun detect(text: String): CrisisDetection? {
            val normalized = normalize(text)
            if (normalized.isEmpty()) return null
            // 높은 레벨부터 본다 — 임박 신호가 명시적 신호보다 먼저 잡혀야 한다.
            for ((level, groups) in KEYWORDS) {
                for ((category, keywords) in groups) {
                    if (keywords.any { normalized.contains(it) }) return CrisisDetection(level, category)
                }
            }
            return null
        }

        /** 공백·문장부호를 지우고 소문자로. 한글은 그대로 둔다. */
        internal fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }

        /**
         * 레벨 → (분류 → 키워드). **정규화된 형태**(공백 없음)로 적는다.
         *
         * 제외한 표현과 이유:
         * - "죽겠다" — "배고파 죽겠다", "피곤해 죽겠다"처럼 일상 과장 표현이 압도적으로 많다
         * - "힘들다" 단독 — 상담 앱에서 거의 모든 대화에 나온다. 1계층에서는 더 구체적인 표현만 본다
         *
         * ⚠️ 전문가 검토 전 초안.
         */
        private val KEYWORDS: List<Pair<CrisisLevel, List<Pair<String, List<String>>>>> = listOf(
            CrisisLevel.IMMINENT to listOf(
                "방법" to listOf(
                    "목을매", "뛰어내리", "투신", "번개탄", "수면제를모아", "약을모아", "약을모았",
                    "손목을그", "손목을긋", "칼로찌르",
                ),
                "시점·준비" to listOf(
                    "유서", "오늘죽", "오늘밤에죽", "내일죽", "마지막인사", "주변정리를", "죽을날",
                    "죽을준비", "죽을곳",
                ),
            ),
            CrisisLevel.EXPLICIT to listOf(
                "자살" to listOf("자살", "스스로목숨", "목숨을끊", "극단적인선택", "극단적선택"),
                "죽음 소망" to listOf(
                    "죽고싶", "죽어버리고싶", "죽어버릴까", "죽으면편할", "죽는게나을", "살기싫", "살고싶지않",
                    "그만살고싶", "사라지고싶", "없어지고싶", "세상을떠나고싶", "눈을뜨지않았으면",
                ),
                "자해" to listOf("자해", "나를해치", "몸에상처", "때리고싶어나를"),
            ),
            CrisisLevel.CONCERN to listOf(
                "절망" to listOf(
                    "희망이없", "아무의미없", "사는게의미없", "살아서뭐해", "살아서뭐하", "미래가없",
                    "나아질것같지않", "다끝났",
                ),
                "무력·한계" to listOf("더는못버티", "못버티겠", "더는못하겠", "다포기하고싶", "모든걸포기"),
                "고립" to listOf("아무도없어", "나혼자뿐", "나를신경쓰는사람", "다들나를싫어", "내가없어도"),
            ),
        )
    }
}
