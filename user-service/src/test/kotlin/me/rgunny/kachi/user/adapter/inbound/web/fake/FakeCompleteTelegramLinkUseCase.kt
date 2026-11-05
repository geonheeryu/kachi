package me.rgunny.kachi.user.adapter.inbound.web.fake

import me.rgunny.kachi.user.application.port.inbound.binding.CompleteTelegramLinkUseCase
import me.rgunny.kachi.user.application.port.inbound.binding.model.CompleteTelegramLinkCommand

/**
 * 연결 완료 유스케이스 대역.
 * 받은 커맨드를 [command]에 남겨 요청 -> 커맨드 매핑을 보고, [exception]을 넣으면 그 예외를 던져 핸들러 분기를 만든다.
 */
class FakeCompleteTelegramLinkUseCase : CompleteTelegramLinkUseCase {
    var exception: RuntimeException? = null
    lateinit var command: CompleteTelegramLinkCommand

    override fun complete(command: CompleteTelegramLinkCommand) {
        exception?.let { throw it }
        this.command = command
    }
}
