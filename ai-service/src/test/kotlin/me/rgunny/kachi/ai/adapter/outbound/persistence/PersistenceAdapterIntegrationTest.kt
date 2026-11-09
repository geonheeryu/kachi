package me.rgunny.kachi.ai.adapter.outbound.persistence

import me.rgunny.kachi.ai.AiServiceTestContainersConfig
import me.rgunny.kachi.ai.adapter.outbound.persistence.keyword.KeywordExpansionPersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.outbox.AiOutboxPersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.quarantine.StoryQuarantinePersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.run.AiRunPersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.story.AiStoryArticlePersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.story.AiStoryPersistenceAdapter
import me.rgunny.kachi.ai.adapter.outbound.persistence.summary.StorySummaryPersistenceAdapter
import me.rgunny.kachi.ai.config.AiMongoTransactionConfig
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataMongoTest
@Import(
    AiRunPersistenceAdapter::class,
    KeywordExpansionPersistenceAdapter::class,
    AiStoryPersistenceAdapter::class,
    AiStoryArticlePersistenceAdapter::class,
    StorySummaryPersistenceAdapter::class,
    StoryQuarantinePersistenceAdapter::class,
    AiOutboxPersistenceAdapter::class,
    AiMongoTransactionConfig::class,
    AiServiceTestContainersConfig::class
)
abstract class PersistenceAdapterIntegrationTest
