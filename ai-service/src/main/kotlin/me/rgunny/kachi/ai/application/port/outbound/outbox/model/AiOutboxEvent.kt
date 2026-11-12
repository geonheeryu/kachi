package me.rgunny.kachi.ai.application.port.outbound.outbox.model

import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import java.time.Instant

/**
 * 발행 대상 도메인 이벤트.
 *
 * 발행 시점에 aggregate를 다시 읽지 않고 이벤트가 만들어진 순간의 값을 그대로 보낸다.
 * 이 타입은 application 안의 모델이며 broker로 나가는 payload 형식이 아니다.
 * 외부 계약으로 바꾸는 일은 [me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxEventSerializer]의 구현이 맡는다.
 *
 * sealed인 이유: 이벤트 종류는 [AiOutboxEventType]과 같은 닫힌 집합이다.
 * 종류가 늘면 직렬화 구현의 `when`이 컴파일되지 않으므로, 새 이벤트가 계약 없이 outbox에 들어가는 일을 타입으로 막는다.
 *
 * interface인 이유: 구현은 각자 필드를 가진 data class이고 여기서 공유하는 것은
 * 저장된 필드가 아니라 [eventKey]처럼 각 구현이 자기 값에서 파생하는 속성뿐이다.
 * 상위에 상태가 없으니 상속으로 묶을 것이 없다.
 *
 * [eventKey]는 aggregate 식별자에서 나온다. 내용 해시로 만들면 같은 aggregate를 다시 만들 때
 * outbox unique index에 걸려 도메인 저장까지 함께 막힌다.
 */
sealed interface AiOutboxEvent {
    val schemaVersion: Int
    val type: AiOutboxEventType

    /** outbox unique index의 값. 같은 이벤트를 두 번 기록하지 않게 막는다. */
    val eventKey: String

    /** 발행 순서를 지켜야 하는 단위. 같은 story의 이벤트는 같은 파티션으로 간다. */
    val partitionKey: String

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * 이벤트를 발행 대기 행으로 만든다. payload는 호출자가 직렬화한 결과를 넘긴다.
 */
fun AiOutboxEvent.toOutbox(payload: String, now: Instant): AiOutbox {
    return AiOutbox.create(
        eventType = type,
        eventKey = eventKey,
        partitionKey = partitionKey,
        payload = payload,
        now = now
    )
}
