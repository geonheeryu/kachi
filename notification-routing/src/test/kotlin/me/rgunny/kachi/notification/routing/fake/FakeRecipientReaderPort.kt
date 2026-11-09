package me.rgunny.kachi.notification.routing.fake

import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.RecipientReaderPort
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage

/**
 * 구독자 페이지 목록과 관리자 목록을 고정으로 돌려주는 수신자 조회 대역.
 *
 * findSubscribers는 호출 순서대로 subscriberPages를 하나씩 돌려주고, 목록을 넘겨 부르면 실패한다.
 * 요청한 (keywords, cursor)와 관리자 조회 횟수를 남긴다.
 */
class FakeRecipientReaderPort(
    private val subscriberPages: List<RecipientPage> = listOf(RecipientPage(recipients = emptyList(), nextCursor = null)),
    private val admins: List<Recipient> = emptyList(),
) : RecipientReaderPort {
    val subscriberRequests = mutableListOf<Pair<Set<String>, String?>>()
    var adminRequests = 0
        private set
    var failure: RuntimeException? = null

    override suspend fun findSubscribers(keywords: Set<String>, cursor: String?): RecipientPage {
        subscriberRequests += keywords to cursor
        failure?.let { throw it }
        check(subscriberRequests.size <= subscriberPages.size) {
            "no more subscriber pages. requests=${subscriberRequests.size}"
        }
        return subscriberPages[subscriberRequests.size - 1]
    }

    override suspend fun findAdmins(): List<Recipient> {
        adminRequests += 1
        failure?.let { throw it }
        return admins
    }
}
