package com.leo.voicecounselpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 1계층 위기 감지 (이슈 #17).
 *
 * 실기기에서 위기 표현을 소리 내어 테스트하지 않기 위해 여기서 검증한다.
 * 원칙은 "오탐을 허용하고 미탐을 피한다" — 부정문이 걸리는 것은 의도된 동작이다.
 */
class CrisisDetectorTest {

    private fun level(text: String) = CrisisDetector.detect(text)?.level ?: CrisisLevel.NONE

    // ---- 레벨 구분 ---------------------------------------------------------

    @Test
    fun `일상 대화는 신호가 없다`() {
        assertEquals(CrisisLevel.NONE, level("오늘 회사에서 팀장님이랑 또 부딪혔어요"))
        assertEquals(CrisisLevel.NONE, level("주말에도 회사 생각이 나요"))
        assertEquals(CrisisLevel.NONE, level("안녕하세요. 오늘 좀 이야기 나누고 싶어서요"))
    }

    @Test
    fun `절망 표현은 우려 신호다`() {
        assertEquals(CrisisLevel.CONCERN, level("요즘은 사는 게 의미 없는 것 같아요"))
        assertEquals(CrisisLevel.CONCERN, level("이제 더는 못 버티겠어요"))
        assertEquals(CrisisLevel.CONCERN, level("주변에 아무도 없어요"))
    }

    @Test
    fun `자살 자해 직접 언급은 명시적 신호다`() {
        assertEquals(CrisisLevel.EXPLICIT, level("그냥 죽고 싶어요"))
        assertEquals(CrisisLevel.EXPLICIT, level("사라지고 싶다는 생각이 들어요"))
        assertEquals(CrisisLevel.EXPLICIT, level("자해를 한 적이 있어요"))
    }

    @Test
    fun `방법 시점 준비 언급은 임박 신호다`() {
        assertEquals(CrisisLevel.IMMINENT, level("수면제를 모아 두었어요"))
        assertEquals(CrisisLevel.IMMINENT, level("유서를 써 놨어요"))
    }

    @Test
    fun `여러 레벨이 섞이면 가장 높은 레벨이다`() {
        assertEquals(CrisisLevel.IMMINENT, level("죽고 싶어서 유서를 썼어요"))
    }

    // ---- 전사 특성 ---------------------------------------------------------

    @Test
    fun `띄어쓰기와 문장부호가 달라도 잡는다`() {
        assertEquals(CrisisLevel.EXPLICIT, level("죽고싶어"))
        assertEquals(CrisisLevel.EXPLICIT, level("죽고  싶어..."))
        assertEquals(CrisisLevel.EXPLICIT, level("죽 고 싶 어"))
    }

    @Test
    fun `조각으로 나뉘어 도착해도 창에서 잡는다`() {
        val detector = CrisisDetector()
        assertNull(detector.feed("요즘은 그냥"))
        assertNull(detector.feed(" 죽고"))
        assertEquals(CrisisLevel.EXPLICIT, detector.feed(" 싶다는 생각이 들어요")?.level)
    }

    @Test
    fun `창을 벗어난 오래된 말은 다시 걸리지 않는다`() {
        val detector = CrisisDetector(windowChars = 20)
        assertEquals(CrisisLevel.EXPLICIT, detector.feed("죽고 싶어요")?.level)
        // 창(20자)을 밀어낼 만큼 일상 대화가 이어지면 더는 걸리지 않는다.
        assertNull(detector.feed("그런데 오늘은 점심으로 김치찌개를 먹었고 맛있었어요"))
    }

    @Test
    fun `reset 하면 창이 비워진다`() {
        val detector = CrisisDetector()
        detector.feed("죽고")
        detector.reset()
        assertNull(detector.feed("싶어요"))
    }

    // ---- 오탐 방지 / 허용 ---------------------------------------------------

    @Test
    fun `일상 과장 표현은 걸리지 않는다`() {
        assertEquals(CrisisLevel.NONE, level("배고파 죽겠다"))
        assertEquals(CrisisLevel.NONE, level("피곤해 죽겠어요"))
        assertEquals(CrisisLevel.NONE, level("너무 힘들어요"))
    }

    @Test
    fun `부정문도 걸린다 - 미탐 회피를 위한 의도된 오탐`() {
        assertEquals(CrisisLevel.EXPLICIT, level("죽고 싶지는 않아요"))
    }

    @Test
    fun `로그용 분류는 원문을 담지 않는다`() {
        val detection = CrisisDetector.detect("그냥 죽고 싶어요")!!
        assertEquals("죽음 소망", detection.category)
    }

    @Test
    fun `레벨은 높은 쪽을 고른다`() {
        assertEquals(CrisisLevel.EXPLICIT, CrisisLevel.max(CrisisLevel.CONCERN, CrisisLevel.EXPLICIT))
        assertEquals(CrisisLevel.IMMINENT, CrisisLevel.max(CrisisLevel.IMMINENT, CrisisLevel.NONE))
    }
}
