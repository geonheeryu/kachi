package me.rgunny.kachi.story.application.service.assembly

import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.exception.StoryAssemblyErrorCode
import me.rgunny.kachi.story.application.exception.StoryAssemblyException
import me.rgunny.kachi.story.application.port.inbound.assembly.model.AttachArticleCommand
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.story.model.AttachOutcome
import me.rgunny.kachi.story.domain.AutoMergedLinkDecision
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.JudgedLinkDecision
import me.rgunny.kachi.story.domain.NewStoryLinkDecision
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryJudge
import me.rgunny.kachi.story.domain.StoryKeyword
import me.rgunny.kachi.story.domain.outbox.StoryOutboxEventType
import me.rgunny.kachi.story.fake.FakeEmbeddingPort
import me.rgunny.kachi.story.fake.FakeStoryLinkJudge
import me.rgunny.kachi.story.fake.FakeStoryOutboxEventSerializer
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fake.InMemoryStoryStore
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NEWS_ID
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import me.rgunny.kachi.story.fixture.StoryTestFixture.assemblyPolicy
import me.rgunny.kachi.story.fixture.StoryTestFixture.command
import me.rgunny.kachi.story.fixture.StoryTestFixture.embedding
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 조립 규칙을 fake 포트로 검증한다.
 *
 * 새 기사의 벡터는 (1, 0)이고 후보 기사의 벡터는 그것과의 코사인이 θ 구간별로 다르게 잡혀 있다.
 */
@DisplayName("AssembleStoryService")
class AssembleStoryServiceTest {
    private val embeddingPort = FakeEmbeddingPort()
    private val index = InMemoryCandidateIndexPort()
    private val judge = FakeStoryLinkJudge()
    private val store = InMemoryStoryStore()
    private val serializer = FakeStoryOutboxEventSerializer()

    /** 새 기사. */
    private val incoming: Embedding = embedding(1f, 0f)

    /** 코사인 약 0.949. θ_high 위. */
    private val high: Embedding = embedding(0.75f, 0.25f)

    /** 코사인 약 0.650. 회색 구간. */
    private val gray: Embedding = embedding(0.65f, 0.76f)

    /** 코사인 약 0.620. 회색 구간. */
    private val gray2: Embedding = embedding(0.62f, 0.785f)

    /** 코사인 약 0.300. θ_low 아래. */
    private val low: Embedding = embedding(0.3f, 0.954f)

    /** 코사인 0. */
    private val orthogonal: Embedding = embedding(0f, 1f)

    private val command: AttachArticleCommand = command()

    init {
        embeddingPort.fixed[command.embeddingText] = incoming
    }

    @Nested
    @DisplayName("새 story")
    inner class NewStory {

        @Test
        @DisplayName("후보가 없으면 새 story를 열고 기사·outbox·색인을 함께 남긴다")
        fun openStoryWhenNoCandidates() = runBlocking {
            val result = service().assemble(command)

            assertFalse(result.replayed)
            assertEquals(NEWS_ID, result.newsId)
            assertEquals(NewStoryLinkDecision(candidateStoryId = null, similarity = null), result.decision)

            val story = store.stories.getValue(result.storyId)
            assertEquals(1, story.articleCount)
            assertEquals(0, story.version)
            assertEquals(setOf(StoryKeyword.of("nvidia")), story.keywords)
            assertNull(story.parentStoryId)
            assertEquals(result.storyId, store.articles.getValue(NEWS_ID).storyId)

            val outbox = store.outboxes.single()
            assertEquals(StoryOutboxEventType.ARTICLE_ATTACHED, outbox.eventType)
            assertEquals("${result.storyId.value}:${NEWS_ID.value}", outbox.eventKey)
            assertEquals(result.storyId.value.toString(), outbox.partitionKey)
            assertEquals(1, serializer.serialized.single().let { it as me.rgunny.kachi.story.application.port.outbound.outbox.model.StoryArticleAttachedEvent }.storyArticleCount)

            assertEquals(result.storyId, index.points.getValue(NEWS_ID).storyId)
            assertTrue(judge.scoredSubjects.isEmpty())
        }

        @Test
        @DisplayName("최고 후보가 θ_low 아래면 새 story를 열고 그 후보를 기록한다")
        fun openStoryBelowThetaLow() = runBlocking {
            val candidate = seedStory(low)

            val result = service().assemble(command)

            val decision = assertIs<NewStoryLinkDecision>(result.decision)
            assertEquals(candidate.id, decision.candidateStoryId)
            assertEquals(0.30, decision.similarity!!, TOLERANCE)
            assertNotEquals(candidate.id, result.storyId)
            assertTrue(judge.scoredSubjects.isEmpty())
            assertEquals(2, store.stories.size)
        }

        @Test
        @DisplayName("닫힌 story는 병합 대상이 아니며 θ_high 위면 새 story의 부모가 된다")
        fun linkClosedStoryAsParent() = runBlocking {
            val closed = seedStory(high)
            store.stories[closed.id] = closed.close(NOW)

            val result = service().assemble(command)

            val decision = assertIs<NewStoryLinkDecision>(result.decision)
            assertEquals(closed.id, decision.candidateStoryId)
            assertEquals(closed.id, store.stories.getValue(result.storyId).parentStoryId)
            assertEquals(1, store.stories.getValue(closed.id).articleCount)
            assertTrue(store.attachCalls.isEmpty())
        }

        @Test
        @DisplayName("기사 상한에 닿은 story는 병합 대상이 아니며 새 story의 부모가 된다")
        fun linkFullStoryAsParent() = runBlocking {
            val full = seedStory(high, high)

            val result = service(assemblyPolicy(maxArticles = 2)).assemble(command)

            assertIs<NewStoryLinkDecision>(result.decision)
            assertEquals(full.id, store.stories.getValue(result.storyId).parentStoryId)
            assertEquals(2, store.stories.getValue(full.id).articleCount)
        }
    }

    @Nested
    @DisplayName("자동 병합")
    inner class AutoMerge {

        @Test
        @DisplayName("θ_high 이상이면 judge 없이 붙이고 story의 centroid·기사 수·version이 바뀐다")
        fun mergeAboveThetaHigh() = runBlocking {
            val target = seedStory(high)

            val result = service().assemble(command)

            val decision = assertIs<AutoMergedLinkDecision>(result.decision)
            assertEquals(target.id, decision.storyId)
            assertEquals(0.949, decision.similarity, TOLERANCE)
            assertEquals(target.id, result.storyId)
            assertTrue(judge.scoredSubjects.isEmpty())

            val (written, expectedVersion) = store.attachCalls.single()
            assertEquals(0, expectedVersion)
            assertEquals(1, written.version)
            val stored = store.stories.getValue(target.id)
            assertEquals(2, stored.articleCount)
            assertEquals(embedding(0.875f, 0.125f), stored.centroid)
            assertEquals(target.id, index.points.getValue(NEWS_ID).storyId)
        }

        @Test
        @DisplayName("outbox payload는 serializer 결과이고 기사 수는 붙인 뒤의 값이다")
        fun writeOutboxWithSerializedPayload() = runBlocking {
            seedStory(high)

            service().assemble(command)

            val event = serializer.serialized.single() as me.rgunny.kachi.story.application.port.outbound.outbox.model.StoryArticleAttachedEvent
            assertEquals(2, event.storyArticleCount)
            assertEquals(NEWS_ID.value, event.newsId)
            assertEquals(FakeStoryOutboxEventSerializer.payloadOf(event), store.outboxes.single().payload)
        }

        @Test
        @DisplayName("centroid가 낮아도 최근 기사 하나가 θ_high 위면 그 점수로 붙인다")
        fun scoreByBestRecentArticle() = runBlocking {
            val target = seedStory(orthogonal, orthogonal, incoming)
            assertTrue(target.centroid.cosine(incoming) < 0.60)

            val result = service().assemble(command)

            val decision = assertIs<AutoMergedLinkDecision>(result.decision)
            assertEquals(target.id, decision.storyId)
            assertEquals(1.0, decision.similarity, TOLERANCE)
        }
    }

    @Nested
    @DisplayName("judge")
    inner class Judge {

        @Test
        @DisplayName("회색 구간이면 최대 유사도를 낸 기사와 그 점수를 judge에 넣고 θ_judge 이상이면 붙인다")
        fun mergeWhenJudgeAccepts() = runBlocking {
            val target = seedStory(gray)
            val grayArticle = store.articles.values.single()
            judge.scores[grayArticle.embeddingText] = 0.8

            val result = service().assemble(command)

            val decision = assertIs<JudgedLinkDecision>(result.decision)
            assertTrue(decision.merged)
            assertEquals(target.id, decision.candidateStoryId)
            assertEquals(StoryJudge.BGE_RERANKER_V2_M3, decision.judge)
            assertEquals(0.8, decision.judgeScore)
            assertEquals(0.65, decision.similarity, TOLERANCE)
            assertEquals(target.id, result.storyId)

            assertEquals(command.embeddingText, judge.scoredSubjects.single())
            val candidate = judge.scoredCandidates.single().single()
            assertEquals(grayArticle.embeddingText, candidate.text)
            assertEquals(0.65, candidate.similarity, TOLERANCE)
        }

        @Test
        @DisplayName("판정이 θ_judge 아래면 새 story를 열고 판정 결과를 기사에 남긴다")
        fun openStoryWhenJudgeRejects() = runBlocking {
            val candidate = seedStory(gray)
            judge.defaultScore = 0.1

            val result = service().assemble(command)

            val decision = assertIs<JudgedLinkDecision>(result.decision)
            assertFalse(decision.merged)
            assertEquals(candidate.id, decision.candidateStoryId)
            assertEquals(0.1, decision.judgeScore)
            assertNotEquals(candidate.id, result.storyId)
            assertEquals(1, store.openStoryCalls.size)
            assertNull(store.stories.getValue(result.storyId).parentStoryId)
        }

        @Test
        @DisplayName("회색 후보가 여럿이면 한 번에 판정하고 가장 높은 판정을 받은 story에 붙인다")
        fun judgeAllGrayCandidatesOnce() = runBlocking {
            val first = seedStory(gray)
            val second = seedStory(gray2)
            judge.scores[store.articles.values.first { it.storyId == first.id }.embeddingText] = 0.2
            judge.scores[store.articles.values.first { it.storyId == second.id }.embeddingText] = 0.9

            val result = service().assemble(command)

            val decision = assertIs<JudgedLinkDecision>(result.decision)
            assertEquals(second.id, decision.candidateStoryId)
            assertEquals(0.9, decision.judgeScore)
            assertEquals(1, judge.scoredSubjects.size)
            assertEquals(2, judge.scoredCandidates.single().size)
        }
    }

    @Nested
    @DisplayName("재전달과 경합")
    inner class ReplayAndConflict {

        @Test
        @DisplayName("이미 저장된 기사는 임베딩 없이 색인만 다시 쓰고 replayed로 끝난다")
        fun replayStoredArticle() = runBlocking {
            val target = seedStory(high)
            val stored = article(newsId = NEWS_ID, storyId = target.id, embedding = incoming)
            store.articles[NEWS_ID] = stored

            val result = service().assemble(command)

            assertTrue(result.replayed)
            assertEquals(target.id, result.storyId)
            assertEquals(stored.decision, result.decision)
            assertTrue(embeddingPort.embeddedTexts.isEmpty())
            assertEquals(target.id, index.points.getValue(NEWS_ID).storyId)
            assertTrue(store.outboxes.isEmpty())
        }

        @Test
        @DisplayName("story가 그 사이 바뀌면 임베딩은 두고 후보 검색부터 다시 한다")
        fun retryFromSearchWhenStoryChanged() = runBlocking {
            val target = seedStory(high)
            store.attachOutcomes += AttachOutcome.STORY_CHANGED

            val result = service().assemble(command)

            assertFalse(result.replayed)
            assertEquals(target.id, result.storyId)
            assertEquals(1, embeddingPort.embeddedTexts.size)
            assertEquals(2, index.searches.size)
            assertEquals(2, store.attachCalls.size)
            assertEquals(2, store.stories.getValue(target.id).articleCount)
        }

        @Test
        @DisplayName("경합이 재시도 한도 안에 풀리지 않으면 예외로 끝내고 색인은 건드리지 않는다")
        fun failWhenCasRetriesExhausted() = runBlocking {
            seedStory(high)
            store.attachOutcomes += AttachOutcome.STORY_CHANGED
            store.attachOutcomes += AttachOutcome.STORY_CHANGED

            val error = assertFailsWith<StoryAssemblyException> {
                service(assemblyPolicy(maxCasRetries = 1)).assemble(command)
            }

            assertEquals(StoryAssemblyErrorCode.ASSEMBLY_CONFLICT_EXHAUSTED, error.errorCode)
            assertEquals(2, store.attachCalls.size)
            assertEquals(1, index.points.size)
            assertFalse(index.points.containsKey(NEWS_ID))
        }

        @Test
        @DisplayName("쓰기가 중복으로 거부되면 다른 쪽이 저장한 기사를 읽어 색인만 맞춘다")
        fun replayWhenWriteIsDuplicated() = runBlocking {
            val target = seedStory(high)
            val concurrent = article(newsId = NEWS_ID, storyId = target.id, embedding = incoming)
            store.attachOutcomes += AttachOutcome.DUPLICATED
            store.beforeWrite = { store.articles[NEWS_ID] = concurrent }

            val result = service().assemble(command)

            assertTrue(result.replayed)
            assertEquals(target.id, result.storyId)
            assertEquals(target.id, index.points.getValue(NEWS_ID).storyId)
            assertTrue(store.outboxes.isEmpty())
        }
    }

    @Nested
    @DisplayName("병합 치환")
    inner class MergedSubstitution {

        @Test
        @DisplayName("흡수된 story를 가리키는 후보는 살아남은 story로 치환하고 색인을 고친다")
        fun substituteMergedCandidateAndHealIndex() = runBlocking {
            val source = seedStory(high)
            val target = seedStory(high)
            simulateMerge(source = source, target = target)

            val result = service().assemble(command)

            val decision = assertIs<AutoMergedLinkDecision>(result.decision)
            assertEquals(target.id, decision.storyId)
            assertEquals(target.id, result.storyId)
            assertTrue(index.points.values.all { it.storyId == target.id })
            assertEquals(3, store.stories.getValue(target.id).articleCount)
        }

        @Test
        @DisplayName("두 번 흡수된 계보도 끝까지 따라가 치환한다")
        fun followMergeChainToTerminal() = runBlocking {
            val first = seedStory(high)
            val second = seedStory(high)
            val terminal = seedStory(high)
            simulateMerge(source = first, target = second)
            simulateMerge(source = second, target = terminal)

            val result = service().assemble(command)

            assertEquals(terminal.id, result.storyId)
            assertTrue(index.points.values.all { it.storyId == terminal.id })
        }

        @Test
        @DisplayName("색인 수리가 실패해도 치환된 story로 조립은 끝난다")
        fun keepAssemblyWhenHealFails() = runBlocking {
            val source = seedStory(high)
            val target = seedStory(high)
            simulateMerge(source = source, target = target)
            val service = AssembleStoryService(
                embeddingPort = embeddingPort,
                candidateIndexPort = FailingReassignIndexPort(index),
                storyLinkJudge = judge,
                storyPersistencePort = store,
                storyArticlePersistencePort = store,
                storyAssemblyPersistencePort = store,
                eventSerializer = serializer,
                policy = assemblyPolicy(),
                clock = StoryTestFixture.CLOCK
            )

            val result = service.assemble(command)

            assertEquals(target.id, result.storyId)
            assertTrue(index.points.values.any { it.storyId == source.id })
        }

        @Test
        @DisplayName("계보의 대상이 없으면 그 후보를 버리고 새 story를 연다")
        fun dropCandidateWhenChainTargetMissing() = runBlocking {
            val source = seedStory(high)
            val ghost = StoryTestFixture.story(article(newsId = NewsId.of(UUID.randomUUID()), embedding = high, storyId = StoryId.newId()))
            store.stories[source.id] = source.mergeInto(ghost, NOW)

            val result = service().assemble(command)

            assertIs<NewStoryLinkDecision>(result.decision)
            assertNotEquals(source.id, result.storyId)
            assertEquals(1, store.openStoryCalls.size)
        }

        /**
         * 병합 트랜잭션 커밋 뒤 색인 이전만 남은 상태를 만든다. 색인은 일부러 옛 storyId로 둔다.
         */
        private fun simulateMerge(source: Story, target: Story) {
            val storedSource = store.stories.getValue(source.id)
            val storedTarget = store.stories.getValue(target.id)
            store.stories[target.id] = storedTarget.absorb(storedSource, NOW)
            store.stories[source.id] = storedSource.mergeInto(storedTarget, NOW)
            store.articles.values
                .filter { it.storyId == source.id }
                .forEach { store.articles[it.newsId] = it.reassign(target.id) }
        }
    }

    @Nested
    @DisplayName("호출 계약")
    inner class Calls {

        @Test
        @DisplayName("후보 검색은 정책의 창과 후보 수로, 언어 필터 없이 한다")
        fun searchWithPolicyWindowAndLimit() = runBlocking {
            service(assemblyPolicy(candidateWindow = Duration.ofHours(48), candidateLimit = 7)).assemble(command)

            val query = index.searches.single()
            assertEquals(NOW.minus(Duration.ofHours(48)), query.collectedAfter)
            assertEquals(7, query.limit)
            assertNull(query.language)
            assertEquals(incoming, query.embedding)
        }

        @Test
        @DisplayName("임베딩이 실패하면 예외를 그대로 올리고 아무것도 쓰지 않는다")
        fun propagateEmbeddingFailure() = runBlocking {
            embeddingPort.failure = IllegalStateException("tei down")

            assertFailsWith<IllegalStateException> { service().assemble(command) }

            assertTrue(store.stories.isEmpty())
            assertTrue(store.outboxes.isEmpty())
            assertTrue(index.points.isEmpty())
            assertTrue(index.searches.isEmpty())
        }
    }

    private fun service(policy: AssemblyPolicy = assemblyPolicy()): AssembleStoryService {
        return AssembleStoryService(
            embeddingPort = embeddingPort,
            candidateIndexPort = index,
            storyLinkJudge = judge,
            storyPersistencePort = store,
            storyArticlePersistencePort = store,
            storyAssemblyPersistencePort = store,
            eventSerializer = serializer,
            policy = policy,
            clock = StoryTestFixture.CLOCK
        )
    }

    /**
     * 주어진 벡터의 기사들로 story 하나를 저장소와 색인에 심는다.
     */
    private suspend fun seedStory(vararg embeddings: Embedding): Story {
        val storyId = StoryId.newId()
        val articles = embeddings.mapIndexed { i, embedding ->
            article(
                newsId = NewsId.of(UUID.randomUUID()),
                title = "후보 기사 ${storyId.value.toString().takeLast(4)}-$i",
                excerpt = "후보 발췌문 $i",
                embedding = embedding,
                storyId = storyId,
                attachedAt = NOW.minus(Duration.ofMinutes((embeddings.size - i).toLong()))
            )
        }
        val story = articles.drop(1).fold(Story.open(articles.first(), NOW)) { acc, article -> acc.attach(article, NOW) }
        store.seed(story, *articles.toTypedArray())
        index.upsert(articles.map { IndexedArticle.from(it) })

        return story
    }

    /** story 단위 이전이 항상 실패하는 색인. */
    private class FailingReassignIndexPort(
        private val delegate: InMemoryCandidateIndexPort
    ) : CandidateIndexPort by delegate {

        override suspend fun reassignStory(from: StoryId, to: StoryId) {
            throw IllegalStateException("index unavailable")
        }
    }

    private companion object {
        const val TOLERANCE = 0.005
    }
}
