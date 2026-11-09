package me.rgunny.kachi.story.domain

import java.time.Instant

/**
 * 같은 사건을 다루는 기사 묶음.
 *
 * 구성 기사의 사본은 [StoryArticle]이 갖고, 여기는 기사들에서 파생한 상태만 둔다.
 * [centroid]는 구성 기사 임베딩의 산술 평균, [keywords]는 구성 기사 키워드의 합집합, [lastArticleAt]은 가장 늦은 발행 시각이다.
 *
 * ```
 * OPEN -> OPEN    (attach, absorb)
 * OPEN -> CLOSED  (close)
 * OPEN -> CLOSED  (mergeInto, mergedInto = 흡수한 story)
 * ```
 *
 * 불변이며 전이 메서드는 [version]을 하나 올린 새 인스턴스를 반환한다. 저장소는 직전 version을 조건으로 쓴다.
 */
class Story private constructor(
    val id: StoryId,
    val status: StoryStatus,
    val centroid: Embedding,
    val articleCount: Int,
    val keywords: Set<StoryKeyword>,
    val openedAt: Instant,
    val lastArticleAt: Instant,
    val closedAt: Instant?,
    val parentStoryId: StoryId?,
    val mergedInto: StoryId?,
    val version: Long
) {
    companion object {

        /** 첫 기사로 story를 연다. */
        fun open(first: StoryArticle, now: Instant, parentStoryId: StoryId? = null): Story {
            require(parentStoryId != first.storyId) { "story는 자기 자신의 후속일 수 없습니다: $parentStoryId" }

            return Story(
                id = first.storyId,
                status = StoryStatus.OPEN,
                centroid = first.embedding,
                articleCount = 1,
                keywords = first.matchedKeywords.toSet(),
                openedAt = now,
                lastArticleAt = first.publishedAt,
                closedAt = null,
                parentStoryId = parentStoryId,
                mergedInto = null,
                version = 0
            )
        }

        fun restore(
            id: StoryId,
            status: StoryStatus,
            centroid: Embedding,
            articleCount: Int,
            keywords: Set<StoryKeyword>,
            openedAt: Instant,
            lastArticleAt: Instant,
            closedAt: Instant?,
            parentStoryId: StoryId?,
            mergedInto: StoryId?,
            version: Long
        ): Story {
            require(articleCount >= 1) { "story는 기사를 하나 이상 가져야 합니다: $articleCount" }
            require(keywords.isNotEmpty()) { "story는 키워드를 하나 이상 가져야 합니다" }
            require(version >= 0) { "story version은 0 이상이어야 합니다: $version" }
            require((status == StoryStatus.CLOSED) == (closedAt != null)) { "closedAt은 CLOSED 상태에서만 존재해야 합니다" }
            require(mergedInto == null || status == StoryStatus.CLOSED) { "흡수된 story는 CLOSED여야 합니다" }
            require(mergedInto != id) { "story는 자기 자신에 흡수될 수 없습니다: $id" }
            require(parentStoryId != id) { "story는 자기 자신의 후속일 수 없습니다: $id" }

            return Story(
                id = id,
                status = status,
                centroid = centroid,
                articleCount = articleCount,
                keywords = keywords,
                openedAt = openedAt,
                lastArticleAt = lastArticleAt,
                closedAt = closedAt,
                parentStoryId = parentStoryId,
                mergedInto = mergedInto,
                version = version
            )
        }
    }

    /** 병합 대상이 될 수 있는지. 열려 있고 상한 아래여야 한다. */
    fun acceptsMore(maxArticles: Int): Boolean {
        require(maxArticles >= 1) { "story 기사 상한은 1 이상이어야 합니다: $maxArticles" }

        return status == StoryStatus.OPEN && articleCount < maxArticles
    }

    /**
     * 기사 한 건을 붙인다.
     */
    fun attach(article: StoryArticle, now: Instant): Story {
        requireOpen("기사를 붙일")
        require(article.storyId == id) { "다른 story의 기사입니다: article=${article.storyId}, story=$id" }

        return copy(
            centroid = centroid.meanWith(article.embedding, articleCount, 1),
            articleCount = articleCount + 1,
            keywords = keywords + article.matchedKeywords,
            lastArticleAt = maxOf(lastArticleAt, article.publishedAt),
            version = version + 1
        )
    }

    /**
     * story를 닫는다.
     */
    fun close(now: Instant): Story {
        requireOpen("닫을")

        return copy(
            status = StoryStatus.CLOSED,
            closedAt = now,
            version = version + 1
        )
    }

    /** 다른 story에 흡수된다. */
    fun mergeInto(target: Story, now: Instant): Story {
        requireOpen("흡수될")
        require(target.id != id) { "story는 자기 자신에 흡수될 수 없습니다: $id" }
        require(target.status == StoryStatus.OPEN) { "닫힌 story에는 흡수될 수 없습니다: ${target.id}" }

        return copy(
            status = StoryStatus.CLOSED,
            closedAt = now,
            mergedInto = target.id,
            version = version + 1
        )
    }

    /**
     * 구성 기사 전체에서 파생 상태를 다시 계산한다.
     *
     * [articles]는 분리 뒤 이 story에 남는(또는 새로 속하는) 기사 전체다.
     */
    fun recompose(articles: List<StoryArticle>, now: Instant): Story {
        requireOpen("재구성할")
        require(articles.isNotEmpty()) { "story는 기사를 하나 이상 가져야 합니다" }
        require(articles.all { it.storyId == id }) { "다른 story의 기사가 있습니다: story=$id" }

        return copy(
            centroid = centroidOf(articles),
            articleCount = articles.size,
            keywords = articles.flatMap { it.matchedKeywords }.toSet(),
            lastArticleAt = articles.maxOf { it.publishedAt },
            version = version + 1
        )
    }

    /** 다른 story를 흡수한다. */
    fun absorb(other: Story, now: Instant): Story {
        requireOpen("흡수할")
        require(other.id != id) { "story는 자기 자신을 흡수할 수 없습니다: $id" }
        require(other.status == StoryStatus.OPEN) { "닫힌 story는 흡수할 수 없습니다: ${other.id}" }

        return copy(
            centroid = centroid.meanWith(other.centroid, articleCount, other.articleCount),
            articleCount = articleCount + other.articleCount,
            keywords = keywords + other.keywords,
            lastArticleAt = maxOf(lastArticleAt, other.lastArticleAt),
            version = version + 1
        )
    }

    private fun centroidOf(articles: List<StoryArticle>): Embedding {
        return articles.map { it.embedding }
            .reduceIndexed { index, mean, embedding -> mean.meanWith(embedding, index, 1) }
    }

    private fun requireOpen(action: String) {
        check(status == StoryStatus.OPEN) { "OPEN story만 $action 수 있습니다: $status" }
    }

    private fun copy(
        status: StoryStatus = this.status,
        centroid: Embedding = this.centroid,
        articleCount: Int = this.articleCount,
        keywords: Set<StoryKeyword> = this.keywords,
        lastArticleAt: Instant = this.lastArticleAt,
        closedAt: Instant? = this.closedAt,
        mergedInto: StoryId? = this.mergedInto,
        version: Long = this.version
    ): Story {
        return Story(
            id = id,
            status = status,
            centroid = centroid,
            articleCount = articleCount,
            keywords = keywords,
            openedAt = openedAt,
            lastArticleAt = lastArticleAt,
            closedAt = closedAt,
            parentStoryId = parentStoryId,
            mergedInto = mergedInto,
            version = version
        )
    }
}
