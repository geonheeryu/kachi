package me.rgunny.kachi.notification.worker.adapter.outbound.sender

import me.rgunny.kachi.notification.application.port.outbound.sender.model.SendNotificationCommand
import me.rgunny.kachi.notification.application.port.outbound.sender.model.SendNotificationResult
import me.rgunny.kachi.notification.application.port.outbound.sender.NotificationSender
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.retry.RetryFailure
import me.rgunny.kachi.notification.domain.retry.RetryFailureCode
import org.slf4j.LoggerFactory

/**
 * 설정된 채널 가운데 실제 sender가 켜지지 않은 채널의 발송을 받는 mock sender.
 *
 * vendor를 부르지 않고 설정된 mode의 결과를 돌려준다.
 */
class MockNotificationSender(
    private val channels: Set<NotificationChannel>,
    private val mode: MockNotificationSenderMode,
) : NotificationSender {

    override fun supports(channel: NotificationChannel): Boolean {
        return channel in channels
    }

    override suspend fun send(command: SendNotificationCommand): SendNotificationResult {
        // ADDRESS-EXPOSURE: 수신 주소를 로그에 남긴다. 로컬 검증용 sender라 두지만 운영에서는 쓰지 않는다.
        log.info(
            "mock notification send notificationId={} channel={} address={} mode={}",
            command.notificationId.id,
            command.channel,
            command.address,
            mode,
        )

        return when (mode) {
            MockNotificationSenderMode.SUCCESS -> SendNotificationResult.Success(
                providerMessageId = "mock-${command.notificationId.id}",
            )
            MockNotificationSenderMode.TRANSIENT_FAILURE -> SendNotificationResult.TransientFailure(
                RetryFailure.of(RetryFailureCode.VENDOR_TRANSIENT_ERROR, "mock transient failure"),
            )
            MockNotificationSenderMode.RATE_LIMITED -> SendNotificationResult.RateLimited(
                RetryFailure.of(RetryFailureCode.VENDOR_RATE_LIMITED, "mock rate limited"),
            )
            MockNotificationSenderMode.PERMANENT_FAILURE -> SendNotificationResult.PermanentFailure(
                RetryFailure.of(RetryFailureCode.INVALID_RECIPIENT, "mock permanent failure"),
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MockNotificationSender::class.java)
    }
}
