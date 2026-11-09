package me.rgunny.kachi.story.application.port.outbound.candidateindex.model

import java.time.Instant
import me.rgunny.kachi.story.domain.ArticleLanguage
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId

/**
 * 색인에 넣는 기사 한 건.
 */
data class IndexedArticle(
    val newsId: NewsId,
    val embedding: Embedding,
    val storyId: StoryId,
    val collectedAt: Instant,
    val language: ArticleLanguage
) {
    companion object {

        fun from(article: StoryArticle): IndexedArticle {
            return IndexedArticle(
                newsId = article.newsId,
                embedding = article.embedding,
                storyId = article.storyId,
                collectedAt = article.collectedAt,
                language = article.language
            )
        }
    }
}
