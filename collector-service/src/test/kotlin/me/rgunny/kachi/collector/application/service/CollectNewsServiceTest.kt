package me.rgunny.kachi.collector.application.service

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.application.port.inbound.collection.model.CollectNewsCommand
import me.rgunny.kachi.collector.application.port.outbound.news.model.CollectedArticle
import me.rgunny.kachi.collector.application.port.outbound.collection.CollectionRunPersistencePort
import me.rgunny.kachi.collector.application.port.outbound.keyword.KeywordReaderPort
import me.rgunny.kachi.collector.application.port.outbound.news.NewsPersistencePort
import me.rgunny.kachi.collector.application.port.outbound.news.NewsProviderPort
import me.rgunny.kachi.collector.application.port.outbound.news.model.SaveNewsResult
import me.rgunny.kachi.collector.domain.outbox.CollectorOutbox
import me.rgunny.kachi.collector.fake.FakeCollectorOutboxEventSerializer
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.CollectionRun
import me.rgunny.kachi.collector.domain.CollectionRunId
import me.rgunny.kachi.collector.domain.CollectionRunStatus
import me.rgunny.kachi.collector.domain.News
import me.rgunny.kachi.collector.domain.NewsSource
import me.rgunny.kachi.collector.domain.NewsUrl
import me.rgunny.kachi.collector.domain.ProviderFailureReason
import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("CollectNewsService")
class CollectNewsServiceTest {
    private val clock = CollectorTestFixture.CLOCK
    private val keyword = CollectedKeyword.of("NVIDIA")
    private val keywordReader = FakeKeywordReaderPort()
    private val newsPersistence = FakeNewsPersistencePort()
    private val collectionRunPersistence = FakeCollectionRunPersistencePort()

    @Nested
    @DisplayName("collect()")
    inner class Collect {
        @Test
        @DisplayName("뉴스를 수집하고 CollectionRun을 성공 상태로 완료한다")
        fun collectNewsSuccessfully() = runBlocking {
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(
                    article(NewsSource.GOOGLE, "NVIDIA 실적 발표", "https://kachi.com/news/1"),
                    article(NewsSource.GOOGLE, "NVIDIA 신제품 공개", "https://kachi.com/news/2")
                )
            )
            val service = serviceOf(googleProvider)

            val result = service.collect(CollectNewsCommand(keywords = listOf(keyword)))

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(1, result.requestedKeywords)
            assertEquals(2, result.collectedCount)
            assertEquals(0, result.duplicateCount)
            assertEquals(0, result.failureCount)
            assertEquals(2, newsPersistence.savedNews.size)
            assertEquals(2, collectionRunPersistence.savedRuns.size)
        }

        @Test
        @DisplayName("command에 키워드가 없으면 KeywordReaderPort에서 활성 키워드를 읽는다")
        fun readKeywordsWhenCommandKeywordsAreEmpty() = runBlocking {
            keywordReader.keywords = listOf(keyword)
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "NVIDIA 뉴스", "https://kachi.com/news/1"))
            )
            val service = serviceOf(googleProvider)

            val result = service.collect(CollectNewsCommand(keywords = emptyList()))

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(1, result.requestedKeywords)
            assertEquals(1, keywordReader.readCount)
        }

        @Test
        @DisplayName("수집 source가 지정되면 해당 provider만 호출한다")
        fun collectOnlyRequestedSources() = runBlocking {
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "Google 뉴스", "https://kachi.com/news/1"))
            )
            val naverProvider = FakeNewsProviderPort(
                source = NewsSource.NAVER,
                articles = listOf(article(NewsSource.NAVER, "Naver 뉴스", "https://kachi.com/news/2"))
            )
            val service = serviceOf(googleProvider, naverProvider)

            val result = service.collect(
                CollectNewsCommand(
                    keywords = listOf(keyword),
                    sources = setOf(NewsSource.GOOGLE)
                )
            )

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(1, result.collectedCount)
            assertEquals(1, googleProvider.collectCount)
            assertEquals(0, naverProvider.collectCount)
        }

        @Test
        @DisplayName("일부 provider가 실패하면 CollectionRun을 부분 실패 상태로 완료한다")
        fun completeAsPartiallyFailedWhenSomeProviderFails() = runBlocking {
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "Google 뉴스", "https://kachi.com/news/1"))
            )
            val naverProvider = FakeNewsProviderPort(
                source = NewsSource.NAVER,
                failure = IllegalStateException("timeout")
            )
            val service = serviceOf(googleProvider, naverProvider)

            val result = service.collect(CollectNewsCommand(keywords = listOf(keyword)))

            assertEquals(CollectionRunStatus.PARTIALLY_FAILED, result.status)
            assertEquals(1, result.collectedCount)
            assertEquals(1, result.failureCount)
            assertEquals("timeout", result.failureReason)
            assertEquals(
                ProviderFailureReason.UNKNOWN,
                collectionRunPersistence.savedRuns.last().providerResults.last().failureReason
            )
        }

        @Test
        @DisplayName("이미 저장된 URL은 중복으로 집계하고 저장하지 않는다")
        fun skipExistingUrlHash() = runBlocking {
            val existingUrlHash = NewsUrl.of("https://kachi.com/news/1").hash
            newsPersistence.existingNewsKeys = mutableSetOf(NewsSource.GOOGLE to existingUrlHash)
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "NVIDIA 뉴스", "https://kachi.com/news/1"))
            )
            val service = serviceOf(googleProvider)

            val result = service.collect(CollectNewsCommand(keywords = listOf(keyword)))

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(0, result.collectedCount)
            assertEquals(1, result.duplicateCount)
            assertEquals(0, newsPersistence.savedNews.size)
        }

        @Test
        @DisplayName("다른 source의 같은 URL은 기존 중복으로 보지 않는다")
        fun saveSameUrlWhenSourceIsDifferent() = runBlocking {
            val urlHash = NewsUrl.of("https://kachi.com/news/1").hash
            newsPersistence.existingNewsKeys = mutableSetOf(NewsSource.NAVER to urlHash)
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "NVIDIA 뉴스", "https://kachi.com/news/1"))
            )
            val service = serviceOf(googleProvider)

            val result = service.collect(CollectNewsCommand(keywords = listOf(keyword)))

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(1, result.collectedCount)
            assertEquals(0, result.duplicateCount)
            assertEquals(1, newsPersistence.savedNews.size)
        }

        @Test
        @DisplayName("같은 batch 안에서 같은 URL이 여러 키워드에 매칭되면 하나만 저장하고 중복으로 집계한다")
        fun countBatchDuplicateUrlAsDuplicate() = runBlocking {
            val aiKeyword = CollectedKeyword.of("AI 반도체")
            val googleProvider = FakeNewsProviderPort(
                source = NewsSource.GOOGLE,
                articles = listOf(article(NewsSource.GOOGLE, "NVIDIA AI 반도체 뉴스", "https://kachi.com/news/1"))
            )
            val service = serviceOf(googleProvider)

            val result = service.collect(CollectNewsCommand(keywords = listOf(keyword, aiKeyword)))

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status)
            assertEquals(1, result.collectedCount)
            assertEquals(1, result.duplicateCount)
            assertEquals(1, newsPersistence.savedNews.size)
            assertEquals(listOf(keyword, aiKeyword), newsPersistence.savedNews.first().matchedKeywords)
        }
    }

    private fun serviceOf(vararg providers: NewsProviderPort): CollectNewsService {
        return CollectNewsService(
            keywordReaderPort = keywordReader,
            newsProviderPorts = providers.toList(),
            newsPersistencePort = newsPersistence,
            collectionRunPersistencePort = collectionRunPersistence,
            eventSerializer = FakeCollectorOutboxEventSerializer(),
            clock = clock
        )
    }

    private fun article(source: NewsSource, title: String, url: String): CollectedArticle {
        return CollectedArticle(
            source = source,
            title = title,
            excerpt = "$title 발췌문",
            url = url,
            language = "ko",
            publishedAt = CollectorTestFixture.NOW
        )
    }

    private class FakeKeywordReaderPort : KeywordReaderPort {
        var keywords: List<CollectedKeyword> = emptyList()
        var readCount: Int = 0

        override suspend fun findActiveKeywords(): List<CollectedKeyword> {
            readCount += 1
            return keywords
        }
    }

    private class FakeNewsProviderPort(
        override val source: NewsSource,
        private val articles: List<CollectedArticle> = emptyList(),
        private val failure: RuntimeException? = null
    ) : NewsProviderPort {
        var collectCount: Int = 0

        override suspend fun collect(keyword: CollectedKeyword): List<CollectedArticle> {
            collectCount += 1
            failure?.let { throw it }
            return articles
        }
    }

    private class FakeNewsPersistencePort : NewsPersistencePort {
        var existingNewsKeys: MutableSet<Pair<NewsSource, String>> = mutableSetOf()
        val savedNews: MutableList<News> = mutableListOf()

        override suspend fun findExistingUrlHashes(source: NewsSource, urlHashes: Set<String>): Set<String> {
            return existingNewsKeys
                .filter { (existingSource, existingUrlHash) ->
                    existingSource == source && existingUrlHash in urlHashes
                }
                .map { it.second }
                .toSet()
        }

        val savedOutboxes: MutableList<CollectorOutbox> = mutableListOf()

        override suspend fun save(news: News, outbox: CollectorOutbox): SaveNewsResult {
            val newsKey = news.source to news.urlHash
            if (newsKey in existingNewsKeys) {
                return SaveNewsResult.DUPLICATED
            }

            existingNewsKeys.add(newsKey)
            savedNews.add(news)
            savedOutboxes.add(outbox)
            return SaveNewsResult.SAVED
        }
    }

    private class FakeCollectionRunPersistencePort : CollectionRunPersistencePort {
        val savedRuns: MutableList<CollectionRun> = mutableListOf()

        override suspend fun findById(id: CollectionRunId): CollectionRun? {
            return savedRuns.firstOrNull { it.id == id }
        }

        override suspend fun save(collectionRun: CollectionRun): CollectionRun {
            savedRuns.add(collectionRun)
            return collectionRun
        }
    }
}
