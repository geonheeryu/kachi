package me.rgunny.kachi.notification.service.config

import java.time.Clock
import me.rgunny.kachi.notification.application.port.inbound.admin.NotificationAdminUseCase
import me.rgunny.kachi.notification.application.port.inbound.dispatch.PublishNotificationDispatchUseCase
import me.rgunny.kachi.notification.application.port.inbound.dlt.NotificationDltMessageAdminUseCase
import me.rgunny.kachi.notification.application.port.inbound.outbox.NotificationOutboxAdminUseCase
import me.rgunny.kachi.notification.application.port.inbound.request.RequestNotificationUseCase
import me.rgunny.kachi.notification.application.port.outbound.dlt.NotificationDltMessageAdminPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.idempotency.NotificationDeduplicationPort
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationDispatchPublisher
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationEventSerializer
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationAdminPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationPublishPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationRequestPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationOutboxPersistencePort
import me.rgunny.kachi.notification.application.service.NotificationAdminService
import me.rgunny.kachi.notification.application.service.NotificationDltMessageAdminService
import me.rgunny.kachi.notification.application.service.NotificationOutboxAdminService
import me.rgunny.kachi.notification.application.service.OutboxPublishPolicy
import me.rgunny.kachi.notification.application.service.PublishNotificationDispatchService
import me.rgunny.kachi.notification.application.service.RequestNotificationPolicy
import me.rgunny.kachi.notification.application.service.RequestNotificationService
import me.rgunny.kachi.notification.domain.retry.RetryPolicy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(NotificationServiceProperties::class)
class NotificationCoreConfig {

    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }

    @Bean
    fun requestNotificationPolicy(
        properties: NotificationServiceProperties
    ): RequestNotificationPolicy {
        return RequestNotificationPolicy(
            dedupeTtl = properties.request.dedupeTtl,
            dispatchTopic = properties.dispatch.topic
        )
    }

    @Bean
    fun outboxRetryPolicy(
        properties: NotificationServiceProperties,
    ): RetryPolicy {
        return RetryPolicy(
            maxAttempts = properties.outbox.retry.maxAttempts,
            baseDelay = properties.outbox.retry.baseDelay,
            maxDelay = properties.outbox.retry.maxDelay,
        )
    }

    @Bean
    fun outboxPublishPolicy(
        properties: NotificationServiceProperties,
        outboxRetryPolicy: RetryPolicy,
    ): OutboxPublishPolicy {
        return OutboxPublishPolicy(
            batchSize = properties.outbox.batchSize,
            publisherId = properties.outbox.publisherId,
            retryPolicy = outboxRetryPolicy,
            publishingVisibilityTimeout = properties.outbox.publishingVisibilityTimeout,
        )
    }

    @Bean
    fun requestNotificationUseCase(
        notificationPersistencePort: NotificationPersistencePort,
        requestPersistencePort: NotificationRequestPersistencePort,
        deduplicationPort: NotificationDeduplicationPort,
        eventSerializer: NotificationEventSerializer,
        requestNotificationPolicy: RequestNotificationPolicy,
        clock: Clock,
    ): RequestNotificationUseCase {
        return RequestNotificationService(
            notificationPersistencePort = notificationPersistencePort,
            requestPersistencePort = requestPersistencePort,
            deduplicationPort = deduplicationPort,
            eventSerializer = eventSerializer,
            policy = requestNotificationPolicy,
            clock = clock,
        )
    }

    @Bean
    fun publishNotificationDispatchUseCase(
        outboxPersistencePort: NotificationOutboxPersistencePort,
        publishPersistencePort: NotificationPublishPersistencePort,
        dispatchPublisher: NotificationDispatchPublisher,
        outboxPublishPolicy: OutboxPublishPolicy,
        clock: Clock,
    ): PublishNotificationDispatchUseCase {
        return PublishNotificationDispatchService(
            outboxPersistencePort = outboxPersistencePort,
            publishPersistencePort = publishPersistencePort,
            dispatchPublisher = dispatchPublisher,
            policy = outboxPublishPolicy,
            clock = clock,
        )
    }

    @Bean
    fun notificationOutboxAdminUseCase(
        outboxPersistencePort: NotificationOutboxPersistencePort,
        clock: Clock,
    ): NotificationOutboxAdminUseCase {
        return NotificationOutboxAdminService(
            outboxPersistencePort = outboxPersistencePort,
            clock = clock,
        )
    }

    @Bean
    fun notificationAdminUseCase(
        notificationPersistencePort: NotificationPersistencePort,
        adminPersistencePort: NotificationAdminPersistencePort,
        eventSerializer: NotificationEventSerializer,
        requestNotificationPolicy: RequestNotificationPolicy,
        clock: Clock,
    ): NotificationAdminUseCase {
        return NotificationAdminService(
            notificationPersistencePort = notificationPersistencePort,
            adminPersistencePort = adminPersistencePort,
            eventSerializer = eventSerializer,
            policy = requestNotificationPolicy,
            clock = clock,
        )
    }

    @Bean
    fun notificationDltMessageAdminUseCase(
        persistencePort: NotificationDltMessageAdminPersistencePort,
        clock: Clock,
    ): NotificationDltMessageAdminUseCase {
        return NotificationDltMessageAdminService(persistencePort, clock)
    }

}
