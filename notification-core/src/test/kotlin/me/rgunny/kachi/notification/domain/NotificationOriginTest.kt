package me.rgunny.kachi.notification.domain

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("NotificationOrigin")
class NotificationOriginTest {

    @Test
    @DisplayName("NONE은 값 필드가 모두 null이고 keywords가 비어 있다")
    fun none() {
        assertNull(NotificationOrigin.NONE.summaryId)
        assertNull(NotificationOrigin.NONE.storyId)
        assertNull(NotificationOrigin.NONE.version)
        assertTrue(NotificationOrigin.NONE.keywords.isEmpty())
        assertNull(NotificationOrigin.NONE.userId)
        assertEquals(NotificationOrigin(null, null, null, emptyList(), null), NotificationOrigin.NONE)
    }

    @Test
    @DisplayName("story 출처의 모든 값을 담는다")
    fun storyOrigin() {
        val origin = NotificationOrigin(
            summaryId = "summary-1",
            storyId = "story-1",
            version = 1,
            keywords = listOf("tesla", "nvidia"),
            userId = "user-1",
        )

        assertEquals("story-1", origin.storyId)
        assertEquals(1L, origin.version)
        assertEquals(listOf("tesla", "nvidia"), origin.keywords)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    @DisplayName("빈 문자열 필드는 만들 수 없다")
    fun rejectBlank(blank: String) {
        assertFailsWith<IllegalArgumentException> { origin(summaryId = blank) }
        assertFailsWith<IllegalArgumentException> { origin(storyId = blank) }
        assertFailsWith<IllegalArgumentException> { origin(userId = blank) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    @DisplayName("keywords에 빈 문자열 원소가 있으면 만들 수 없다")
    fun rejectBlankKeyword(blank: String) {
        assertFailsWith<IllegalArgumentException> { origin(keywords = listOf("tesla", blank)) }
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    @DisplayName("version이 1보다 작으면 만들 수 없다")
    fun rejectNonPositiveVersion(version: Long) {
        assertFailsWith<IllegalArgumentException> { origin(version = version) }
    }

    private fun origin(
        summaryId: String? = null,
        storyId: String? = null,
        version: Long? = null,
        keywords: List<String> = emptyList(),
        userId: String? = null,
    ): NotificationOrigin {
        return NotificationOrigin(
            summaryId = summaryId,
            storyId = storyId,
            version = version,
            keywords = keywords,
            userId = userId,
        )
    }
}
