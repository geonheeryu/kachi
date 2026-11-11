package me.rgunny.kachi.user.adapter.inbound.web

import jakarta.validation.Valid
import java.util.UUID
import me.rgunny.kachi.user.adapter.inbound.web.dto.FindSubscriptionRecipientsRequest
import me.rgunny.kachi.user.adapter.inbound.web.dto.SubscriberResponse
import me.rgunny.kachi.user.adapter.inbound.web.dto.SubscriptionRecipientPageResponse
import me.rgunny.kachi.user.adapter.inbound.web.response.ApiResponse
import me.rgunny.kachi.user.application.port.inbound.internal.FindSubscribersUseCase
import me.rgunny.kachi.user.application.port.inbound.internal.FindSubscriptionRecipientsUseCase
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscribersQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery
import me.rgunny.kachi.user.domain.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 구독 internal API.
 *
 * 인증 없이 열려 있으며 알림 라우팅이 요약 이벤트의 키워드로 수신자를 물을 때 호출한다.
 */
@RestController
class InternalSubscriptionController(
    private val findSubscribersUseCase: FindSubscribersUseCase,
    private val findSubscriptionRecipientsUseCase: FindSubscriptionRecipientsUseCase
) {

    @GetMapping(ApiPaths.INTERNAL_SUBSCRIPTIONS, version = ApiVersions.V1)
    fun findSubscribers(
        @RequestParam keyword: String
    ): ResponseEntity<ApiResponse<List<SubscriberResponse>>> {
        val response = findSubscribersUseCase.findSubscribers(FindSubscribersQuery(keyword))
            .map(SubscriberResponse::from)

        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping(ApiPaths.INTERNAL_SUBSCRIPTION_RECIPIENTS_SEARCH, version = ApiVersions.V1)
    fun findSubscriptionRecipients(
        @Valid @RequestBody request: FindSubscriptionRecipientsRequest
    ): ResponseEntity<ApiResponse<SubscriptionRecipientPageResponse>> {
        val result = findSubscriptionRecipientsUseCase.findRecipients(
            FindSubscriptionRecipientsQuery(
                keywords = request.keywords,
                cursor = request.cursor?.let { UserId.of(UUID.fromString(it)) },
                limit = request.limitOrDefault()
            )
        )

        return ResponseEntity.ok(ApiResponse.success(SubscriptionRecipientPageResponse.from(result)))
    }
}
