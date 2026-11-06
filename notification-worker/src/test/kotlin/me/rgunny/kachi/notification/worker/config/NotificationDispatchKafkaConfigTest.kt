package me.rgunny.kachi.notification.worker.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import kotlin.test.assertEquals

/**
 * dispatch listener 전용 container factory의 조립을 본다.
 *
 * listener는 처리 결과를 보고 직접 acknowledge()하므로 factory가 MANUAL이 아니면 모든 레코드가 처리 전에 실패한다.
 * `spring.kafka.listener.ack-mode`는 직접 만든 factory에 적용되지 않는다(ADR 029).
 */
class NotificationDispatchKafkaConfigTest {

    @Test
    @DisplayName("dispatch listener factory는 MANUAL ack 모드로 조립된다")
    fun buildFactoryWithManualAck() {
        val factory = NotificationDispatchKafkaConfig().notificationDispatchKafkaListenerContainerFactory(
            consumerFactory = DefaultKafkaConsumerFactory<String, String>(emptyMap<String, Any>()),
            notificationDispatchErrorHandler = DefaultErrorHandler(),
        )

        assertEquals(ContainerProperties.AckMode.MANUAL, factory.containerProperties.ackMode)
    }
}
