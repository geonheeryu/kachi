package me.rgunny.kachi.story.application.service.assembly

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.exception.StoryAssemblyErrorCode
import me.rgunny.kachi.story.application.exception.StoryAssemblyException
import me.rgunny.kachi.story.application.port.inbound.assembly.AssembleStoryUseCase
import me.rgunny.kachi.story.application.port.inbound.assembly.model.AssembleStoryResult
import me.rgunny.kachi.story.application.port.inbound.assembly.model.AttachArticleCommand
import me.rgunny.kachi.story.application.port.outbound.embedding.EmbeddingPort
import me.rgunny.kachi.story.application.port.outbound.index.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.index.model.CandidateHit
import me.rgunny.kachi.story.application.port.outbound.index.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.index.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.judge.StoryLinkJudge
import me.rgunny.kachi.story.application.port.outbound.judge.model.JudgeCandidate
import me.rgunny.kachi.story.application.port.outbound.outbox.StoryOutboxEventSerializer
import me.rgunny.kachi.story.application.port.outbound.outbox.model.StoryArticleAttachedEvent
import me.rgunny.kachi.story.application.port.outbound.outbox.model.toOutbox
import me.rgunny.kachi.story.application.port.outbound.story.StoryArticlePersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryAssemblyPersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.model.AttachOutcome
import me.rgunny.kachi.story.domain.AutoMergedLinkDecision
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.JudgedLinkDecision
import me.rgunny.kachi.story.domain.LinkDecision
import me.rgunny.kachi.story.domain.NewStoryLinkDecision
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.outbox.StoryOutbox
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 기사 한 건을 같은 사건의 story에 붙이거나 새 story를 여는 유스케이스.
 *
 * 한 기사의 처리는 "재전달 확인 -> 임베딩 -> 후보 검색·판정 -> 트랜잭션 쓰기 -> 색인 갱신" 순서다.
 * 쓰기가 story 갱신 경합으로 밀리면 임베딩은 두고 검색·판정부터 다시 하며, 한도를 넘기면 예외로 끝내 소비자의 재전달에 맡긴다.
 * 색인 갱신 전에 멈춘 기사는 재전달에서 저장된 기사를 찾아 색인만 다시 쓴다.
 * 흡수된 story를 가리키는 후보는 살아남은 story로 치환하고, 그 자리에서 낡은 색인 항목을 고친다.
 */
@Service
class AssembleStoryService(
    private val embeddingPort: EmbeddingPort,
    private val candidateIndexPort: CandidateIndexPort,
    private val storyLinkJudge: StoryLinkJudge,
    private val storyPersistencePort: StoryPersistencePort,
    private val storyArticlePersistencePort: StoryArticlePersistencePort,
    private val storyAssemblyPersistencePort: StoryAssemblyPersistencePort,
    private val eventSerializer: StoryOutboxEventSerializer,
    private val policy: AssemblyPolicy,
    private val clock: Clock
) : AssembleStoryUseCase {

    override suspend fun assemble(command: AttachArticleCommand): AssembleStoryResult {
        val now = Instant.now(clock)

        // 1. 이미 저장된 기사면 색인만 맞추고 끝낸다. 색인 갱신 전에 멈춘 재전달을 여기서 복구한다.
        storyArticlePersistencePort.findByNewsId(command.newsId)?.let { return replay(it) }

        // 2. 임베딩은 기사당 한 번이다. 아래 경합 재시도에서 다시 부르지 않는다.
        val embedding = embeddingPort.embed(listOf(command.embeddingText)).single()

        // 3. 판정과 쓰기. story가 그 사이 바뀌었으면 후보 검색부터 다시 한다.
        repeat(policy.maxCasRetries + 1) { attempt ->
            val placement = decide(command, embedding, now)
            val article = articleOf(command, embedding, placement, now)

            when (write(placement, article, now)) {
                AttachOutcome.ATTACHED -> {
                    candidateIndexPort.upsert(listOf(IndexedArticle.from(article)))
                    logAttached(article, attempt)

                    return AssembleStoryResult(
                        newsId = article.newsId,
                        storyId = article.storyId,
                        decision = article.decision,
                        replayed = false
                    )
                }

                AttachOutcome.DUPLICATED -> {
                    val stored = checkNotNull(storyArticlePersistencePort.findByNewsId(command.newsId)) {
                        "중복으로 거부된 기사가 저장소에 없습니다: ${command.newsId.value}"
                    }

                    return replay(stored)
                }

                AttachOutcome.STORY_CHANGED -> log.warn(
                    "Story changed while attaching, retrying from candidate search: newsId={}, storyId={}, attempt={}",
                    command.newsId.value,
                    article.storyId.value,
                    attempt + 1
                )
            }
        }

        throw StoryAssemblyException(
            errorCode = StoryAssemblyErrorCode.ASSEMBLY_CONFLICT_EXHAUSTED,
            detail = "newsId=${command.newsId.value}, retries=${policy.maxCasRetries}"
        )
    }

    /**
     * 후보 story를 점수순으로 세워 붙일 곳을 정한다.
     *
     * 병합 대상은 열려 있고 상한 아래인 story다. 그 밖의 story는 새 story의 부모를 고르는 데만 쓴다.
     */
    private suspend fun decide(command: AttachArticleCommand, embedding: Embedding, now: Instant): ArticlePlacement {
        val hits = candidateIndexPort.search(
            CandidateQuery(
                embedding = embedding,
                collectedAfter = now.minus(policy.candidateWindow),
                limit = policy.candidateLimit
            )
        )
        val candidates = score(hits, embedding)
        val (eligible, blocked) = candidates.partition { it.story.acceptsMore(policy.maxArticles) }
        val parentStoryId = blocked.firstOrNull { it.score >= policy.thetaHigh }?.story?.id

        val top = eligible.firstOrNull()
            ?: return newStory(candidates.firstOrNull(), parentStoryId)

        if (top.score >= policy.thetaHigh) {
            return ArticlePlacement(
                decision = AutoMergedLinkDecision(storyId = top.story.id, similarity = top.score),
                target = top.story,
                parentStoryId = null
            )
        }

        val gray = eligible.filter { it.score >= policy.thetaLow }
        if (gray.isEmpty()) {
            return newStory(top, parentStoryId)
        }

        return judge(command, gray, parentStoryId)
    }

    /**
     * 회색 구간 후보 전부를 judge에 한 번에 넣고 가장 높은 판정을 받은 후보를 고른다.
     */
    private suspend fun judge(
        command: AttachArticleCommand,
        gray: List<CandidateStory>,
        parentStoryId: StoryId?
    ): ArticlePlacement {
        val scores = storyLinkJudge.score(
            subject = command.embeddingText,
            candidates = gray.map { JudgeCandidate(text = it.bestArticle.embeddingText, similarity = it.score) }
        )
        check(scores.size == gray.size) { "판정 점수 수가 후보 수와 다릅니다: scores=${scores.size}, candidates=${gray.size}" }

        val (best, judgeScore) = gray.zip(scores).maxBy { (_, judgeScore) -> judgeScore }
        val merged = judgeScore >= policy.thetaJudge
        val decision = JudgedLinkDecision(
            candidateStoryId = best.story.id,
            similarity = best.score,
            judge = storyLinkJudge.judge,
            judgeScore = judgeScore,
            merged = merged
        )

        return ArticlePlacement(
            decision = decision,
            target = if (merged) best.story else null,
            parentStoryId = if (merged) null else parentStoryId
        )
    }

    private fun newStory(best: CandidateStory?, parentStoryId: StoryId?): ArticlePlacement {
        return ArticlePlacement(
            decision = NewStoryLinkDecision(candidateStoryId = best?.story?.id, similarity = best?.score),
            target = null,
            parentStoryId = parentStoryId
        )
    }

    /**
     * 검색 결과를 story 단위로 묶어 점수를 매기고 점수 내림차순으로 돌려준다.
     */
    private suspend fun score(hits: List<CandidateHit>, embedding: Embedding): List<CandidateStory> {
        if (hits.isEmpty()) {
            return emptyList()
        }

        return resolveMerged(hits.groupBy { it.storyId })
            .filter { (story, _) -> story.centroid.model == embedding.model }
            .mapNotNull { (story, storyHits) -> candidate(story, storyHits, embedding) }
            .sortedByDescending { it.score }
    }

    /**
     * 흡수된 story를 살아남은 story로 치환하고, 치환 끝이 같은 story로 모인 후보 묶음은 하나로 합친다.
     *
     * 치환이 일어난 원 story의 색인 항목은 그 자리에서 살아남은 story로 고친다.
     */
    private suspend fun resolveMerged(hitsByStory: Map<StoryId, List<CandidateHit>>): List<Pair<Story, List<CandidateHit>>> {
        val storiesById = mutableMapOf<StoryId, Story>()
        val hitsById = linkedMapOf<StoryId, MutableList<CandidateHit>>()

        for (story in storyPersistencePort.findByIds(hitsByStory.keys)) {
            val terminal = followMergeChain(story) ?: continue
            storiesById[terminal.id] = terminal
            hitsById.getOrPut(terminal.id) { mutableListOf() } += hitsByStory.getValue(story.id)
            if (terminal.id != story.id) {
                healIndex(from = story.id, to = terminal.id)
            }
        }

        return hitsById.map { (id, storyHits) -> storiesById.getValue(id) to storyHits }
    }

    /**
     * `mergedInto` 를 끝까지 따라간다.
     *
     * story가 없으면 null이고, 호출한 쪽은 그 후보를 버린다.
     */
    private suspend fun followMergeChain(story: Story): Story? {
        var cursor = story
        while (true) {
            val nextId = cursor.mergedInto ?: return cursor
            val next = storyPersistencePort.findById(nextId)
            if (next == null) {
                log.warn(
                    "Merge chain target missing, dropping candidate: storyId={}, missingId={}",
                    story.id.value,
                    nextId.value
                )
                return null
            }
            cursor = next
        }
    }

    /**
     * 비교 기사는 story의 최근 기사와 검색이 돌려준 그 story의 기사를 합친 것이다.
     */
    private suspend fun candidate(story: Story, storyHits: List<CandidateHit>, embedding: Embedding): CandidateStory? {
        val recent = storyArticlePersistencePort.findRecentByStory(story.id, policy.recentArticles)
        val recentIds = recent.map { it.newsId }.toSet()
        val hitArticles = storyHits
            .filter { it.newsId !in recentIds }
            .mapNotNull { storyArticlePersistencePort.findByNewsId(it.newsId) }

        val bestArticle = (recent + hitArticles).maxByOrNull { embedding.cosine(it.embedding) } ?: return null
        val score = maxOf(story.centroid.cosine(embedding), embedding.cosine(bestArticle.embedding))

        return CandidateStory(story = story, score = score, bestArticle = bestArticle)
    }

    /**
     * 기사·story·outbox를 한 트랜잭션으로 쓴다.
     */
    private suspend fun write(placement: ArticlePlacement, article: StoryArticle, now: Instant): AttachOutcome {
        val target = placement.target
            ?: run {
                val story = Story.open(article, now, placement.parentStoryId)

                return storyAssemblyPersistencePort.openStory(story, article, outboxOf(story, article, now))
            }

        val updated = target.attach(article, now)

        return storyAssemblyPersistencePort.attach(
            article = article,
            story = updated,
            expectedVersion = target.version,
            outbox = outboxOf(updated, article, now)
        )
    }

    /**
     * 실패해도 조립을 멈추지 않는다. 다음 읽기가 같은 항목을 다시 고친다.
     */
    private suspend fun healIndex(from: StoryId, to: StoryId) {
        runCatching { candidateIndexPort.reassignStory(from, to) }
            .onFailure { error ->
                log.warn(
                    "Failed to heal stale index entries of merged story: storyId={}, survivorId={}",
                    from.value,
                    to.value,
                    error
                )
            }
    }

    private suspend fun replay(stored: StoryArticle): AssembleStoryResult {
        candidateIndexPort.upsert(listOf(IndexedArticle.from(stored)))
        log.info(
            "Article replayed, index refreshed: newsId={}, storyId={}",
            stored.newsId.value,
            stored.storyId.value
        )

        return AssembleStoryResult(
            newsId = stored.newsId,
            storyId = stored.storyId,
            decision = stored.decision,
            replayed = true
        )
    }

    private fun decisionName(decision: LinkDecision): String {
        return when (decision) {
            is AutoMergedLinkDecision -> "AUTO_MERGED"
            is JudgedLinkDecision -> if (decision.merged) "JUDGED_MERGED" else "JUDGED_NEW_STORY"
            is NewStoryLinkDecision -> "NEW_STORY"
        }
    }

    private fun articleOf(
        command: AttachArticleCommand,
        embedding: Embedding,
        placement: ArticlePlacement,
        now: Instant
    ): StoryArticle {
        return StoryArticle.create(
            newsId = command.newsId,
            title = command.title,
            excerpt = command.excerpt,
            url = command.url,
            source = command.source,
            language = command.language,
            publishedAt = command.publishedAt,
            collectedAt = command.collectedAt,
            matchedKeywords = command.matchedKeywords,
            embedding = embedding,
            storyId = placement.target?.id ?: StoryId.newId(),
            decision = placement.decision,
            attachedAt = now
        )
    }

    private fun outboxOf(story: Story, article: StoryArticle, now: Instant): StoryOutbox {
        val event = StoryArticleAttachedEvent.from(story, article)

        return event.toOutbox(payload = eventSerializer.serialize(event), now = now)
    }

    private fun logAttached(article: StoryArticle, attempt: Int) {
        log.info(
            "Article attached: newsId={}, storyId={}, decision={}, similarity={}, judgeScore={}, casRetries={}",
            article.newsId.value,
            article.storyId.value,
            decisionName(article.decision),
            article.decision.similarity,
            (article.decision as? JudgedLinkDecision)?.judgeScore,
            attempt
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(AssembleStoryService::class.java)
    }
}
