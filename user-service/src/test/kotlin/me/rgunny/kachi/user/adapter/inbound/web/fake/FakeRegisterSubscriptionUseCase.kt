package me.rgunny.kachi.user.adapter.inbound.web.fake

import me.rgunny.kachi.user.application.port.inbound.subscription.RegisterSubscriptionUseCase
import me.rgunny.kachi.user.application.port.inbound.subscription.model.RegisterSubscriptionCommand
import me.rgunny.kachi.user.application.port.inbound.subscription.model.SubscriptionResult
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.KeywordId
import me.rgunny.kachi.user.domain.SubscriptionId
import me.rgunny.kachi.user.fixture.UserTestFixture

/**
 * 구독 등록 유스케이스 대역.
 *
 * 컨트롤러·핸들러 테스트용이다.
 * 받은 커맨드를 [command]에 남겨 요청 -> 커맨드 매핑하고, 커맨드 값을 그대로 결과로 되돌려 응답 매핑을 본다.
 * [exception]을 넣으면 그 예외를 던져 핸들러 분기를 만든다.
 */
class FakeRegisterSubscriptionUseCase : RegisterSubscriptionUseCase {
    var exception: RuntimeException? = null
    lateinit var command: RegisterSubscriptionCommand

    override fun register(command: RegisterSubscriptionCommand): SubscriptionResult {
        exception?.let { throw it }
        this.command = command

        return SubscriptionResult(
            id = SubscriptionId.newId(),
            userId = command.userId,
            keywordId = KeywordId.newId(),
            name = command.name,
            canonicalKey = CanonicalKey.of(command.name).value,
            channels = command.channels,
            enabled = true,
            registeredAt = UserTestFixture.NOW,
            disabledAt = null
        )
    }
}
