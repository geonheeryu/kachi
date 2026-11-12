package me.rgunny.kachi.collector.adapter.outbound.persistence.news

import java.time.Duration
import kotlin.test.assertEquals
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.collector.application.port.outbound.news.model.SaveNewsResult
import me.rgunny.kachi.collector.application.port.outbound.outbox.model.NewsCollectedEvent
import me.rgunny.kachi.collector.application.port.outbound.outbox.model.toOutbox
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.News
import me.rgunny.kachi.collector.domain.NewsExcerpt
import me.rgunny.kachi.collector.domain.NewsLanguage
import me.rgunny.kachi.collector.domain.NewsSource
import me.rgunny.kachi.collector.domain.NewsTitle
import me.rgunny.kachi.collector.domain.NewsUrl
import me.rgunny.kachi.collector.domain.outbox.CollectorOutbox
import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import me.rgunny.kachi.collector.support.CollectorOutboxCollection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoTemplate

@DisplayName("NewsPersistenceAdapter 통합 테스트")
class NewsPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var adapter: NewsPersistenceAdapter

    @Autowired
    private lateinit var repository: NewsMongoRepository

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    private val collectedAt = CollectorTestFixture.NOW
    private val publishedAt = collectedAt.minus(Duration.ofHours(14))
    private val keyword = CollectedKeyword.of("NVIDIA")

    private val outboxCollection by lazy { CollectorOutboxCollection(mongoTemplate) }

    @BeforeEach
    fun cleanUp() {
        repository.deleteAll().block()
        outboxCollection.clear()
    }

    @Nested
    @DisplayName("save()")
    inner class Save {

        @Test
        @DisplayName("News 도메인을 MongoDB에 저장한다")
        fun saveNews() = runBlocking {
            val news = news(url = "https://kachi.com/news/1")

            val result = adapter.save(news, outboxFor(news))

            assertEquals(SaveNewsResult.SAVED, result)
            assertEquals(
                setOf(news.urlHash),
                adapter.findExistingUrlHashes(NewsSource.GOOGLE, setOf(news.urlHash))
            )
            assertEquals(news.id.value.toString(), outboxCollection.findAll().single().eventKey)
        }

        @Test
        @DisplayName("저장된 문서에 발췌문과 언어가 남는다")
        fun saveExcerptAndLanguage() = runBlocking {
            val news = CollectorTestFixture.news(url = "https://kachi.com/news/9")

            adapter.save(news, outboxFor(news))

            val found = repository.findById(news.id.value).awaitSingle()
            assertEquals(news.excerpt.value, found.excerpt)
            assertEquals(news.language.value, found.language)
        }

        @Test
        @DisplayName("같은 source와 URL hash는 중복으로 처리한다")
        fun returnDuplicatedWhenSourceAndUrlHashAlreadyExists() = runBlocking {
            val first = news(url = "https://kachi.com/news/1")
            val second = news(url = "https://kachi.com/news/1")

            val firstResult = adapter.save(first, outboxFor(first))
            val secondResult = adapter.save(second, outboxFor(second))

            assertEquals(SaveNewsResult.SAVED, firstResult)
            assertEquals(SaveNewsResult.DUPLICATED, secondResult)
            // 중복으로 막힌 기사의 outbox 행은 트랜잭션과 함께 되돌아간다.
            assertEquals(1, outboxCollection.findAll().size)
        }

        @Test
        @DisplayName("같은 페이지의 URL 변형은 같은 hash라 중복으로 처리한다")
        fun returnDuplicatedForCanonicalUrlVariant() = runBlocking {
            val first = news(url = "https://kachi.com/news/1?utm_source=naver")
            val second = news(url = "https://KACHI.com/news/1/")

            adapter.save(first, outboxFor(first))
            val secondResult = adapter.save(second, outboxFor(second))

            assertEquals(SaveNewsResult.DUPLICATED, secondResult)
        }
    }

    @Nested
    @DisplayName("findExistingUrlHashes()")
    inner class FindExistingUrlHashes {

        @Test
        @DisplayName("같은 source에 저장된 URL hash만 반환한다")
        fun findExistingUrlHashes() = runBlocking {
            val saved = news(url = "https://kachi.com/news/1")
            val notSaved = news(url = "https://kachi.com/news/2")
            adapter.save(saved, outboxFor(saved))

            val found = adapter.findExistingUrlHashes(
                source = NewsSource.GOOGLE,
                setOf(saved.urlHash, notSaved.urlHash)
            )

            assertEquals(setOf(saved.urlHash), found)
        }

        @Test
        @DisplayName("다른 source의 같은 URL hash는 반환하지 않는다")
        fun doesNotFindSameUrlHashWhenSourceIsDifferent() = runBlocking {
            val saved = news(url = "https://kachi.com/news/1", source = NewsSource.NAVER)
            adapter.save(saved, outboxFor(saved))

            val found = adapter.findExistingUrlHashes(
                source = NewsSource.GOOGLE,
                urlHashes = setOf(saved.urlHash)
            )

            assertEquals(emptySet(), found)
        }

        @Test
        @DisplayName("조회 대상 URL hash가 비어 있으면 빈 Set을 반환한다")
        fun returnEmptySetWhenUrlHashesAreEmpty() = runBlocking {
            val found = adapter.findExistingUrlHashes(NewsSource.GOOGLE, emptySet())

            assertEquals(emptySet(), found)
        }
    }

    private fun outboxFor(news: News): CollectorOutbox {
        return NewsCollectedEvent.from(news).toOutbox(payload = "{}", now = CollectorTestFixture.NOW)
    }

    private fun news(
        url: String,
        source: NewsSource = NewsSource.GOOGLE
    ): News {
        val title = "NVIDIA 뉴스"
        return News.create(
            source = source,
            title = NewsTitle.of(title),
            excerpt = NewsExcerpt.of("$title 발췌문"),
            url = NewsUrl.of(url),
            language = NewsLanguage.of("ko"),
            publishedAt = publishedAt,
            collectedAt = collectedAt,
            matchedKeywords = listOf(keyword)
        )
    }
}
