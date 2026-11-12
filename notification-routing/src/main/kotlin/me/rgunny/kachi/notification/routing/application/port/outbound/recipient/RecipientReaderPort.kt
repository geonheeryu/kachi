package me.rgunny.kachi.notification.routing.application.port.outbound.recipient

import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage

/**
 * 수신자 조회 port.
 *
 * 구현체는 발송 가능한 (수신자, 채널)만 돌려준다. 없는 키워드, 관리자 없음은 빈 목록이다.
 * 조회 실패는 RecipientReaderException으로 던진다.
 */
interface RecipientReaderPort {

    /**
     * keywords 중 하나라도 구독한 수신자 x 채널의 한 페이지.
     *
     * keywords는 비어 있지 않다.
     * cursor가 null이면 첫 페이지이고, 아니면 앞 페이지의 nextCursor다.
     * 수신자가 0명인 페이지에도 nextCursor가 있을 수 있다.
     */
    suspend fun findSubscribers(keywords: Set<String>, cursor: String?): RecipientPage

    /** 관리자 x 채널. */
    suspend fun findAdmins(): List<Recipient>
}
