package me.rgunny.kachi.notification.routing.adapter.outbound.recipient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto.UserServiceApiResponse
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto.UserServiceRecipientPageResponse
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto.UserServiceRecipientResponse
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto.UserServiceRecipientSearchRequest
import me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto.UserServiceUserChannelsResponse
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.RecipientReaderPort
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedRecipient
import me.rgunny.kachi.notification.routing.exception.routing.RecipientReaderException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingErrorCode
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.client.WebClient

/**
 * user-service 수신자 조회 internal API 클라이언트. 구독자는 키워드 페이지로, 관리자는 역할로 묻는다.
 *
 * 재시도와 서킷 브레이커는 두지 않는다. 실패는 RecipientReaderException으로 나가고 재시도는 호출자(Kafka consumer)가 한다.
 * 구독자 응답의 범위 밖 keywordIndexes, 빈 keywordIndexes, 모르는 채널은 USER_SERVICE_RESPONSE_INVALID다.
 */
class UserServiceRecipientReaderAdapter(
    private val webClient: WebClient,
    private val settings: UserServiceRecipientReaderSettings,
) : RecipientReaderPort {

    override suspend fun findSubscribers(keywords: Set<String>, cursor: String?): RecipientPage {
        require(keywords.isNotEmpty()) { "keywords must not be empty" }
        val detail = "keywordCount=${keywords.size}, cursor=$cursor"

        val page = fetch(RECIPIENT_PAGE_RESPONSE_TYPE, detail) { client ->
            client.post()
                .uri { builder -> builder.path(settings.recipientsSearchPath).build() }
                .bodyValue(
                    UserServiceRecipientSearchRequest(
                        keywords = keywords.toList(),
                        cursor = cursor,
                        limit = settings.pageSize,
                    )
                )
        }

        return toRecipientPage(page, detail)
    }

    override suspend fun findAdmins(): List<Recipient> {
        val users = fetch(USER_CHANNELS_RESPONSE_TYPE, "role=$ADMIN_ROLE") { client ->
            client.get()
                .uri { builder -> builder.path(settings.usersPath).queryParam("role", ADMIN_ROLE).build() }
        }

        return users.flatMap { user ->
            user.channels.map { channel -> Recipient(recipientId = user.userId, channel = toChannel(channel)) }
        }
    }

    private suspend fun <T> fetch(
        responseType: ParameterizedTypeReference<UserServiceApiResponse<T>>,
        detail: String,
        request: (WebClient) -> WebClient.RequestHeadersSpec<*>,
    ): T {
        val response = try {
            request(webClient)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { body ->
                            RecipientReaderException(
                                errorCode = RoutingErrorCode.USER_SERVICE_REQUEST_FAILED,
                                detail = "status=${response.statusCode().value()}, body=${body.take(MAX_ERROR_BODY_LENGTH)}",
                            )
                        }
                }
                .bodyToMono(responseType)
                .timeout(settings.timeout)
                .awaitSingle()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RecipientReaderException) {
            throw exception
        } catch (exception: Exception) {
            throw RecipientReaderException(
                errorCode = RoutingErrorCode.USER_SERVICE_REQUEST_FAILED,
                detail = "$detail, cause=${exception.javaClass.simpleName}",
                cause = exception,
            )
        }

        if (!response.success) {
            throw RecipientReaderException(RoutingErrorCode.USER_SERVICE_RESPONSE_FAILED, detail)
        }

        return response.data
            ?: throw RecipientReaderException(RoutingErrorCode.USER_SERVICE_RESPONSE_MISSING_DATA, detail)
    }

    private fun toRecipientPage(page: UserServiceRecipientPageResponse, detail: String): RecipientPage {
        return try {
            val keywords = page.keywords.map { SubscribedKeyword(canonicalKey = it.canonicalKey, displayName = it.displayName) }
            RecipientPage(
                recipients = page.recipients.map { recipient -> toSubscribedRecipient(recipient, keywords) },
                nextCursor = page.nextCursor,
            )
        } catch (exception: IllegalArgumentException) {
            throw RecipientReaderException(
                errorCode = RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID,
                detail = "$detail, ${exception.message}",
                cause = exception,
            )
        }
    }

    private fun toSubscribedRecipient(
        recipient: UserServiceRecipientResponse,
        keywords: List<SubscribedKeyword>,
    ): SubscribedRecipient {
        if (recipient.keywordIndexes.isEmpty()) {
            throw RecipientReaderException(
                errorCode = RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID,
                detail = "empty keywordIndexes. recipientId=${recipient.userId}, channel=${recipient.channel}",
            )
        }

        return SubscribedRecipient(
            recipientId = recipient.userId,
            channel = toChannel(recipient.channel),
            keywords = recipient.keywordIndexes.map { index ->
                keywords.getOrNull(index) ?: throw RecipientReaderException(
                    errorCode = RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID,
                    detail = "keywordIndex out of range. recipientId=${recipient.userId}, index=$index, keywordCount=${keywords.size}",
                )
            },
        )
    }

    private fun toChannel(channel: String): NotificationChannel {
        return when (channel) {
            "SLACK" -> NotificationChannel.SLACK
            "DISCORD" -> NotificationChannel.DISCORD
            "TELEGRAM" -> NotificationChannel.TELEGRAM
            else -> throw RecipientReaderException(
                errorCode = RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID,
                detail = "channel=$channel",
            )
        }
    }

    private companion object {
        const val MAX_ERROR_BODY_LENGTH = 500
        const val ADMIN_ROLE = "ADMIN"

        val RECIPIENT_PAGE_RESPONSE_TYPE =
            object : ParameterizedTypeReference<UserServiceApiResponse<UserServiceRecipientPageResponse>>() {}
        val USER_CHANNELS_RESPONSE_TYPE =
            object : ParameterizedTypeReference<UserServiceApiResponse<List<UserServiceUserChannelsResponse>>>() {}
    }
}
