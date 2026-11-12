package me.rgunny.kachi.notification.service.adapter.inbound.messaging

import me.rgunny.kachi.notification.application.port.inbound.request.model.RequestNotificationCommand
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import me.rgunny.kachi.notification.contract.NotificationRequestedOrigin
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationOrigin

object NotificationRequestedEventMapper {

    fun toCommand(event: NotificationRequestedEvent): RequestNotificationCommand {
        require(event.schemaVersion == NotificationRequestedEvent.CURRENT_SCHEMA_VERSION) {
            "unsupported notification requested schemaVersion=${event.schemaVersion}"
        }

        return RequestNotificationCommand(
            requestId = event.requestId,
            requester = event.requester,
            channel = NotificationChannel.valueOf(event.channel.name),
            recipientId = event.recipientId,
            message = event.message,
            origin = event.origin?.let(::toOrigin) ?: NotificationOrigin.NONE,
        )
    }

    private fun toOrigin(origin: NotificationRequestedOrigin): NotificationOrigin {
        return NotificationOrigin(
            summaryId = origin.summaryId,
            storyId = origin.storyId,
            version = origin.version,
            keywords = origin.keywords,
            userId = origin.userId,
        )
    }
}
