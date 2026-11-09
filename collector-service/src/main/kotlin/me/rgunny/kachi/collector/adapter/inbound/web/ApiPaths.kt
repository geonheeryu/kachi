package me.rgunny.kachi.collector.adapter.inbound.web

/**
 * collector-service HTTP API Path Contract.
 *
 * Controller mapping은 리소스 경로만 선언하고, API version prefix는 ApiVersionConfig에서 붙인다.
 */
object ApiPaths {
    const val INTERNAL_COLLECTIONS_NEWS = "/internal/collections/news"
    const val INTERNAL_NEWS_PROVIDER_HEALTH = "/internal/providers/news/{source}/health"
    const val INTERNAL_COLLECTOR_OUTBOXES = "/internal/collector/outboxes"
    const val INTERNAL_COLLECTOR_OUTBOX_RECOVER = "/internal/collector/outboxes/{outboxId}/recover"

    const val V1_INTERNAL_COLLECTIONS_NEWS = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_COLLECTIONS_NEWS"
    const val V1_INTERNAL_NEWS_PROVIDER_HEALTH = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_NEWS_PROVIDER_HEALTH"
    const val V1_INTERNAL_COLLECTOR_OUTBOXES = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_COLLECTOR_OUTBOXES"
    const val V1_INTERNAL_COLLECTOR_OUTBOX_RECOVER = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_COLLECTOR_OUTBOX_RECOVER"
}
