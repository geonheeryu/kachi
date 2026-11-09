package me.rgunny.kachi.notification.service.adapter.outbound.outbox

import me.rgunny.kachi.notification.application.port.outbound.outbox.model.NotificationDispatchMessage
import me.rgunny.kachi.notification.contract.NotificationChannel as ContractNotificationChannel
import me.rgunny.kachi.notification.contract.NotificationDispatchEvent
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals

@DisplayName("JacksonNotificationEventSerializer")
class JacksonNotificationEventSerializerTest {

    private val jsonMapper = JsonMapper.builder().findAndAddModules().build()
    private val serializer = JacksonNotificationEventSerializer(jsonMapper)

    @Test
    @DisplayName("dispatch message를 notification.dispatch Kafka 계약으로 직렬화한다")
    fun serializeDispatch() {
        val notificationId = NotificationId.of(UUID.randomUUID())

        val payload = serializer.serializeDispatch(
            NotificationDispatchMessage(
                notificationId = notificationId,
                requestId = "request-1",
                channel = NotificationChannel.SLACK,
                recipientId = "user-1",
                message = "hello",
            )
        )

        val event = jsonMapper.readValue(payload, NotificationDispatchEvent::class.java)
        assertEquals(NotificationDispatchEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion)
        assertEquals(notificationId.id.toString(), event.notificationId)
        assertEquals("request-1", event.requestId)
        assertEquals(ContractNotificationChannel.SLACK, event.channel)
        assertEquals("user-1", event.recipientId)
        assertEquals("hello", event.message)
    }
}
