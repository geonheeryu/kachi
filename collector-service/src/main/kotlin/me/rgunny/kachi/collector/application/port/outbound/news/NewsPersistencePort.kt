package me.rgunny.kachi.collector.application.port.outbound.news

import me.rgunny.kachi.collector.application.port.outbound.news.model.SaveNewsResult
import me.rgunny.kachi.collector.domain.News
import me.rgunny.kachi.collector.domain.outbox.CollectorOutbox
import me.rgunny.kachi.collector.domain.NewsSource

/** 수집 뉴스 저장소 접근을 application 계층에 제공하는 출력 포트 */
interface NewsPersistencePort {

    suspend fun findExistingUrlHashes(source: NewsSource, urlHashes: Set<String>): Set<String>

    /**
     * 기사와 그 기사의 발행 대기 행을 한 트랜잭션으로 저장한다. 같은 출처·URL이 이미 있으면 둘 다 저장하지 않는다.
     */
    suspend fun save(news: News, outbox: CollectorOutbox): SaveNewsResult
}
