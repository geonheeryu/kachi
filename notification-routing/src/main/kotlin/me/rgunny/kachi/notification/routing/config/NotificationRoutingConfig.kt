package me.rgunny.kachi.notification.routing.config

import java.time.Clock
import me.rgunny.kachi.notification.routing.adapter.outbound.messaging.KafkaNotificationRequestPublisher
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.UserServiceRecipientReaderAdapter
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.UserServiceRecipientReaderSettings
import me.rgunny.kachi.notification.routing.application.port.outbound.job.RoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.NotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.RecipientReaderPort
import me.rgunny.kachi.notification.routing.application.service.RouteNotificationService
import me.rgunny.kachi.notification.routing.application.service.RoutingPolicy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper

/**
 * 라우팅 유스케이스와 outbound 어댑터(user-service 클라이언트, 접수 topic publisher)를 조립한다.
 */
@Configuration
@EnableConfigurationProperties(NotificationRoutingProperties::class)
class NotificationRoutingConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun routingPolicy(properties: NotificationRoutingProperties): RoutingPolicy {
        return RoutingPolicy(requester = ROUTING_REQUESTER)
    }

    @Bean
    fun userServiceWebClient(properties: NotificationRoutingProperties): WebClient {
        return WebClient.builder()
            .baseUrl(properties.userService.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(properties.userService.maxInMemorySize) }
            .build()
    }

    @Bean
    fun recipientReaderPort(
        userServiceWebClient: WebClient,
        properties: NotificationRoutingProperties,
    ): RecipientReaderPort {
        return UserServiceRecipientReaderAdapter(
            webClient = userServiceWebClient,
            settings = UserServiceRecipientReaderSettings(
                recipientsSearchPath = properties.userService.recipientsSearchPath,
                pageSize = properties.userService.pageSize,
                usersPath = properties.userService.usersPath,
                timeout = properties.userService.timeout,
            ),
        )
    }

    @Bean
    fun notificationRequestPublisherPort(
        kafkaTemplate: KafkaTemplate<String, String>,
        jsonMapper: JsonMapper,
        properties: NotificationRoutingProperties,
    ): NotificationRequestPublisherPort {
        return KafkaNotificationRequestPublisher(
            kafkaTemplate = kafkaTemplate,
            jsonMapper = jsonMapper,
            topic = properties.requestTopic,
        )
    }

    @Bean
    fun routeNotificationService(
        routingJobPersistencePort: RoutingJobPersistencePort,
        recipientReaderPort: RecipientReaderPort,
        notificationRequestPublisherPort: NotificationRequestPublisherPort,
        routingPolicy: RoutingPolicy,
        clock: Clock,
    ): RouteNotificationService {
        return RouteNotificationService(
            routingJobPersistencePort = routingJobPersistencePort,
            recipientReaderPort = recipientReaderPort,
            notificationRequestPublisherPort = notificationRequestPublisherPort,
            policy = routingPolicy,
            clock = clock,
        )
    }

    private companion object {
        const val ROUTING_REQUESTER = "notification-routing"
    }
}
