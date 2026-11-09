package me.rgunny.kachi.ai.adapter.outbound.lock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@DisplayName("InMemoryExecutionLockAdapter")
class InMemoryExecutionLockAdapterTest {
    private val adapter = InMemoryExecutionLockAdapter(AiTestFixture.CLOCK)

    @Test
    @DisplayName("먼저 들어온 요청만 실행하고 나중 요청은 쥔 시각과 함께 막는다")
    fun blockSecondRequestWhileFirstIsRunning() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()

        val first = async {
            adapter.withLock(AiExecutionLock.NEWS_SUMMARY) {
                started.complete(Unit)
                complete.await()
                "실행됨"
            }
        }
        started.await()

        var secondActionCalled = false
        val second = adapter.withLock(AiExecutionLock.NEWS_SUMMARY) { secondActionCalled = true }
        complete.complete(Unit)

        val blocked = assertIs<ExecutionLockOutcome.AlreadyHeld>(second)
        assertEquals(AiTestFixture.NOW, blocked.holder.acquiredAt)
        assertEquals(false, secondActionCalled)
        assertEquals("실행됨", assertIs<ExecutionLockOutcome.Executed<String>>(first.await()).value)
    }

    @Test
    @DisplayName("실행이 끝나면 다음 요청이 lock을 얻는다")
    fun releaseLockAfterActionFinished() = runBlocking {
        val first = adapter.withLock(AiExecutionLock.NEWS_SUMMARY) { 1 }
        val second = adapter.withLock(AiExecutionLock.NEWS_SUMMARY) { 2 }

        assertEquals(1, assertIs<ExecutionLockOutcome.Executed<Int>>(first).value)
        assertEquals(2, assertIs<ExecutionLockOutcome.Executed<Int>>(second).value)
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 lock을 풀고 예외를 그대로 올린다")
    fun releaseLockWhenActionThrows() = runBlocking {
        assertFailsWith<IllegalStateException> {
            adapter.withLock(AiExecutionLock.NEWS_SUMMARY) { error("작업 실패") }
        }

        val next = adapter.withLock(AiExecutionLock.NEWS_SUMMARY) { "다음 실행" }

        assertEquals("다음 실행", assertIs<ExecutionLockOutcome.Executed<String>>(next).value)
    }

    @Test
    @DisplayName("다른 실행 단위는 서로 막지 않는다")
    fun doNotBlockDifferentTarget() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()

        val collection = async {
            adapter.withLock(AiExecutionLock.NEWS_SUMMARY) {
                started.complete(Unit)
                complete.await()
            }
        }
        started.await()

        val relay = adapter.withLock(AiExecutionLock.OUTBOX_RELAY) { "relay 실행" }
        complete.complete(Unit)
        collection.await()

        assertEquals("relay 실행", assertIs<ExecutionLockOutcome.Executed<String>>(relay).value)
    }
}
