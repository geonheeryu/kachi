package me.rgunny.kachi.collector.domain

import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@DisplayName("News")
class NewsTest {
    private val title = NewsTitle.of("NVIDIA 실적 발표")
    private val excerpt = NewsExcerpt.of("엔비디아가 2분기 실적을 발표했다")
    private val url = NewsUrl.of("https://kachi.com/news/1")
    private val language = NewsLanguage.of("ko")
    private val publishedAt = CollectorTestFixture.NOW
    private val collectedAt = publishedAt.plusSeconds(60)
    private val keyword = CollectedKeyword.of("NVIDIA")

    @Nested
    @DisplayName("create()")
    inner class Create {

        @Test
        @DisplayName("뉴스를 생성한다")
        fun createNews() {
            val news = createNewsFixture(listOf(keyword))

            assertNotNull(news.id.value)
            assertEquals(NewsSource.GOOGLE, news.source)
            assertEquals(title, news.title)
            assertEquals(excerpt, news.excerpt)
            assertEquals(url, news.url)
            assertEquals(url.hash, news.urlHash)
            assertEquals(language, news.language)
            assertEquals(publishedAt, news.publishedAt)
            assertEquals(collectedAt, news.collectedAt)
            assertEquals(listOf(keyword), news.matchedKeywords)
        }

        @Test
        @DisplayName("중복 키워드는 제거한다")
        fun removeDuplicatedKeywords() {
            val news = createNewsFixture(listOf(keyword, keyword))

            assertEquals(listOf(keyword), news.matchedKeywords)
        }

        @Test
        @DisplayName("매칭 키워드는 하나 이상이어야 한다")
        fun rejectEmptyMatchedKeywords() {
            assertFailsWith<IllegalArgumentException> {
                createNewsFixture(emptyList())
            }
        }
    }

    private fun createNewsFixture(matchedKeywords: List<CollectedKeyword>): News {
        return News.create(
            source = NewsSource.GOOGLE,
            title = title,
            excerpt = excerpt,
            url = url,
            language = language,
            publishedAt = publishedAt,
            collectedAt = collectedAt,
            matchedKeywords = matchedKeywords
        )
    }
}
