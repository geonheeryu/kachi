package me.rgunny.kachi.notification.service.adapter.outbound.outbox

import me.rgunny.kachi.notification.application.port.outbound.outbox.model.NotificationDispatchMessage
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationEventSerializer
import me.rgunny.kachi.notification.contract.NotificationDispatchEvent
import me.rgunny.kachi.notification.contract.NotificationChannel as ContractNotificationChannel
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class JacksonNotificationEventSerializer(
    private val jsonMapper: JsonMapper
) : NotificationEventSerializer {

    override fun serializeDispatch(message: NotificationDispatchMessage): String {
        return jsonMapper.writeValueAsString(
            NotificationDispatchEvent(
                notificationId = message.notificationId.id.toString(),
                requestId = message.requestId,
                channel = ContractNotificationChannel.valueOf(message.channel.name),
                recipientId = message.recipientId,
                message = message.message,
            )
        )
    }
}
